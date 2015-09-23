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
package io.atomix.catalog.client;

import io.atomix.catalog.client.session.ClientSession;
import io.atomix.catalog.client.session.Session;
import io.atomix.catalyst.serializer.Serializer;
import io.atomix.catalyst.serializer.ServiceLoaderTypeResolver;
import io.atomix.catalyst.transport.Address;
import io.atomix.catalyst.transport.Transport;
import io.atomix.catalyst.util.Assert;
import io.atomix.catalyst.util.ConfigurationException;
import io.atomix.catalyst.util.Managed;
import io.atomix.catalyst.util.concurrent.Futures;
import io.atomix.catalyst.util.concurrent.ThreadContext;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Raft client.
 *
 * @author <a href="http://github.com/kuujo">Jordan Halterman</a>
 */
public class RaftClient implements Managed<RaftClient> {

  /**
   * Returns a new Raft client builder.
   * <p>
   * The provided set of members will be used to connect to the Raft cluster. The members list does not have to represent
   * the complete list of servers in the cluster, but it must have at least one reachable member.
   *
   * @param members The cluster members to which to connect.
   * @return The client builder.
   */
  public static Builder builder(Address... members) {
    return builder(Arrays.asList(Assert.notNull(members, "members")));
  }

  /**
   * Returns a new Raft client builder.
   * <p>
   * The provided set of members will be used to connect to the Raft cluster. The members list does not have to represent
   * the complete list of servers in the cluster, but it must have at least one reachable member.
   *
   * @param members The cluster members to which to connect.
   * @return The client builder.
   */
  public static Builder builder(Collection<Address> members) {
    return new Builder(members);
  }

  private final Transport transport;
  private final Collection<Address> members;
  private final Serializer serializer;
  private ClientSession session;
  private CompletableFuture<RaftClient> openFuture;
  private CompletableFuture<Void> closeFuture;

  protected RaftClient(Transport transport, Collection<Address> members, Serializer serializer) {
    serializer.resolve(new ServiceLoaderTypeResolver());
    this.transport = Assert.notNull(transport, "transport");
    this.members = Assert.notNull(members, "members");
    this.serializer = Assert.notNull(serializer, "serializer");
  }

  /**
   * Returns the client execution context.
   * <p>
   * The execution context is the event loop that this client uses to communicate Raft servers.
   * Implementations must guarantee that all asynchronous {@link java.util.concurrent.CompletableFuture} callbacks are
   * executed on a single thread via the returned {@link io.atomix.catalyst.util.concurrent.ThreadContext}.
   * <p>
   * The {@link io.atomix.catalyst.util.concurrent.ThreadContext} can also be used to access the Raft client's internal
   * {@link io.atomix.catalyst.serializer.Serializer serializer} via {@link ThreadContext#serializer()}.
   *
   * @return The Raft context.
   */
  public ThreadContext context() {
    return session != null ? session.context() : null;
  }

  /**
   * Returns the client session.
   * <p>
   * The returned {@link Session} instance will remain constant throughout the lifetime of this client. Once the instance
   * is opened, the session will have been registered with the Raft cluster and listeners registered via
   * {@link Session#onOpen(java.util.function.Consumer)} will be called. In the event of a session expiration, listeners
   * registered via {@link Session#onClose(java.util.function.Consumer)} will be called.
   *
   * @return The client session.
   */
  public Session session() {
    return session;
  }

  /**
   * Submits an operation to the Raft cluster.
   * <p>
   * This method is provided for convenience. The submitted {@link Operation} must be an instance
   * of {@link Command} or {@link Query}.
   *
   * @param operation The operation to submit.
   * @param <T> The operation result type.
   * @return A completable future to be completed with the operation result.
   * @throws IllegalArgumentException If the {@link Operation} is not an instance of {@link Command} or {@link Query}.
   * @throws NullPointerException if {@code operation} is null
   */
  public <T> CompletableFuture<T> submit(Operation<T> operation) {
    Assert.notNull(operation, "operation");
    if (operation instanceof Command) {
      return submit((Command<T>) operation);
    } else if (operation instanceof Query) {
      return submit((Query<T>) operation);
    } else {
      throw new IllegalArgumentException("unknown operation type");
    }
  }

  /**
   * Submits a command to the Raft cluster.
   * <p>
   * Commands are used to alter state machine state. All commands will be forwarded to the current Raft leader.
   * Once a leader receives the command, it will write the command to its internal {@code Log} and
   * replicate it to a majority of the cluster. Once the command has been replicated to a majority of the cluster, it
   * will apply the command to its state machine and respond with the result.
   * <p>
   * Once the command has been applied to a server state machine, the returned {@link java.util.concurrent.CompletableFuture}
   * will be completed with the state machine output.
   *
   * @param command The command to submit.
   * @param <T> The command result type.
   * @return A completable future to be completed with the command result.
   * @throws NullPointerException if {@code command} is null
   */
  public <T> CompletableFuture<T> submit(Command<T> command) {
    Assert.notNull(command, "command");
    if (session == null)
      return Futures.exceptionalFuture(new IllegalStateException("client not open"));
    return session.submit(command);
  }

