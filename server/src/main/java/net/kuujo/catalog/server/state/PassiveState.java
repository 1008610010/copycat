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
package net.kuujo.catalog.server.state;

import net.kuujo.catalog.server.RaftServer;
import net.kuujo.catalog.client.error.RaftError;
import net.kuujo.catalog.client.request.*;
import net.kuujo.catalog.client.response.*;
import net.kuujo.catalog.server.request.*;
import net.kuujo.catalog.server.response.*;
import net.kuujo.catalog.server.storage.ConfigurationEntry;
import net.kuujo.catalog.server.storage.Entry;
import net.kuujo.catalog.server.storage.RaftEntry;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Passive state.
 *
 * @author <a href="http://github.com/kuujo">Jordan Halterman</a>
 */
class PassiveState extends AbstractState {
  private final Queue<AtomicInteger> counterPool = new ArrayDeque<>();

  public PassiveState(ServerContext context) {
    super(context);
  }

  @Override
  public RaftServer.State type() {
    return RaftServer.State.PASSIVE;
  }

  @Override
  protected CompletableFuture<AppendResponse> append(final AppendRequest request) {
    try {
      context.checkThread();

      // If the request indicates a term that is greater than the current term then
      // assign that term and leader to the current context and step down as leader.
      if (request.term() > context.getTerm() || (request.term() == context.getTerm() && context.getLeader() == null)) {
        context.setTerm(request.term());
        context.setLeader(request.leader());
      }

      return CompletableFuture.completedFuture(logResponse(handleAppend(logRequest(request))));
    } finally {
      request.release();
    }
  }

  /**
   * Starts the append process.
   */
  protected AppendResponse handleAppend(AppendRequest request) {
    // If the request term is less than the current term then immediately
    // reply false and return our current term. The leader will receive
    // the updated term and step down.
    if (request.term() < context.getTerm()) {
      LOGGER.warn("{} - Rejected {}: request term is less than the current term ({})", context.getAddress(), request, context.getTerm());
      return AppendResponse.builder()
        .withStatus(Response.Status.OK)
        .withTerm(context.getTerm())
        .withSucceeded(false)
        .withLogIndex(context.getLog().lastIndex())
        .build();
    } else if (request.logIndex() != 0 && request.logTerm() != 0) {
      return doCheckPreviousEntry(request);
    } else {
      return doAppendEntries(request);
    }
  }

  /**
   * Checks the previous log entry for consistency.
   */
  protected AppendResponse doCheckPreviousEntry(AppendRequest request) {
    if (request.logIndex() != 0 && context.getLog().isEmpty()) {
      LOGGER.warn("{} - Rejected {}: Previous index ({}) is greater than the local log's last index ({})", context.getAddress(), request, request.logIndex(), context.getLog().lastIndex());
      return AppendResponse.builder()
        .withStatus(Response.Status.OK)
        .withTerm(context.getTerm())
        .withSucceeded(false)
        .withLogIndex(context.getLog().lastIndex())
        .build();
    } else if (request.logIndex() != 0 && context.getLog().lastIndex() != 0 && request.logIndex() > context.getLog().lastIndex()) {
      LOGGER.warn("{} - Rejected {}: Previous index ({}) is greater than the local log's last index ({})", context.getAddress(), request, request.logIndex(), context.getLog().lastIndex());
      return AppendResponse.builder()
        .withStatus(Response.Status.OK)
        .withTerm(context.getTerm())
        .withSucceeded(false)
        .withLogIndex(context.getLog().lastIndex())
        .build();
    }

    // If the previous entry term doesn't match the local previous term then reject the request.
    try (RaftEntry entry = context.getLog().get(request.logIndex())) {
      if (entry == null || entry.getTerm() != request.logTerm()) {
        LOGGER.warn("{} - Rejected {}: Request log term does not match local log term {} for the same entry", context.getAddress(), request, entry != null ? entry.getTerm() : "unknown");
        return AppendResponse.builder()
          .withStatus(Response.Status.OK)
          .withTerm(context.getTerm())
          .withSucceeded(false)
          .withLogIndex(request.logIndex() <= context.getLog().lastIndex() ? request.logIndex() - 1 : context.getLog().lastIndex())
          .build();
      } else {
        return doAppendEntries(request);
      }
    }
  }

