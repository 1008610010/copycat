/*
 * Copyright 2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.atomix.copycat.server;

import io.atomix.copycat.Command;
import io.atomix.copycat.Query;
import io.atomix.copycat.error.ConfigurationException;
import io.atomix.copycat.protocol.Address;
import io.atomix.copycat.protocol.Protocol;
import io.atomix.copycat.protocol.ProtocolServer;
import io.atomix.copycat.server.cluster.Cluster;
import io.atomix.copycat.server.cluster.Member;
import io.atomix.copycat.server.protocol.RaftProtocol;
import io.atomix.copycat.server.protocol.RaftProtocolServer;
import io.atomix.copycat.server.state.ServerContext;
import io.atomix.copycat.server.storage.Storage;
import io.atomix.copycat.server.storage.StorageLevel;
import io.atomix.copycat.util.Assert;
import io.atomix.copycat.util.concurrent.Futures;
import io.atomix.copycat.util.concurrent.Listener;
import io.atomix.copycat.util.concurrent.SingleThreadContext;
import io.atomix.copycat.util.concurrent.ThreadContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Provides a standalone implementation of the <a href="http://raft.github.io/">Raft consensus algorithm</a>.
 * <p>
 * To create a new server, use the server {@link CopycatServer.Builder}. Servers require
 * cluster membership information in order to perform communication. Each server must be provided a local {@link Address}
 * to which to bind the internal {@link io.atomix.copycat.server.protocol.RaftProtocolServer} and a set of addresses for other members in
 * the cluster.
 * <h2>State machines</h2>
 * Underlying each server is a {@link StateMachine}. The state machine is responsible for maintaining the state with
 * relation to {@link Command}s and {@link Query}s submitted to the server by a client. State machines are provided
 * in a factory to allow servers to transition between stateful and stateless states.
 * <pre>
 *   {@code
 *   Address address = new Address("123.456.789.0", 5000);
 *   Collection<Address> members = Arrays.asList(new Address("123.456.789.1", 5000), new Address("123.456.789.2", 5000));
 *
 *   CopycatServer server = CopycatServer.builder(address)
 *     .withStateMachine(MyStateMachine::new)
 *     .build();
 *   }
 * </pre>
 * Server state machines are responsible for registering {@link Command}s which can be submitted to the cluster. Raft
 * relies upon determinism to ensure consistency throughout the cluster, so <em>it is imperative that each server in
 * a cluster have the same state machine with the same commands.</em> State machines are provided to the server as
 * a {@link Supplier factory} to allow servers to {@link Member#promote(Member.Type) transition} between stateful
 * and stateless states.
 * <h2>Transports</h2>
 * By default, the server will use the {@code NettyTransport} for communication. You can configure the transport via
 * {@link CopycatServer.Builder#withProtocol(RaftProtocol)}. To use the Netty transport, ensure you have the
 * {@code io.atomix.catalyst:catalyst-netty} jar on your classpath.
 * <pre>
 * {@code
 * CopycatServer server = CopycatServer.builder(address)
 *   .withStateMachine(MyStateMachine::new)
 *   .withTransport(NettyTransport.builder()
 *     .withThreads(4)
 *     .build())
 *   .build();
 * }
 * </pre>
 * <h2>Storage</h2>
 * As {@link Command}s are received by the server, they're written to the Raft {@link io.atomix.copycat.server.storage.Log} and replicated to other members
 * of the cluster. By default, the log is stored on disk, but users can override the default {@link Storage} configuration
 * via {@link CopycatServer.Builder#withStorage(Storage)}. Most notably, to configure the storage module to store entries in
 * memory instead of disk, configure the {@link StorageLevel}.
 * <pre>
 * {@code
 * CopycatServer server = CopycatServer.builder(address)
 *   .withStateMachine(MyStateMachine::new)
 *   .withStorage(Storage.builder()
 *     .withDirectory(new File("logs"))
 *     .withStorageLevel(StorageLevel.DISK)
 *     .build())
 *   .build();
 * }
 * </pre>
 * Servers use the {@code Storage} object to manage the storage of cluster configurations, voting information, and
 * state machine snapshots in addition to logs. See the {@link Storage} documentation for more information.
 * <h2>Bootstrapping the cluster</h2>
 * Once a server has been built, it must either be {@link #bootstrap() bootstrapped} to form a new cluster or
 * {@link #join(Address...) joined} to an existing cluster. The simplest way to bootstrap a new cluster is to bootstrap
 * a single server to which additional servers can be joined.
 * <pre>
 *   {@code
 *   CompletableFuture<CopycatServer> future = server.bootstrap();
 *   future.thenRun(() -> {
 *     System.out.println("Server bootstrapped!");
 *   });
 *   }
 * </pre>
 * Alternatively, the bootstrapped cluster can include multiple servers by providing an initial configuration to the
 * {@link #bootstrap(Address...)} method on each server. When bootstrapping a multi-node cluster, the bootstrap configuration
 * must be identical on all servers for safety.
 * <pre>
 *   {@code
 *     List<Address> cluster = Arrays.asList(
 *       new Address("123.456.789.0", 5000),
 *       new Address("123.456.789.1", 5000),
 *       new Address("123.456.789.2", 5000)
 *     );
 *
 *     CompletableFuture<CopycatServer> future = server.bootstrap(cluster);
 *     future.thenRun(() -> {
 *       System.out.println("Cluster bootstrapped");
 *     });
 *   }
 * </pre>
 * <h2>Adding a server to an existing cluster</h2>
 * Once a single- or multi-node cluster has been {@link #bootstrap() bootstrapped}, often times users need to
 * add additional servers to the cluster. For example, some users prefer to bootstrap a single-node cluster and
 * add additional nodes to that server. Servers can join existing bootstrapped clusters using the {@link #join(Address...)}
 * method. When joining an existing cluster, the server simply needs to specify at least one reachable server in the
 * existing cluster.
 * <pre>
 *   {@code
 *     CopycatServer server = CopycatServer.builder(new Address("123.456.789.3", 5000))
 *       .withTransport(NettyTransport.builder().withThreads(4).build())
 *       .build();
 *
 *     List<Address> cluster = Arrays.asList(
 *       new Address("123.456.789.0", 5000),
 *       new Address("123.456.789.1", 5000),
 *       new Address("123.456.789.2", 5000)
 *     );
 *
 *     CompletableFuture<CopycatServer> future = server.join(cluster);
 *     future.thenRun(() -> {
 *       System.out.println("Server joined successfully!");
 *     });
 *   }
 * </pre>
 * <h2>Server types</h2>
 * Servers form new clusters and join existing clusters as active Raft voting members by default. However, for
 * large deployments Copycat also supports alternative types of nodes which are configured by setting the server
 * {@link Member.Type}. For example, the {@link Member.Type#PASSIVE PASSIVE} server type does not participate
 * directly in the Raft consensus algorithm and instead receives state changes via an asynchronous gossip protocol.
 * This allows passive members to scale sequential reads beyond the typical three- or five-node Raft cluster. The
 * {@link Member.Type#RESERVE RESERVE} server type is a stateless server that can act as a standby to the stateful
 * servers in the cluster, being {@link Member#promote(Member.Type) promoted} to a stateful state when necessary.
 * <p>
 * Server types are defined in the server builder simply by passing the initial {@link Member.Type} to the
 * {@link Builder#withType(Member.Type)} setter:
 * <pre>
 *   {@code
 *   CopycatServer server = CopycatServer.builder(address)
 *     .withType(Member.Type.PASSIVE)
 *     .withTransport(new NettyTransport())
 *     .build();
 *   }
 * </pre>
 *
 * @see StateMachine
 * @see RaftProtocol
 * @see Storage
 * @author <a href="http://github.com/kuujo">Jordan Halterman</a>
 */
