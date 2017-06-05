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
package io.atomix.copycat.server.state;

import io.atomix.catalyst.concurrent.Futures;
import io.atomix.catalyst.concurrent.Scheduled;
import io.atomix.catalyst.transport.Connection;
import io.atomix.copycat.error.CopycatError;
import io.atomix.copycat.error.CopycatException;
import io.atomix.copycat.protocol.CloseSessionRequest;
import io.atomix.copycat.protocol.CloseSessionResponse;
import io.atomix.copycat.protocol.CommandRequest;
import io.atomix.copycat.protocol.CommandResponse;
import io.atomix.copycat.protocol.ConnectRequest;
import io.atomix.copycat.protocol.ConnectResponse;
import io.atomix.copycat.protocol.KeepAliveRequest;
import io.atomix.copycat.protocol.KeepAliveResponse;
import io.atomix.copycat.protocol.MetadataRequest;
import io.atomix.copycat.protocol.MetadataResponse;
import io.atomix.copycat.protocol.OpenSessionRequest;
import io.atomix.copycat.protocol.OpenSessionResponse;
import io.atomix.copycat.protocol.QueryRequest;
import io.atomix.copycat.protocol.QueryResponse;
import io.atomix.copycat.protocol.Response;
import io.atomix.copycat.server.CopycatServer;
import io.atomix.copycat.server.cluster.Member;
import io.atomix.copycat.server.protocol.AppendRequest;
import io.atomix.copycat.server.protocol.AppendResponse;
import io.atomix.copycat.server.protocol.JoinRequest;
import io.atomix.copycat.server.protocol.JoinResponse;
import io.atomix.copycat.server.protocol.LeaveRequest;
import io.atomix.copycat.server.protocol.LeaveResponse;
import io.atomix.copycat.server.protocol.PollRequest;
import io.atomix.copycat.server.protocol.PollResponse;
import io.atomix.copycat.server.protocol.ReconfigureRequest;
import io.atomix.copycat.server.protocol.ReconfigureResponse;
import io.atomix.copycat.server.protocol.VoteRequest;
import io.atomix.copycat.server.protocol.VoteResponse;
import io.atomix.copycat.server.storage.Indexed;
import io.atomix.copycat.server.storage.LogWriter;
import io.atomix.copycat.server.storage.entry.CloseSessionEntry;
import io.atomix.copycat.server.storage.entry.CommandEntry;
import io.atomix.copycat.server.storage.entry.ConfigurationEntry;
import io.atomix.copycat.server.storage.entry.InitializeEntry;
import io.atomix.copycat.server.storage.entry.KeepAliveEntry;
import io.atomix.copycat.server.storage.entry.MetadataEntry;
import io.atomix.copycat.server.storage.entry.OpenSessionEntry;
import io.atomix.copycat.server.storage.entry.QueryEntry;
import io.atomix.copycat.server.storage.system.Configuration;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.stream.Collectors;

/**
 * Leader state.
 *
 * @author <a href="http://github.com/kuujo">Jordan Halterman</a>
 */
final class LeaderState extends ActiveState {
  private final LeaderAppender appender;
  private Scheduled appendTimer;
  private long configuring;

  public LeaderState(ServerContext context) {
    super(context);
    this.appender = new LeaderAppender(this);
  }

  @Override
  public CopycatServer.State type() {
    return CopycatServer.State.LEADER;
  }

  @Override
  public synchronized CompletableFuture<ServerState> open() {
    // Reset state for the leader.
    takeLeadership();

    // Append initial entries to the log, including an initial no-op entry and the server's configuration.
    appendInitialEntries();

    // Commit the initial leader entries.
    commitInitialEntries();

    return super.open()
      .thenRun(this::startAppendTimer)
      .thenApply(v -> this);
  }

  /**
   * Sets the current node as the cluster leader.
   */
  private void takeLeadership() {
    context.setLeader(context.getCluster().member().id());
    context.getClusterState().getRemoteMemberStates().forEach(m -> m.resetState(context.getLog()));
  }