  /**
   * Submits a query to the Raft cluster.
   * <p>
   * Queries are used to read state machine state. The behavior of query submissions is primarily dependent on the
   * query's {@link io.atomix.catalog.client.Query.ConsistencyLevel}. For {@link io.atomix.catalog.client.Query.ConsistencyLevel#LINEARIZABLE}
   * and {@link io.atomix.catalog.client.Query.ConsistencyLevel#BOUNDED_LINEARIZABLE} consistency levels, queries will be forwarded
   * to the Raft leader. For lower consistency levels, queries are allowed to read from followers. All queries are executed
   * by applying queries to an internal server state machine.
   * <p>
   * Once the query has been applied to a server state machine, the returned {@link java.util.concurrent.CompletableFuture}
   * will be completed with the state machine output.
   *
   * @param query The query to submit.
   * @param <T> The query result type.
   * @return A completable future to be completed with the query result.
   * @throws NullPointerException if {@code query} is null
   */
  public <T> CompletableFuture<T> submit(Query<T> query) {
    Assert.notNull(query, "query");
    if (session == null)
      return Futures.exceptionalFuture(new IllegalStateException("client not open"));
    return session.submit(query);
  }

  @Override
  public CompletableFuture<RaftClient> open() {
    if (session != null && session.isOpen())
      return CompletableFuture.completedFuture(this);

    if (openFuture == null) {
      synchronized (this) {
        if (openFuture == null) {
          ClientSession session = new ClientSession(transport, members, serializer);
          if (closeFuture == null) {
            openFuture = session.open().thenApply(s -> {
              synchronized (this) {
                openFuture = null;
                this.session = session;
                return this;
              }
            });
          } else {
            openFuture = closeFuture.thenCompose(v -> session.open().thenApply(s -> {
              synchronized (this) {
                openFuture = null;
                this.session = session;
                return this;
              }
            }));
          }
        }
      }
    }
    return openFuture;
  }

  @Override
  public boolean isOpen() {
    return session != null && session.isOpen();
  }

  @Override
  public CompletableFuture<Void> close() {
    if (session == null || !session.isOpen())
      return CompletableFuture.completedFuture(null);

    if (closeFuture == null) {
      synchronized (this) {
        if (session == null) {
          return CompletableFuture.completedFuture(null);
        }

        if (closeFuture == null) {
          if (openFuture == null) {
            closeFuture = session.close().whenComplete((result, error) -> {
              synchronized (this) {
                session = null;
                closeFuture = null;
              }
            });
          } else {
            closeFuture = openFuture.thenCompose(v -> session.close().whenComplete((result, error) -> {
              synchronized (this) {
                session = null;
                closeFuture = null;
              }
            }));
          }
        }
      }
    }
    return closeFuture;
  }

  @Override
  public boolean isClosed() {
    return session == null || session.isClosed();
  }

  /**
   * Raft client builder.
   */
  public static class Builder extends io.atomix.catalyst.util.Builder<RaftClient> {
    private Transport transport;
    private Serializer serializer;
    private Set<Address> members;

    private Builder(Collection<Address> members) {
      this.members = new HashSet<>(Assert.notNull(members, "members"));
    }

    @Override
    protected void reset() {
      transport = null;
      serializer = null;
      members = null;
    }

    /**
     * Sets the client transport.
     *
     * @param transport The client transport.
     * @return The client builder.
     * @throws NullPointerException if {@code transport} is null
     */
    public Builder withTransport(Transport transport) {
      this.transport = Assert.notNull(transport, "transport");
      return this;
    }

    /**
     * Sets the client serializer.
     *
     * @param serializer The client serializer.
     * @return The client builder.
     * @throws NullPointerException if {@code serializer} is null
     */
    public Builder withSerializer(Serializer serializer) {
      this.serializer = Assert.notNull(serializer, "serializer");
      return this;
    }

    /**
     * @throws ConfigurationException if transport is not configured
     */
    @Override
    public RaftClient build() {
      // If the transport is not configured, attempt to use the default Netty transport.
      if (transport == null) {
        try {
          transport = (Transport) Class.forName("io.atomix.catalyst.transport.NettyTransport").newInstance();
        } catch (ClassNotFoundException | InstantiationException | IllegalAccessException e) {
          throw new ConfigurationException("transport not configured");
        }
      }

      // If no serializer instance was provided, create one.
      if (serializer == null) {
        serializer = new Serializer();
      }
      return new RaftClient(transport, members, serializer);
    }
  }

}