public class CopycatServer {
  private static final Logger LOGGER = LoggerFactory.getLogger(CopycatServer.class);
  private static final String DEFAULT_HOST = "0.0.0.0";
  private static final int DEFAULT_PORT = 8700;

  /**
   * Returns a new Copycat server builder using the default host:port.
   * <p>
   * The server will be constructed at 0.0.0.0:8700.
   *
   * @return The server builder.
   */
  public static Builder builder() {
    return builder(new Address(DEFAULT_HOST, DEFAULT_PORT));
  }

  /**
   * Returns a new Copycat server builder.
   * <p>
   * The provided {@link Address} is the address to which to bind the server being constructed.
   *
   * @param address The address through which clients and servers connect to this server.
   * @return The server builder.
   */
  public static Builder builder(Address address) {
    return new Builder(address, address);
  }

  /**
   * Returns a new Copycat server builder.
   * <p>
   * The provided {@link Address}es are the client and server address to which to bind the server being
   * constructed respectively.
   *
   * @param clientAddress The address through which clients connect to the server.
   * @param serverAddress The local server member address.
   * @return The server builder.
   */
  public static Builder builder(Address clientAddress, Address serverAddress) {
    return new Builder(clientAddress, serverAddress);
  }

  /**
   * Copycat server state types.
   * <p>
   * States represent the context of the server's internal state machine. Throughout the lifetime of a server,
   * the server will periodically transition between states based on requests, responses, and timeouts.
   *
   * @author <a href="http://github.com/kuujo">Jordan Halterman</a>
   */
  public enum State {