  /**
   * Appends initial entries to the log to take leadership.
   */
  private void appendInitialEntries() {
    final long term = context.getTerm();

    final LogWriter writer = context.getLogWriter();
    try {
      writer.lock();
      Indexed<InitializeEntry> indexed = writer.append(term, new InitializeEntry(appender.time()));
      LOGGER.debug("{} - Appended {}", context.getCluster().member().address(), indexed.index());
    } finally {
      writer.unlock();
    }

    // Append a configuration entry to propagate the leader's cluster configuration.
    configure(context.getCluster().members());
  }

  /**
   * Commits a no-op entry to the log, ensuring any entries from a previous term are committed.
   */
  private CompletableFuture<Void> commitInitialEntries() {
    // The Raft protocol dictates that leaders cannot commit entries from previous terms until
    // at least one entry from their current term has been stored on a majority of servers. Thus,
    // we force entries to be appended up to the leader's no-op entry. The LeaderAppender will ensure
    // that the commitIndex is not increased until the no-op entry (appender.index()) is committed.
    CompletableFuture<Void> future = new CompletableFuture<>();
    appender.appendEntries(appender.index()).whenComplete((resultIndex, error) -> {
      context.checkThread();
      if (isOpen()) {
        if (error == null) {
          context.getStateMachine().apply(resultIndex);
          future.complete(null);
        } else {
          context.setLeader(0);
          context.transition(CopycatServer.State.FOLLOWER);
        }
      }
    });
    return future;
  }

  /**
   * Starts sending AppendEntries requests to all cluster members.
   */
  private void startAppendTimer() {
    // Set a timer that will be used to periodically synchronize with other nodes
    // in the cluster. This timer acts as a heartbeat to ensure this node remains
    // the leader.
    LOGGER.trace("{} - Starting append timer", context.getCluster().member().address());
    appendTimer = context.getThreadContext().schedule(Duration.ZERO, context.getHeartbeatInterval(), this::appendMembers);
  }

  /**
   * Sends AppendEntries requests to members of the cluster that haven't heard from the leader in a while.
   */
  private void appendMembers() {
    context.checkThread();
    if (isOpen()) {
      appender.appendEntries();
    }
  }

  /**
   * Returns a boolean value indicating whether a configuration is currently being committed.
   *
   * @return Indicates whether a configuration is currently being committed.
   */
  boolean configuring() {
    return configuring > 0;
  }

  /**
   * Returns a boolean value indicating whether the leader is still being initialized.
   *
   * @return Indicates whether the leader is still being initialized.
   */
  boolean initializing() {
    // If the leader index is 0 or is greater than the commitIndex, do not allow configuration changes.
    // Configuration changes should not be allowed until the leader has committed a no-op entry.
    // See https://groups.google.com/forum/#!topic/raft-dev/t4xj6dJTP6E
    return appender.index() == 0 || context.getCommitIndex() < appender.index();
  }

  /**
   * Commits the given configuration.
   */
  protected CompletableFuture<Long> configure(Collection<Member> members) {
    context.checkThread();

    final long term = context.getTerm();

    final LogWriter writer = context.getLogWriter();
    final Indexed<ConfigurationEntry> entry;
    try {
      writer.lock();
      entry = writer.append(term, new ConfigurationEntry(System.currentTimeMillis(), members));
      LOGGER.debug("{} - Appended {}", context.getCluster().member().address(), entry);
    } finally {
      writer.unlock();
    }

    // Store the index of the configuration entry in order to prevent other configurations from
    // being logged and committed concurrently. This is an important safety property of Raft.
    configuring = entry.index();
    context.getClusterState().configure(new Configuration(entry.index(), entry.term(), entry.entry().timestamp(), entry.entry().members()));

    return appender.appendEntries(entry.index()).whenComplete((commitIndex, commitError) -> {
      context.checkThread();
      if (isOpen()) {
        // Reset the configuration index to allow new configuration changes to be committed.
        configuring = 0;
      }
    });
  }