  /**
   * Appends entries to the local log.
   */
  @SuppressWarnings("unchecked")
  protected AppendResponse doAppendEntries(AppendRequest request) {
    // If the log contains entries after the request's previous log index
    // then remove those entries to be replaced by the request entries.
    if (!request.entries().isEmpty()) {

      // Iterate through request entries and append them to the log.
      for (RaftEntry entry : (Iterable<RaftEntry>) request.entries()) {
        // If the entry index is greater than the last log index, skip missing entries.
        if (context.getLog().lastIndex() < entry.getIndex()) {
          context.getLog().skip(entry.getIndex() - context.getLog().lastIndex() - 1).append(entry);
          LOGGER.debug("{} - Appended {} to log at index {}", context.getAddress(), entry, entry.getIndex());
        } else {
          // Compare the term of the received entry with the matching entry in the log.
          try (RaftEntry match = context.getLog().get(entry.getIndex())) {
            if (match != null) {
              if (entry.getTerm() != match.getTerm()) {
                // We found an invalid entry in the log. Remove the invalid entry and append the new entry.
                // If appending to the log fails, apply commits and reply false to the append request.
                LOGGER.warn("{} - Appended entry term does not match local log, removing incorrect entries", context.getAddress());
                context.getLog().truncate(entry.getIndex() - 1);
                context.getLog().append(entry);
                LOGGER.debug("{} - Appended {} to log at index {}", context.getAddress(), entry, entry.getIndex());
              }
            } else {
              context.getLog().truncate(entry.getIndex() - 1).append(entry);
              LOGGER.debug("{} - Appended {} to log at index {}", context.getAddress(), entry, entry.getIndex());
            }
          }
        }

        // If the entry is a configuration entry then immediately configure the cluster.
        if (entry instanceof ConfigurationEntry) {
          ConfigurationEntry configurationEntry = (ConfigurationEntry) entry;
          if (context.getCluster().isPassive()) {
            context.getCluster().configure(entry.getIndex(), configurationEntry.getActive(), configurationEntry.getPassive());
            if (context.getCluster().isActive()) {
              transition(RaftServer.State.FOLLOWER);
            }
          } else {
            context.getCluster().configure(entry.getIndex(), configurationEntry.getActive(), configurationEntry.getPassive());
            if (context.getCluster().isPassive()) {
              transition(RaftServer.State.PASSIVE);
            }
          }
        }
      }
    }

    // If we've made it this far, apply commits and send a successful response.
    context.getContext().execute(() -> applyCommits(request.commitIndex())).thenRun(() -> applyIndex(request.globalIndex()));

    return AppendResponse.builder()
      .withStatus(Response.Status.OK)
      .withTerm(context.getTerm())
      .withSucceeded(true)
      .withLogIndex(context.getLog().lastIndex())
      .build();
  }

  /**
   * Applies commits to the local state machine.
   */
  @SuppressWarnings("unchecked")
  protected CompletableFuture<Void> applyCommits(long commitIndex) {
    // Set the commit index, ensuring that the index cannot be decreased.
    context.setCommitIndex(Math.max(context.getCommitIndex(), commitIndex));

    // The entries to be applied to the state machine are the difference between min(lastIndex, commitIndex) and lastApplied.
    long lastIndex = context.getLog().lastIndex();
    long lastApplied = context.getLastApplied();

    long effectiveIndex = Math.min(lastIndex, context.getCommitIndex());

    // If the effective commit index is greater than the last index applied to the state machine then apply remaining entries.
    if (effectiveIndex > lastApplied) {
      long entriesToApply = effectiveIndex - lastApplied;
      LOGGER.debug("{} - Applying {} commits", context.getAddress(), entriesToApply);

      CompletableFuture<Void> future = new CompletableFuture<>();

      // Rather than composing all futures into a single future, use a counter to count completions in order to preserve memory.
      AtomicInteger counter = getCounter();

      for (long i = lastApplied + 1; i <= effectiveIndex; i++) {
        Entry entry = context.getLog().get(i);
        if (entry != null && !(entry instanceof ConfigurationEntry)) {
          applyEntry(entry).whenComplete((result, error) -> {
            if (isOpen() && error != null) {
              LOGGER.info("{} - An application error occurred: {}", context.getAddress(), error.getMessage());
            }

            if (counter.incrementAndGet() == entriesToApply) {
              future.complete(null);
              recycleCounter(counter);
            }
            entry.release();
          });
        }
      }
      return future;
    }
    return CompletableFuture.completedFuture(null);
  }

  /**
   * Gets a counter from the counter pool.
   */
  private AtomicInteger getCounter() {
    AtomicInteger counter = counterPool.poll();
    if (counter == null)
      counter = new AtomicInteger();
    counter.set(0);
    return counter;
  }

  /**
   * Adds a used counter to the counter pool.
   */
  private void recycleCounter(AtomicInteger counter) {
    counterPool.add(counter);
  }

  /**
   * Applies an entry to the state machine.
   */
  protected CompletableFuture<?> applyEntry(Entry entry) {
    LOGGER.debug("{} - Applying {}", context.getAddress(), entry);
    return context.getStateMachine().apply(entry);
  }