    /**
     * Represents the state of an inactive server.
     * <p>
     * All servers start in this state and return to this state when {@link #leave() stopped}.
     */
    INACTIVE,

    /**
     * Represents the state of a server that is a reserve member of the cluster.
     * <p>
     * Reserve servers only receive notification of leader, term, and configuration changes.
     */
    RESERVE,

    /**
     * Represents the state of a server in the process of catching up its log.
     * <p>
     * Upon successfully joining an existing cluster, the server will transition to the passive state and remain there
     * until the leader determines that the server has caught up enough to be promoted to a full member.
     */
    PASSIVE,

    /**
     * Represents the state of a server participating in normal log replication.
     * <p>
     * The follower state is a standard Raft state in which the server receives replicated log entries from the leader.
     */
    FOLLOWER,

    /**
     * Represents the state of a server attempting to become the leader.
     * <p>
     * When a server in the follower state fails to receive communication from a valid leader for some time period,
     * the follower will transition to the candidate state. During this period, the candidate requests votes from
     * each of the other servers in the cluster. If the candidate wins the election by receiving votes from a majority
     * of the cluster, it will transition to the leader state.
     */
    CANDIDATE,

    /**
     * Represents the state of a server which is actively coordinating and replicating logs with other servers.
     * <p>
     * Leaders are responsible for handling and replicating writes from clients. Note that more than one leader can
     * exist at any given time, but Raft guarantees that no two leaders will exist for the same {@link Cluster#term()}.
     */
    LEADER

  }

  protected final String name;
  protected final Protocol clientProtocol;
  protected final RaftProtocol raftProtocol;
  protected final ProtocolServer clientServer;
  protected final RaftProtocolServer raftServer;
  protected final ServerContext context;
  private volatile CompletableFuture<CopycatServer> openFuture;
  private volatile CompletableFuture<Void> closeFuture;
  private Listener<Member> electionListener;
  private volatile boolean started;

  protected CopycatServer(String name, Protocol clientProtocol, RaftProtocol raftProtocol, ServerContext context) {
    this.name = Assert.notNull(name, "name");
    this.clientProtocol = clientProtocol;
    this.raftProtocol = Assert.notNull(raftProtocol, "raftProtocol");
    this.raftServer = raftProtocol.createServer();
    this.clientServer = !context.getCluster().member().serverAddress().equals(context.getCluster().member().clientAddress()) ? clientProtocol.createServer() : null;
    this.context = Assert.notNull(context, "context");
  }

  /**
   * Returns the server name.
   * <p>
   * The server name is provided to the server via the {@link Builder#withName(String) builder configuration}.
   * The name is used internally to manage the server's on-disk state. {@link io.atomix.copycat.server.storage.Log Log},
   * {@link io.atomix.copycat.server.storage.snapshot.SnapshotStore snapshot},
   * and {@link io.atomix.copycat.server.storage.system.MetaStore configuration} files stored on disk use
   * the server name as the prefix.
   *
   * @return The server name.
   */
  public String name() {
    return name;
  }

  /**
   * Returns the server storage.
   * <p>
   * The returned {@link Storage} object is the object provided to the server via the {@link Builder#withStorage(Storage) builder}
   * configuration. The storage object is immutable and is intended to provide runtime configuration information only. Users
   * should <em>never open logs, snapshots, or other storage related files</em> through the {@link Storage} API. Doing so
   * can conflict with internal server operations, resulting in the loss of state.
   *
   * @return The server storage.
   */
  public Storage storage() {
    return context.getStorage();
  }

  /**
   * Returns the server's cluster configuration.
   * <p>
   * The {@link Cluster} is representative of the server's current view of the cluster configuration. The first time
   * the server is {@link #bootstrap() started}, the cluster configuration will be initialized using the {@link Address}
   * list provided to the server {@link #builder(Address) builder}. For {@link StorageLevel#DISK persistent}
   * servers, subsequent starts will result in the last known cluster configuration being loaded from disk.
   * <p>
   * The returned {@link Cluster} can be used to modify the state of the cluster to which this server belongs. Note,
   * however, that users need not explicitly {@link Cluster#join(Address...) join} or {@link Cluster#leave() leave} the
   * cluster since starting and stopping the server results in joining and leaving the cluster respectively.
   *
   * @return The server's cluster configuration.
   */
  public Cluster cluster() {
    return context.getCluster();
  }

  /**
   * Returns the Copycat server state.
   * <p>
   * The initial state of a Raft server is {@link State#INACTIVE}. Once the server is {@link #bootstrap() started} and
   * until it is explicitly shutdown, the server will be in one of the active states - {@link State#PASSIVE},
   * {@link State#FOLLOWER}, {@link State#CANDIDATE}, or {@link State#LEADER}.
   *
   * @return The Copycat server state.
   */
  public State state() {
    return context.getState();
  }