  @Override
  public CompletableFuture<JoinResponse> join(final JoinRequest request) {
    context.checkThread();
    logRequest(request);

    // If another configuration change is already under way, reject the configuration.
    // If the leader index is 0 or is greater than the commitIndex, reject the join requests.
    // Configuration changes should not be allowed until the leader has committed a no-op entry.
    // See https://groups.google.com/forum/#!topic/raft-dev/t4xj6dJTP6E
    if (configuring() || initializing()) {
      return CompletableFuture.completedFuture(logResponse(JoinResponse.builder()
        .withStatus(Response.Status.ERROR)
        .build()));
    }

    // If the member is already a known member of the cluster, complete the join successfully.
    if (context.getCluster().member(request.member().id()) != null) {
      return CompletableFuture.completedFuture(logResponse(JoinResponse.builder()
        .withStatus(Response.Status.OK)
        .withIndex(context.getClusterState().getConfiguration().index())
        .withTerm(context.getClusterState().getConfiguration().term())
        .withTime(context.getClusterState().getConfiguration().time())
        .withMembers(context.getCluster().members())
        .build()));
    }

    Member member = request.member();

    // Add the joining member to the members list. If the joining member's type is ACTIVE, join the member in the
    // PROMOTABLE state to allow it to get caught up without impacting the quorum size.
    Collection<Member> members = context.getCluster().members();
    members.add(new ServerMember(member.type(), member.status(), member.serverAddress(), member.clientAddress(), Instant.now()));

    CompletableFuture<JoinResponse> future = new CompletableFuture<>();
    configure(members).whenComplete((index, error) -> {
      context.checkThread();
      if (isOpen()) {
        if (error == null) {
          future.complete(logResponse(JoinResponse.builder()
            .withStatus(Response.Status.OK)
            .withIndex(index)
            .withTerm(context.getClusterState().getConfiguration().term())
            .withTime(context.getClusterState().getConfiguration().time())
            .withMembers(members)
            .build()));
        } else {
          future.complete(logResponse(JoinResponse.builder()
            .withStatus(Response.Status.ERROR)
            .withError(CopycatError.Type.INTERNAL_ERROR)
            .build()));
        }
      }
    });
    return future;
  }

  @Override
  public CompletableFuture<ReconfigureResponse> reconfigure(final ReconfigureRequest request) {
    context.checkThread();
    logRequest(request);

    // If another configuration change is already under way, reject the configuration.
    // If the leader index is 0 or is greater than the commitIndex, reject the promote requests.
    // Configuration changes should not be allowed until the leader has committed a no-op entry.
    // See https://groups.google.com/forum/#!topic/raft-dev/t4xj6dJTP6E
    if (configuring() || initializing()) {
      return CompletableFuture.completedFuture(logResponse(ReconfigureResponse.builder()
        .withStatus(Response.Status.ERROR)
        .build()));
    }

    // If the member is not a known member of the cluster, fail the promotion.
    ServerMember existingMember = context.getClusterState().member(request.member().id());
    if (existingMember == null) {
      return CompletableFuture.completedFuture(logResponse(ReconfigureResponse.builder()
        .withStatus(Response.Status.ERROR)
        .withError(CopycatError.Type.UNKNOWN_SESSION_ERROR)
        .build()));
    }

    // If the configuration request index is less than the last known configuration index for
    // the leader, fail the request to ensure servers can't reconfigure an old configuration.
    if (request.index() > 0 && request.index() < context.getClusterState().getConfiguration().index() || request.term() != context.getClusterState().getConfiguration().term()
      && (existingMember.type() != request.member().type() || existingMember.status() != request.member().status())) {
      return CompletableFuture.completedFuture(logResponse(ReconfigureResponse.builder()
        .withStatus(Response.Status.ERROR)
        .withError(CopycatError.Type.CONFIGURATION_ERROR)
        .build()));
    }

    Member member = request.member();

    // If the client address is being set or has changed, update the configuration.
    if (member.clientAddress() != null && (existingMember.clientAddress() == null || !existingMember.clientAddress().equals(member.clientAddress()))) {
      existingMember.update(member.clientAddress(), Instant.now());
    }

    // Update the member type.
    existingMember.update(request.member().type(), Instant.now());

    Collection<Member> members = context.getCluster().members();

    CompletableFuture<ReconfigureResponse> future = new CompletableFuture<>();
    configure(members).whenComplete((index, error) -> {
      context.checkThread();
      if (isOpen()) {
        if (error == null) {
          future.complete(logResponse(ReconfigureResponse.builder()
            .withStatus(Response.Status.OK)
            .withIndex(index)
            .withTerm(context.getClusterState().getConfiguration().term())
            .withTime(context.getClusterState().getConfiguration().time())
            .withMembers(members)
            .build()));
        } else {
          future.complete(logResponse(ReconfigureResponse.builder()
            .withStatus(Response.Status.ERROR)
            .withError(CopycatError.Type.INTERNAL_ERROR)
            .build()));
        }
      }
    });
    return future;
  }