  /**
   * Recycles the log up to the given index.
   */
  protected void applyIndex(long globalIndex) {
    if (globalIndex > 0) {
      context.setGlobalIndex(globalIndex);
    }
  }

  @Override
  protected CompletableFuture<PollResponse> poll(PollRequest request) {
    try {
      context.checkThread();
      logRequest(request);

      return CompletableFuture.completedFuture(logResponse(PollResponse.builder()
        .withStatus(Response.Status.ERROR)
        .withError(RaftError.Type.ILLEGAL_MEMBER_STATE_ERROR)
        .build()));
    } finally {
      request.release();
    }
  }

  @Override
  protected CompletableFuture<VoteResponse> vote(VoteRequest request) {
    try {
      context.checkThread();
      logRequest(request);
      return CompletableFuture.completedFuture(logResponse(VoteResponse.builder()
        .withStatus(Response.Status.ERROR)
        .withError(RaftError.Type.ILLEGAL_MEMBER_STATE_ERROR)
        .build()));
    } finally {
      request.release();
    }
  }

  /**
   * Forwards the given request to the leader if possible.
   */
  protected <T extends Request<T>, U extends Response<U>> CompletableFuture<U> forward(T request) {
    request.acquire();
    CompletableFuture<U> future = new CompletableFuture<>();
    context.getConnections().getConnection(context.getLeader()).whenComplete((connection, connectError) -> {
      if (connectError == null) {
        connection.<T, U>send(request).whenComplete((response, responseError) -> {
          if (responseError == null) {
            future.complete(response);
          } else {
            future.completeExceptionally(responseError);
          }
        });
      } else {
        request.release();
      }
    });
    return future;
  }

  @Override
  protected CompletableFuture<CommandResponse> command(CommandRequest request) {
    try {
      context.checkThread();
      logRequest(request);
      if (context.getLeader() == null) {
        return CompletableFuture.completedFuture(logResponse(CommandResponse.builder()
          .withStatus(Response.Status.ERROR)
          .withError(RaftError.Type.NO_LEADER_ERROR)
          .build()));
      } else {
        return this.<CommandRequest, CommandResponse>forward(request).thenApply(this::logResponse);
      }
    } finally {
      request.release();
    }
  }

  @Override
  protected CompletableFuture<QueryResponse> query(QueryRequest request) {
    try {
      context.checkThread();
      logRequest(request);
      if (context.getLeader() == null) {
        return CompletableFuture.completedFuture(logResponse(QueryResponse.builder()
          .withStatus(Response.Status.ERROR)
          .withError(RaftError.Type.NO_LEADER_ERROR)
          .build()));
      } else {
        return this.<QueryRequest, QueryResponse>forward(request).thenApply(this::logResponse);
      }
    } finally {
      request.release();
    }
  }

  @Override
  protected CompletableFuture<RegisterResponse> register(RegisterRequest request) {
    try {
      context.checkThread();
      logRequest(request);

      return CompletableFuture.completedFuture(logResponse(RegisterResponse.builder()
        .withStatus(Response.Status.ERROR)
        .withError(RaftError.Type.ILLEGAL_MEMBER_STATE_ERROR)
        .build()));
    } finally {
      request.release();
    }
  }

  @Override
  protected CompletableFuture<KeepAliveResponse> keepAlive(KeepAliveRequest request) {
    try {
      context.checkThread();
      logRequest(request);

      return CompletableFuture.completedFuture(logResponse(KeepAliveResponse.builder()
        .withStatus(Response.Status.ERROR)
        .withError(RaftError.Type.ILLEGAL_MEMBER_STATE_ERROR)
        .build()));
    } finally {
      request.release();
    }
  }

  @Override
  protected CompletableFuture<JoinResponse> join(JoinRequest request) {
    try {
      context.checkThread();
      logRequest(request);

      if (context.getLeader() == null) {
        return CompletableFuture.completedFuture(logResponse(JoinResponse.builder()
          .withStatus(Response.Status.ERROR)
          .withError(RaftError.Type.ILLEGAL_MEMBER_STATE_ERROR)
          .build()));
      } else {
        return this.<JoinRequest, JoinResponse>forward(request).thenApply(this::logResponse);
      }
    } finally {
      request.release();
    }
  }

  @Override
  protected CompletableFuture<LeaveResponse> leave(LeaveRequest request) {
    try {
      context.checkThread();
      logRequest(request);

      if (context.getLeader() == null) {
        return CompletableFuture.completedFuture(logResponse(LeaveResponse.builder()
          .withStatus(Response.Status.ERROR)
          .withError(RaftError.Type.ILLEGAL_MEMBER_STATE_ERROR)
          .build()));
      } else {
        return this.<LeaveRequest, LeaveResponse>forward(request).thenApply(this::logResponse);
      }
    } finally {
      request.release();
    }
  }

}