  /**
   * Registers a state change listener.
   * <p>
   * Throughout the lifetime of the cluster, the server will periodically transition between various {@link CopycatServer.State states}.
   * Users can listen for and react to state change events. To determine when this server is elected leader, simply
   * listen for the {@link CopycatServer.State#LEADER} state.
   * <pre>
   *   {@code
   *   server.onStateChange(state -> {
   *     if (state == CopycatServer.State.LEADER) {
   *       System.out.println("Server elected leader!");
   *     }
   *   });
   *   }
   * </pre>
   *
   * @param listener The state change listener.
   * @return The listener context. This can be used to unregister the election listener via
   * {@link Listener#close()}.
   * @throws NullPointerException If {@code listener} is {@code null}
   */
  public Listener<State> onStateChange(Consumer<State> listener) {
    return context.onStateChange(listener);
  }

  /**
   * Returns the server execution context.
   * <p>
   * The thread context is the event loop that this server uses to communicate other Raft servers.
   * Implementations must guarantee that all asynchronous {@link java.util.concurrent.CompletableFuture} callbacks are
   * executed on a single thread via the returned {@link io.atomix.copycat.util.concurrent.ThreadContext}.
   *
   * @return The server thread context.
   */
  public ThreadContext context() {
    return context.getThreadContext();
  }

  /**
   * Bootstraps a single-node cluster.
   * <p>
   * Bootstrapping a single-node cluster results in the server forming a new cluster to which additional servers
   * can be joined.
   * <p>
   * Only {@link Member.Type#ACTIVE} members can be included in a bootstrap configuration. If the local server is
   * not initialized as an active member, it cannot be part of the bootstrap configuration for the cluster.
   * <p>
   * When the cluster is bootstrapped, the local server will be transitioned into the active state and begin
   * participating in the Raft consensus algorithm. When the cluster is first bootstrapped, no leader will exist.
   * The bootstrapped members will elect a leader amongst themselves. Once a cluster has been bootstrapped, additional
   * members may be {@link #join(Address...) joined} to the cluster. In the event that the bootstrapped members cannot
   * reach a quorum to elect a leader, bootstrap will continue until successful.
   * <p>
   * It is critical that all servers in a bootstrap configuration be started with the same exact set of members.
   * Bootstrapping multiple servers with different configurations may result in split brain.
   * <p>
   * The {@link CompletableFuture} returned by this method will be completed once the cluster has been bootstrapped,
   * a leader has been elected, and the leader has been notified of the local server's client configurations.
   *
   * @return A completable future to be completed once the cluster has been bootstrapped.
   */
  @SuppressWarnings("unchecked")
  public CompletableFuture<CopycatServer> bootstrap() {
    return bootstrap(Collections.EMPTY_LIST);
  }

  /**
   * Bootstraps the cluster using the provided cluster configuration.
   * <p>
   * Bootstrapping the cluster results in a new cluster being formed with the provided configuration. The initial
   * nodes in a cluster must always be bootstrapped. This is necessary to prevent split brain. If the provided
   * configuration is empty, the local server will form a single-node cluster.
   * <p>
   * Only {@link Member.Type#ACTIVE} members can be included in a bootstrap configuration. If the local server is
   * not initialized as an active member, it cannot be part of the bootstrap configuration for the cluster.
   * <p>
   * When the cluster is bootstrapped, the local server will be transitioned into the active state and begin
   * participating in the Raft consensus algorithm. When the cluster is first bootstrapped, no leader will exist.
   * The bootstrapped members will elect a leader amongst themselves. Once a cluster has been bootstrapped, additional
   * members may be {@link #join(Address...) joined} to the cluster. In the event that the bootstrapped members cannot
   * reach a quorum to elect a leader, bootstrap will continue until successful.
   * <p>
   * It is critical that all servers in a bootstrap configuration be started with the same exact set of members.
   * Bootstrapping multiple servers with different configurations may result in split brain.
   * <p>
   * The {@link CompletableFuture} returned by this method will be completed once the cluster has been bootstrapped,
   * a leader has been elected, and the leader has been notified of the local server's client configurations.
   *
   * @param cluster The bootstrap cluster configuration.
   * @return A completable future to be completed once the cluster has been bootstrapped.
   */
  public CompletableFuture<CopycatServer> bootstrap(Address... cluster) {
    return bootstrap(Arrays.asList(cluster));
  }