  @Override
  public CompletableFuture<LeaveResponse> leave(final LeaveRequest request) {
    context.checkThread();
    logRequest(request);

    // If another configuration change is already under way, reject the configuration.
    // If the leader index is 0 or is greater than the commitIndex, reject the join requests.
    // Configuration changes should not be allowed until the leader has committed a no-op entry.
    // See https://groups.google.com/forum/#!topic/raft-dev/t4xj6dJTP6E
    if (configuring() || initializing()) {
      return CompletableFuture.completedFuture(logResponse(LeaveResponse.builder()
        .withStatus(Response.Status.ERROR)
        .build()));
    }

    // If the leaving member is not a known member of the cluster, complete the leave successfully.
    if (context.getCluster().member(request.member().id()) == null) {
      return CompletableFuture.completedFuture(logResponse(LeaveResponse.builder()
        .withStatus(Response.Status.OK)
        .withMembers(context.getCluster().members())
        .build()));
    }

    Member member = request.member();

    Collection<Member> members = context.getCluster().members();
    members.remove(member);

    CompletableFuture<LeaveResponse> future = new CompletableFuture<>();
    configure(members).whenComplete((index, error) -> {
      context.checkThread();
      if (isOpen()) {
        if (error == null) {
          future.complete(logResponse(LeaveResponse.builder()
            .withStatus(Response.Status.OK)
            .withIndex(index)
            .withTerm(context.getClusterState().getConfiguration().term())
            .withTime(context.getClusterState().getConfiguration().time())
            .withMembers(members)
            .build()));
        } else {
          future.complete(logResponse(LeaveResponse.builder()
            .withStatus(Response.Status.ERROR)
            .withError(CopycatError.Type.INTERNAL_ERROR)
            .build()));
        }
      }
    });
    return future;
  }

  @Override
  public CompletableFuture<PollResponse> poll(final PollRequest request) {
    logRequest(request);

    // If a member sends a PollRequest to the leader, that indicates that it likely healed from
    // a network partition and may have had its status set to UNAVAILABLE by the leader. In order
    // to ensure heartbeats are immediately stored to the member, update its status if necessary.
    MemberState member = context.getClusterState().getMemberState(request.candidate());
    if (member != null) {
      member.resetFailureCount();
      if (member.getMember().status() == Member.Status.UNAVAILABLE) {
        member.getMember().update(Member.Status.AVAILABLE, Instant.now());
        configure(context.getCluster().members());
      }
    }

    return CompletableFuture.completedFuture(logResponse(PollResponse.builder()
      .withStatus(Response.Status.OK)
      .withTerm(context.getTerm())
      .withAccepted(false)
      .build()));
  }

  @Override
  public CompletableFuture<VoteResponse> vote(final VoteRequest request) {
    if (updateTermAndLeader(request.term(), 0)) {
      LOGGER.debug("{} - Received greater term", context.getCluster().member().address());
      context.transition(CopycatServer.State.FOLLOWER);
      return super.vote(request);
    } else {
      logRequest(request);
      return CompletableFuture.completedFuture(logResponse(VoteResponse.builder()
        .withStatus(Response.Status.OK)
        .withTerm(context.getTerm())
        .withVoted(false)
        .build()));
    }
  }

  @Override
  public CompletableFuture<AppendResponse> append(final AppendRequest request) {
    context.checkThread();
    if (updateTermAndLeader(request.term(), request.leader())) {
      CompletableFuture<AppendResponse> future = super.append(request);
      context.transition(CopycatServer.State.FOLLOWER);
      return future;
    } else if (request.term() < context.getTerm()) {
      logRequest(request);
      return CompletableFuture.completedFuture(logResponse(AppendResponse.builder()
        .withStatus(Response.Status.OK)
        .withTerm(context.getTerm())
        .withSucceeded(false)
        .withLogIndex(context.getLogWriter().lastIndex())
        .build()));
    } else {
      context.setLeader(request.leader()).transition(CopycatServer.State.FOLLOWER);
      return super.append(request);
    }
  }

  @Override
  public CompletableFuture<MetadataResponse> metadata(MetadataRequest request) {
    context.checkThread();
    logRequest(request);

    CompletableFuture<MetadataResponse> future = new CompletableFuture<>();
    Indexed<MetadataEntry> entry = new Indexed<>(
      context.getStateMachine().getLastApplied(),
      context.getTerm(),
      new MetadataEntry(System.currentTimeMillis(), request.session()), 0);
    context.getStateMachine().<MetadataResult>apply(entry).whenComplete((result, error) -> {
      context.checkThread();
      if (isOpen()) {
        if (error == null) {
          future.complete(logResponse(MetadataResponse.builder()
            .withStatus(Response.Status.OK)
            .withSessions(result.sessions)
            .build()));
        } else {
          future.complete(logResponse(MetadataResponse.builder()
            .withStatus(Response.Status.ERROR)
            .withError(CopycatError.Type.INTERNAL_ERROR)
            .build()));
        }
      }
    });
    return future;
  }

  @Override
  public CompletableFuture<CommandResponse> command(final CommandRequest request) {
    context.checkThread();
    logRequest(request);

    // Get the client's server session. If the session doesn't exist, return an unknown session error.
    ServerSessionContext session = context.getStateMachine().getSessions().getSession(request.session());
    if (session == null) {
      return CompletableFuture.completedFuture(logResponse(CommandResponse.builder()
        .withStatus(Response.Status.ERROR)
        .withError(CopycatError.Type.UNKNOWN_SESSION_ERROR)
        .build()));
    }

    // If the command is LINEARIZABLE and the session's current sequence number is less then one prior to the request
    // sequence number, queue this request for handling later. We want to handle command requests in the order in which
    // they were sent by the client. Note that it's possible for the session sequence number to be greater than the request
    // sequence number. In that case, it's likely that the command was submitted more than once to the
    // cluster, and the command will be deduplicated once applied to the state machine.
    if (!session.setRequestSequence(request.sequence())) {
      return CompletableFuture.completedFuture(logResponse(CommandResponse.builder()
        .withStatus(Response.Status.ERROR)
        .withError(CopycatError.Type.COMMAND_ERROR)
        .withLastSequence(session.getRequestSequence())
        .build()));
    }

    final CompletableFuture<CommandResponse> future = new CompletableFuture<>();

    final long term = context.getTerm();
    final long timestamp = System.currentTimeMillis();

    final Indexed<CommandEntry> entry;

    final LogWriter writer = context.getLogWriter();
    try {
      writer.lock();
      entry = writer.append(term, new CommandEntry(timestamp, request.session(), request.sequence(), request.bytes()));
      LOGGER.debug("{} - Appended {}", context.getCluster().member().address(), entry);
    } finally {
      writer.unlock();
    }

    // Replicate the command to followers.
    appender.appendEntries(entry.index()).whenComplete((commitIndex, commitError) -> {
      context.checkThread();
      if (isOpen()) {
        // If the command was successfully committed, apply it to the state machine.
        if (commitError == null) {
          context.getStateMachine().<OperationResult>apply(entry.index()).whenComplete((result, error) -> {
            if (isOpen()) {
              completeOperation(result, CommandResponse.builder(), error, future);
            }
          });
        } else {
          future.complete(CommandResponse.builder()
            .withStatus(Response.Status.ERROR)
            .withError(CopycatError.Type.INTERNAL_ERROR)
            .build());
        }
      }
    });
    return future.thenApply(this::logResponse);
  }