  /**
   * Bootstraps the cluster using the provided cluster configuration.
   * <p>
   * Bootstrapping the cluster results in a new cluster being formed with the provided configuration. The initial
   * nodes in a cluster must always be bootstrapped. This is necessary to prevent split brain. If the provided
   * configuration is empty, the local server will form a single-node cluster.
   * <p>
   * Only {@link Member.Type#ACTIVE} members can be included in a bootstrap configuration. If the local server is
   * not initialized as an active member, it cannot be part of the bootstrap configuration for the cluster.
   * <p>
   * When the cluster is bootstrapped, the local server will be transitioned into the active state and begin
   * participating in the Raft consensus algorithm. When the cluster is first bootstrapped, no leader will exist.
   * The bootstrapped members will elect a leader amongst themselves. Once a cluster has been bootstrapped, additional
   * members may be {@link #join(Address...) joined} to the cluster. In the event that the bootstrapped members cannot
   * reach a quorum to elect a leader, bootstrap will continue until successful.
   * <p>
   * It is critical that all servers in a bootstrap configuration be started with the same exact set of members.
   * Bootstrapping multiple servers with different configurations may result in split brain.
   * <p>
   * The {@link CompletableFuture} returned by this method will be completed once the cluster has been bootstrapped,
   * a leader has been elected, and the leader has been notified of the local server's client configurations.
   *
   * @param cluster The bootstrap cluster configuration.
   * @return A completable future to be completed once the cluster has been bootstrapped.
   */
  public CompletableFuture<CopycatServer> bootstrap(Collection<Address> cluster) {
    return start(() -> cluster().bootstrap(cluster));
  }

  /**
   * Joins the cluster.
   * <p>
   * Joining the cluster results in the local server being added to an existing cluster that has already been
   * bootstrapped. The provided configuration will be used to connect to the existing cluster and submit a join
   * request. Once the server has been added to the existing cluster's configuration, the join operation is complete.
   * <p>
   * Any {@link Member.Type type} of server may join a cluster. In order to join a cluster, the provided list of
   * bootstrapped members must be non-empty and must include at least one active member of the cluster. If no member
   * in the configuration is reachable, the server will continue to attempt to join the cluster until successful. If
   * the provided cluster configuration is empty, the returned {@link CompletableFuture} will be completed exceptionally.
   * <p>
   * When the server joins the cluster, the local server will be transitioned into its initial state as defined by
   * the configured {@link Member.Type}. Once the server has joined, it will immediately begin participating in
   * Raft and asynchronous replication according to its configuration.
   * <p>
   * It's important to note that the provided cluster configuration will only be used the first time the server attempts
   * to join the cluster. Thereafter, in the event that the server crashes and is restarted by {@code join}ing the cluster
   * again, the last known configuration will be used assuming the server is configured with persistent storage. Only when
   * the server leaves the cluster will its configuration and log be reset.
   * <p>
   * In order to preserve safety during configuration changes, Copycat leaders do not allow concurrent configuration
   * changes. In the event that an existing configuration change (a server joining or leaving the cluster or a
   * member being {@link Member#promote() promoted} or {@link Member#demote() demoted}) is under way, the local
   * server will retry attempts to join the cluster until successful. If the server fails to reach the leader,
   * the join will be retried until successful.
   *
   * @param cluster A collection of cluster member addresses to join.
   * @return A completable future to be completed once the local server has joined the cluster.
   */
  public CompletableFuture<CopycatServer> join(Address... cluster) {
    return join(Arrays.asList(cluster));
  }

  /**
   * Joins the cluster.
   * <p>
   * Joining the cluster results in the local server being added to an existing cluster that has already been
   * bootstrapped. The provided configuration will be used to connect to the existing cluster and submit a join
   * request. Once the server has been added to the existing cluster's configuration, the join operation is complete.
   * <p>
   * Any {@link Member.Type type} of server may join a cluster. In order to join a cluster, the provided list of
   * bootstrapped members must be non-empty and must include at least one active member of the cluster. If no member
   * in the configuration is reachable, the server will continue to attempt to join the cluster until successful. If
   * the provided cluster configuration is empty, the returned {@link CompletableFuture} will be completed exceptionally.
   * <p>
   * When the server joins the cluster, the local server will be transitioned into its initial state as defined by
   * the configured {@link Member.Type}. Once the server has joined, it will immediately begin participating in
   * Raft and asynchronous replication according to its configuration.
   * <p>
   * It's important to note that the provided cluster configuration will only be used the first time the server attempts
   * to join the cluster. Thereafter, in the event that the server crashes and is restarted by {@code join}ing the cluster
   * again, the last known configuration will be used assuming the server is configured with persistent storage. Only when
   * the server leaves the cluster will its configuration and log be reset.
   * <p>
   * In order to preserve safety during configuration changes, Copycat leaders do not allow concurrent configuration
   * changes. In the event that an existing configuration change (a server joining or leaving the cluster or a
   * member being {@link Member#promote() promoted} or {@link Member#demote() demoted}) is under way, the local
   * server will retry attempts to join the cluster until successful. If the server fails to reach the leader,
   * the join will be retried until successful.
   *
   * @param cluster A collection of cluster member addresses to join.
   * @return A completable future to be completed once the local server has joined the cluster.
   */
  public CompletableFuture<CopycatServer> join(Collection<Address> cluster) {
    return start(() -> cluster().join(cluster));
  }