  @Override
  public CompletableFuture<QueryResponse> query(final QueryRequest request) {
    final long timestamp = System.currentTimeMillis();

    context.checkThread();
    logRequest(request);

    final Indexed<QueryEntry> entry = new Indexed<>(
      request.index(),
      context.getTerm(),
      new QueryEntry(
        System.currentTimeMillis(),
        request.session(),
        request.sequence(),
        request.bytes()), 0);

    final CompletableFuture<QueryResponse> future;
    switch (request.consistency()) {
      case SEQUENTIAL:
        future = queryLocal(entry);
        break;
      case LINEARIZABLE_LEASE:
        future = queryBoundedLinearizable(entry);
        break;
      case LINEARIZABLE:
        future = queryLinearizable(entry);
        break;
      default:
        future = Futures.exceptionalFuture(new IllegalStateException("Unknown consistency level: " + request.consistency()));
    }
    return future.thenApply(this::logResponse);
  }

  /**
   * Executes a bounded linearizable query.
   * <p>
   * Bounded linearizable queries succeed as long as this server remains the leader. This is possible
   * since the leader will step down in the event it fails to contact a majority of the cluster.
   */
  private CompletableFuture<QueryResponse> queryBoundedLinearizable(Indexed<QueryEntry> entry) {
    return applyQuery(entry);
  }

  /**
   * Executes a linearizable query.
   * <p>
   * Linearizable queries are first sequenced with commands and then applied to the state machine. Once
   * applied, we verify the node's leadership prior to responding successfully to the query.
   */
  private CompletableFuture<QueryResponse> queryLinearizable(Indexed<QueryEntry> entry) {
    return applyQuery(entry)
      .thenCompose(response -> appender.appendEntries()
        .thenApply(index -> response)
        .exceptionally(error -> QueryResponse.builder()
          .withStatus(Response.Status.ERROR)
          .withError(CopycatError.Type.QUERY_ERROR)
          .build()));
  }

  @Override
  public CompletableFuture<ConnectResponse> connect(ConnectRequest request, Connection connection) {
    context.checkThread();
    logRequest(request);

    // Associate the connection with the appropriate client.
    context.getStateMachine().getSessions().registerConnection(request.session(), request.connection(), connection);

    return CompletableFuture.completedFuture(ConnectResponse.builder()
      .withStatus(Response.Status.OK)
      .withLeader(context.getCluster().member().clientAddress())
      .withMembers(context.getCluster().members().stream()
        .map(Member::clientAddress)
        .filter(m -> m != null)
        .collect(Collectors.toList()))
      .build())
      .thenApply(this::logResponse);
  }

  @Override
  public CompletableFuture<OpenSessionResponse> openSession(OpenSessionRequest request) {
    final long term = context.getTerm();
    final long timestamp = System.currentTimeMillis();

    // If the client submitted a session timeout, use the client's timeout, otherwise use the configured
    // default server session timeout.
    final long timeout;
    if (request.timeout() != 0) {
      timeout = request.timeout();
    } else {
      timeout = context.getSessionTimeout().toMillis();
    }

    context.checkThread();
    logRequest(request);

    final Indexed<OpenSessionEntry> entry;
    final LogWriter writer = context.getLogWriter();
    try {
      writer.lock();
      entry = writer.append(term, new OpenSessionEntry(timestamp, request.name(), request.type(), timeout));
      LOGGER.debug("{} - Appended {}", context.getCluster().member().address(), entry);
    } finally {
      writer.unlock();
    }

    CompletableFuture<OpenSessionResponse> future = new CompletableFuture<>();
    appender.appendEntries(entry.index()).whenComplete((commitIndex, commitError) -> {
      context.checkThread();
      if (isOpen()) {
        if (commitError == null) {
          context.getStateMachine().<Long>apply(entry.index()).whenComplete((sessionId, sessionError) -> {
            if (isOpen()) {
              if (sessionError == null) {
                future.complete(logResponse(OpenSessionResponse.builder()
                  .withStatus(Response.Status.OK)
                  .withSession(sessionId)
                  .withTimeout(timeout)
                  .build()));
              } else if (sessionError instanceof CompletionException && sessionError.getCause() instanceof CopycatException) {
                future.complete(logResponse(OpenSessionResponse.builder()
                  .withStatus(Response.Status.ERROR)
                  .withError(((CopycatException) sessionError.getCause()).getType())
                  .build()));
              } else if (sessionError instanceof CopycatException) {
                future.complete(logResponse(OpenSessionResponse.builder()
                  .withStatus(Response.Status.ERROR)
                  .withError(((CopycatException) sessionError).getType())
                  .build()));
              } else {
                future.complete(logResponse(OpenSessionResponse.builder()
                  .withStatus(Response.Status.ERROR)
                  .withError(CopycatError.Type.INTERNAL_ERROR)
                  .build()));
              }
            }
          });
        } else {
          future.complete(logResponse(OpenSessionResponse.builder()
            .withStatus(Response.Status.ERROR)
            .withError(CopycatError.Type.INTERNAL_ERROR)
            .build()));
        }
      }
    });

    return future;
  }

  @Override
  public CompletableFuture<KeepAliveResponse> keepAlive(KeepAliveRequest request) {
    final long term = context.getTerm();
    final long timestamp = System.currentTimeMillis();

    context.checkThread();
    logRequest(request);

    final Indexed<KeepAliveEntry> entry;
    final LogWriter writer = context.getLogWriter();
    try {
      writer.lock();
      entry = writer.append(term, new KeepAliveEntry(timestamp, request.sessionIds(), request.commandSequences(), request.eventIndexes(), request.connections()));
      LOGGER.debug("{} - Appended {}", context.getCluster().member().address(), entry);
    } finally {
      writer.unlock();
    }

    CompletableFuture<KeepAliveResponse> future = new CompletableFuture<>();
    appender.appendEntries(entry.index()).whenComplete((commitIndex, commitError) -> {
      context.checkThread();
      if (isOpen()) {
        if (commitError == null) {
          context.getStateMachine().apply(entry.index()).whenComplete((sessionResult, sessionError) -> {
            if (isOpen()) {
              if (sessionError == null) {
                future.complete(logResponse(KeepAliveResponse.builder()
                  .withStatus(Response.Status.OK)
                  .withLeader(context.getCluster().member().clientAddress())
                  .withMembers(context.getCluster().members().stream()
                    .map(Member::clientAddress)
                    .filter(m -> m != null)
                    .collect(Collectors.toList())).build()));
              } else if (sessionError instanceof CompletionException && sessionError.getCause() instanceof CopycatException) {
                future.complete(logResponse(KeepAliveResponse.builder()
                  .withStatus(Response.Status.ERROR)
                  .withLeader(context.getCluster().member().clientAddress())
                  .withError(((CopycatException) sessionError.getCause()).getType())
                  .build()));
              } else if (sessionError instanceof CopycatException) {
                future.complete(logResponse(KeepAliveResponse.builder()
                  .withStatus(Response.Status.ERROR)
                  .withLeader(context.getCluster().member().clientAddress())
                  .withError(((CopycatException) sessionError).getType())
                  .build()));
              } else {
                future.complete(logResponse(KeepAliveResponse.builder()
                  .withStatus(Response.Status.ERROR)
                  .withLeader(context.getCluster().member().clientAddress())
                  .withError(CopycatError.Type.INTERNAL_ERROR)
                  .build()));
              }
            }
          });
        } else {
          future.complete(logResponse(KeepAliveResponse.builder()
            .withStatus(Response.Status.ERROR)
            .withLeader(context.getCluster().member().clientAddress())
            .withError(CopycatError.Type.INTERNAL_ERROR)
            .build()));
        }
      }
    });

    return future;
  }