  /**
   * Starts the server.
   */
  private CompletableFuture<CopycatServer> start(Supplier<CompletableFuture<Void>> joiner) {
    if (started)
      return CompletableFuture.completedFuture(this);

    if (openFuture == null) {
      synchronized (this) {
        if (openFuture == null) {
          Function<Void, CompletionStage<CopycatServer>> completionFunction = state -> {
            CompletableFuture<CopycatServer> future = new CompletableFuture<>();
            openFuture = null;
            joiner.get().whenComplete((result, error) -> {
              if (error == null) {
                if (cluster().leader() != null) {
                  started = true;
                  future.complete(this);
                } else {
                  electionListener = cluster().onLeaderElection(leader -> {
                    if (electionListener != null) {
                      started = true;
                      future.complete(this);
                      electionListener.close();
                      electionListener = null;
                    }
                  });
                }
              } else {
                future.completeExceptionally(error);
              }
            });
            return future;
          };

          if (closeFuture == null) {
            openFuture = listen().thenCompose(completionFunction);
          } else {
            openFuture = closeFuture.thenCompose(c -> listen().thenCompose(completionFunction));
          }
        }
      }
    }

    return openFuture.whenComplete((result, error) -> {
      if (error == null) {
        LOGGER.info("Server started successfully!");
      } else {
        LOGGER.warn("Failed to start server!");
      }
    });
  }

  /**
   * Starts listening the server.
   */
  private CompletableFuture<Void> listen() {
    CompletableFuture<Void> future = new CompletableFuture<>();
    context.getThreadContext().executor().execute(() -> {
      raftServer.listen(cluster().member().serverAddress(), context::connectServer).whenComplete((internalResult, internalError) -> {
        if (internalError == null) {
          // If the client address is different than the server address, start a separate client server.
          if (clientServer != null) {
            clientServer.listen(cluster().member().clientAddress(), context::connectClient).whenComplete((clientResult, clientError) -> {
              started = true;
              future.complete(null);
            });
          } else {
            started = true;
            future.complete(null);
          }
        } else {
          future.completeExceptionally(internalError);
        }
      });
    });

    return future;
  }

  /**
   * Returns a boolean indicating whether the server is running.
   *
   * @return Indicates whether the server is running.
   */
  public boolean isRunning() {
    return started;
  }

  /**
   * Shuts down the server without leaving the Copycat cluster.
   *
   * @return A completable future to be completed once the server has been shutdown.
   */
  public CompletableFuture<Void> shutdown() {
    if (!started)
      return Futures.exceptionalFuture(new IllegalStateException("context not open"));

    CompletableFuture<Void> future = new CompletableFuture<>();
    context.getThreadContext().executor().execute(() -> {
      started = false;
      if (clientServer != null) {
        clientServer.close().whenCompleteAsync((clientResult, clientError) -> {
          raftServer.close().whenCompleteAsync((internalResult, internalError) -> {
            if (internalError != null) {
              future.completeExceptionally(internalError);
            } else if (clientError != null) {
              future.completeExceptionally(clientError);
            } else {
              future.complete(null);
            }
          }, context.getThreadContext().executor());
        }, context.getThreadContext().executor());
      } else {
        raftServer.close().whenCompleteAsync((internalResult, internalError) -> {
          if (internalError != null) {
            future.completeExceptionally(internalError);
          } else {
            future.complete(null);
          }
        }, context.getThreadContext().executor());
      }

      context.transition(CopycatServer.State.INACTIVE);
    });

    return future.thenComposeAsync(v -> {
      if (clientProtocol != null) {
        return clientProtocol.close();
      }
      return CompletableFuture.completedFuture(null);
    }).thenComposeAsync(v -> raftProtocol.close())
      .whenCompleteAsync((result, error) -> {
        context.close();
        started = false;
      });
  }

  /**
   * Leaves the Copycat cluster.
   *
   * @return A completable future to be completed once the server has left the cluster.
   */
  public CompletableFuture<Void> leave() {
    if (!started)
      return CompletableFuture.completedFuture(null);

    if (closeFuture == null) {
      synchronized (this) {
        if (closeFuture == null) {
          if (openFuture == null) {
            closeFuture = cluster().leave().thenCompose(v -> shutdown()).thenRun(context::delete);
          } else {
            closeFuture = openFuture.thenCompose(c -> cluster().leave().thenCompose(v -> shutdown()).thenRun(context::delete));
          }
        }
      }
    }
    return closeFuture;
  }