  @Override
  public CompletableFuture<CloseSessionResponse> closeSession(CloseSessionRequest request) {
    final long term = context.getTerm();
    final long timestamp = System.currentTimeMillis();

    context.checkThread();
    logRequest(request);

    final Indexed<CloseSessionEntry> entry;
    final LogWriter writer = context.getLogWriter();
    try {
      writer.lock();
      entry = writer.append(term, new CloseSessionEntry(timestamp, request.session()));
      LOGGER.debug("{} - Appended {}", context.getCluster().member().address(), entry);
    } finally {
      writer.unlock();
    }

    CompletableFuture<CloseSessionResponse> future = new CompletableFuture<>();
    appender.appendEntries(entry.index()).whenComplete((commitIndex, commitError) -> {
      context.checkThread();
      if (isOpen()) {
        if (commitError == null) {
          context.getStateMachine().<Long>apply(entry.index()).whenComplete((closeResult, closeError) -> {
            if (isOpen()) {
              if (closeError == null) {
                future.complete(logResponse(CloseSessionResponse.builder()
                  .withStatus(Response.Status.OK)
                  .build()));
              } else if (closeError instanceof CompletionException && closeError.getCause() instanceof CopycatException) {
                future.complete(logResponse(CloseSessionResponse.builder()
                  .withStatus(Response.Status.ERROR)
                  .withError(((CopycatException) closeError.getCause()).getType())
                  .build()));
              } else if (closeError instanceof CopycatException) {
                future.complete(logResponse(CloseSessionResponse.builder()
                  .withStatus(Response.Status.ERROR)
                  .withError(((CopycatException) closeError).getType())
                  .build()));
              } else {
                future.complete(logResponse(CloseSessionResponse.builder()
                  .withStatus(Response.Status.ERROR)
                  .withError(CopycatError.Type.INTERNAL_ERROR)
                  .build()));
              }
            }
          });
        } else {
          future.complete(logResponse(CloseSessionResponse.builder()
            .withStatus(Response.Status.ERROR)
            .withError(CopycatError.Type.INTERNAL_ERROR)
            .build()));
        }
      }
    });

    return future;
  }

  /**
   * Cancels the append timer.
   */
  private void cancelAppendTimer() {
    if (appendTimer != null) {
      LOGGER.trace("{} - Cancelling append timer", context.getCluster().member().address());
      appendTimer.cancel();
    }
  }

  /**
   * Ensures the local server is not the leader.
   */
  private void stepDown() {
    if (context.getLeader() != null && context.getLeader().equals(context.getCluster().member())) {
      context.setLeader(0);
    }
  }

  @Override
  public synchronized CompletableFuture<Void> close() {
    return super.close()
      .thenRun(appender::close)
      .thenRun(this::cancelAppendTimer)
      .thenRun(this::stepDown);
  }

}