  /**
   * Builds a single-use Copycat server.
   * <p>
   * This builder should be used to programmatically configure and construct a new {@link CopycatServer} instance.
   * The builder provides methods for configuring all aspects of a Copycat server. The {@code CopycatServer.Builder}
   * class cannot be instantiated directly. To create a new builder, use one of the
   * {@link CopycatServer#builder(Address) server builder factory} methods.
   * <pre>
   *   {@code
   *   CopycatServer.Builder builder = CopycatServer.builder(address);
   *   }
   * </pre>
   * Once the server has been configured, use the {@link #build()} method to build the server instance:
   * <pre>
   *   {@code
   *   CopycatServer server = CopycatServer.builder(address)
   *     ...
   *     .build();
   *   }
   * </pre>
   * Each server <em>must</em> be configured with a {@link StateMachine}. The state machine is the component of the
   * server that stores state and reacts to commands and queries submitted by clients to the cluster. State machines
   * are provided to the server in the form of a state machine {@link Supplier factory} to allow the server to reconstruct
   * its state when necessary.
   * <pre>
   *   {@code
   *   CopycatServer server = CopycatServer.builder(address)
   *     .withStateMachine(MyStateMachine::new)
   *     .build();
   *   }
   * </pre>
   * Similarly critical to the operation of the server are the {@link RaftProtocol} and {@link Storage} layers. By default,
   * servers are configured with the {@code io.atomix.catalyst.transport.netty.NettyTransport} transport if it's available on
   * the classpath. Users should provide a {@link Storage} instance to specify how the server stores state changes.
   */
  public static class Builder implements io.atomix.copycat.util.Builder<CopycatServer> {
    private static final String DEFAULT_NAME = "copycat";
    private static final Duration DEFAULT_ELECTION_TIMEOUT = Duration.ofMillis(750);
    private static final Duration DEFAULT_HEARTBEAT_INTERVAL = Duration.ofMillis(250);
    private static final Duration DEFAULT_SESSION_TIMEOUT = Duration.ofMillis(5000);
    private static final Duration DEFAULT_GLOBAL_SUSPEND_TIMEOUT = Duration.ofHours(1);

    private String name = DEFAULT_NAME;
    private Member.Type type = Member.Type.ACTIVE;
    private Protocol clientProtocol;
    private RaftProtocol raftProtocol;
    private Storage storage;
    private Supplier<StateMachine> stateMachineFactory;
    private Address clientAddress;
    private Address serverAddress;
    private Duration electionTimeout = DEFAULT_ELECTION_TIMEOUT;
    private Duration heartbeatInterval = DEFAULT_HEARTBEAT_INTERVAL;
    private Duration sessionTimeout = DEFAULT_SESSION_TIMEOUT;
    private Duration globalSuspendTimeout = DEFAULT_GLOBAL_SUSPEND_TIMEOUT;

    private Builder(Address clientAddress, Address serverAddress) {
      this.clientAddress = Assert.notNull(clientAddress, "clientAddress");
      this.serverAddress = Assert.notNull(serverAddress, "serverAddress");
    }

    /**
     * Sets the server name.
     * <p>
     * The server name is used to
     *
     * @param name The server name.
     * @return The server builder.
     */
    public Builder withName(String name) {
      this.name = Assert.notNull(name, "name");
      return this;
    }

    /**
     * Sets the initial server member type.
     *
     * @param type The initial server member type.
     * @return The server builder.
     */
    public Builder withType(Member.Type type) {
      this.type = Assert.notNull(type, "type");
      return this;
    }

    /**
     * Sets the client and server protocol.
     *
     * @param protocol The client and server protocol.
     * @return The server builder.
     * @throws NullPointerException if {@code protocol} is null
     */
    public Builder withProtocol(RaftProtocol protocol) {
      Assert.notNull(protocol, "protocol");
      this.raftProtocol = protocol;
      return this;
    }

    /**
     * Sets the client protocol.
     *
     * @param protocol The client protocol.
     * @return The server builder.
     * @throws NullPointerException if {@code protocol} is null
     */
    public Builder withClientProtocol(Protocol protocol) {
      this.clientProtocol = Assert.notNull(protocol, "protocol");
      return this;
    }

    /**
     * Sets the server protocol.
     *
     * @param protocol The server protocol.
     * @return The server builder.
     * @throws NullPointerException if {@code protocol} is null
     */
    public Builder withRaftProtocol(RaftProtocol protocol) {
      this.raftProtocol = Assert.notNull(protocol, "protocol");
      return this;
    }

    /**
     * Sets the storage module.
     *
     * @param storage The storage module.
     * @return The Raft server builder.
     * @throws NullPointerException if {@code storage} is null
     */
    public Builder withStorage(Storage storage) {
      this.storage = Assert.notNull(storage, "storage");
      return this;
    }

    /**
     * Sets the Raft state machine factory.
     *
     * @param factory The Raft state machine factory.
     * @return The server builder.
     * @throws NullPointerException if the {@code factory} is {@code null}
     */
    public Builder withStateMachine(Supplier<StateMachine> factory) {
      this.stateMachineFactory = Assert.notNull(factory, "factory");
      return this;
    }

    /**
     * Sets the Raft election timeout, returning the Raft configuration for method chaining.
     *
     * @param electionTimeout The Raft election timeout duration.
     * @return The Raft configuration.
     * @throws IllegalArgumentException If the election timeout is not positive
     * @throws NullPointerException if {@code electionTimeout} is null
     */
    public Builder withElectionTimeout(Duration electionTimeout) {
      Assert.argNot(electionTimeout.isNegative() || electionTimeout.isZero(), "electionTimeout must be positive");
      Assert.argNot(electionTimeout.toMillis() <= heartbeatInterval.toMillis(), "electionTimeout must be greater than heartbeatInterval");
      this.electionTimeout = Assert.notNull(electionTimeout, "electionTimeout");
      return this;
    }

    /**
     * Sets the Raft heartbeat interval, returning the Raft configuration for method chaining.
     *
     * @param heartbeatInterval The Raft heartbeat interval duration.
     * @return The Raft configuration.
     * @throws IllegalArgumentException If the heartbeat interval is not positive
     * @throws NullPointerException if {@code heartbeatInterval} is null
     */
    public Builder withHeartbeatInterval(Duration heartbeatInterval) {
      Assert.argNot(heartbeatInterval.isNegative() || heartbeatInterval.isZero(), "sessionTimeout must be positive");
      Assert.argNot(heartbeatInterval.toMillis() >= electionTimeout.toMillis(), "heartbeatInterval must be less than electionTimeout");
      this.heartbeatInterval = Assert.notNull(heartbeatInterval, "heartbeatInterval");
      return this;
    }

    /**
     * Sets the Raft session timeout, returning the Raft configuration for method chaining.
     *
     * @param sessionTimeout The Raft session timeout duration.
     * @return The server builder.
     * @throws IllegalArgumentException If the session timeout is not positive
     * @throws NullPointerException if {@code sessionTimeout} is null
     */
    public Builder withSessionTimeout(Duration sessionTimeout) {
      Assert.argNot(sessionTimeout.isNegative() || sessionTimeout.isZero(), "sessionTimeout must be positive");
      Assert.argNot(sessionTimeout.toMillis() <= electionTimeout.toMillis(), "sessionTimeout must be greater than electionTimeout");
      this.sessionTimeout = Assert.notNull(sessionTimeout, "sessionTimeout");
      return this;
    }

    /**
     * Sets the timeout after which suspended global replication will resume and force a partitioned follower
     * to truncate its log once the partition heals.
     *
     * @param globalSuspendTimeout The timeout after which to resume global replication.
     * @return The server builder.
     */
    public Builder withGlobalSuspendTimeout(Duration globalSuspendTimeout) {
      Assert.notNull(globalSuspendTimeout, "globalSuspendTimeout");
      this.globalSuspendTimeout = Assert.argNot(globalSuspendTimeout, globalSuspendTimeout.isNegative() || globalSuspendTimeout.isZero(), "globalSuspendTimeout must be positive");
      return this;
    }

    /**
     * @throws ConfigurationException if a state machine, members or transport are not configured
     */
    @Override
    public CopycatServer build() {
      if (stateMachineFactory == null)
        throw new ConfigurationException("state machine not configured");

      // If the Raft protocol is not configured, use the default protocol.
      if (raftProtocol == null) {
        try {
          raftProtocol = (RaftProtocol) Class.forName("io.atomix.copycat.server.protocol.net.RaftNetProtocol").newInstance();
        } catch (ClassNotFoundException | InstantiationException | IllegalAccessException e) {
          throw new ConfigurationException("protocol not configured");
        }
      }

      // If the storage is not configured, create a new Storage instance with the configured serializer.
      if (storage == null) {
        storage = new Storage();
      }

      final ThreadContext threadContext = new SingleThreadContext(String.format("copycat-server-%s-%s", serverAddress, name));

      final ServerContext context = new ServerContext(name, type, serverAddress, clientAddress, raftProtocol, storage, stateMachineFactory, threadContext);
      context.setElectionTimeout(electionTimeout)
        .setHeartbeatInterval(heartbeatInterval)
        .setSessionTimeout(sessionTimeout)
        .setGlobalSuspendTimeout(globalSuspendTimeout);

      return new CopycatServer(name, clientProtocol, raftProtocol, context);
    }
  }

}
