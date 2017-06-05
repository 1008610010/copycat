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

import io.atomix.catalyst.buffer.HeapBuffer;
import io.atomix.catalyst.concurrent.Scheduled;
import io.atomix.catalyst.concurrent.ThreadContext;
import io.atomix.catalyst.serializer.Serializer;
import io.atomix.catalyst.util.Assert;
import io.atomix.copycat.Command;
import io.atomix.copycat.NoOpCommand;
import io.atomix.copycat.Operation;
import io.atomix.copycat.Query;
import io.atomix.copycat.error.ApplicationException;
import io.atomix.copycat.error.UnknownSessionException;
import io.atomix.copycat.server.Commit;
import io.atomix.copycat.server.StateMachine;
import io.atomix.copycat.server.StateMachineExecutor;
import io.atomix.copycat.server.session.SessionListener;
import io.atomix.copycat.server.storage.snapshot.Snapshot;
import io.atomix.copycat.server.storage.snapshot.SnapshotReader;
import io.atomix.copycat.server.storage.snapshot.SnapshotWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Raft server state machine executor.
 *
 * @author <a href="http://github.com/kuujo>Jordan Halterman</a>
 */
class ServerStateMachineExecutor implements StateMachineExecutor {
  private static final Logger LOGGER = LoggerFactory.getLogger(ServerStateMachineExecutor.class);
  private static final long SNAPSHOT_INTERVAL_MILLIS = 1000 * 60 * 10;

  private final StateMachine stateMachine;
  private final ServerContext server;
  private final ServerStateMachineSessions sessions;
  private final ServerStateMachineContext context;
  private final ThreadContext stateMachineExecutor;
  private final ThreadContext snapshotExecutor;
  private final Queue<Runnable> tasks = new ArrayDeque<>();
  private final List<ServerScheduledTask> scheduledTasks = new ArrayList<>();
  private final List<ServerScheduledTask> complete = new ArrayList<>();
  private final Map<Class, Function> operations = new HashMap<>();
  private volatile Snapshot pendingSnapshot;
  private long snapshotTime;
  private long snapshotIndex;
  private long timestamp;

  ServerStateMachineExecutor(long id, String name, String type, StateMachine stateMachine, ServerContext server, ServerSessionManager sessionManager, ThreadContext stateMachineExecutor, ThreadContext snapshotExecutor) {
    this.stateMachine = stateMachine;
    this.server = server;
    this.sessions = new ServerStateMachineSessions(sessionManager);
    this.stateMachineExecutor = stateMachineExecutor;
    this.snapshotExecutor = snapshotExecutor;
    this.context = new ServerStateMachineContext(id, name, type, sessions);
    init();
  }

  /**
   * Initializes the state machine.
   */
  private void init() {
    if (stateMachine instanceof SessionListener) {
      sessions.addListener((SessionListener) stateMachine);
    }
    stateMachine.init(this);
  }

  @Override
  public ServerStateMachineContext context() {
    return context;
  }

  @Override
  public Logger logger() {
    return stateMachineExecutor.logger();
  }

  @Override
  public Serializer serializer() {
    return stateMachineExecutor.serializer();
  }

  /**
   * Executes scheduled callbacks based on the provided time.
   */
  private void tick(long index, long timestamp) {
    this.timestamp = Math.max(this.timestamp, timestamp);

    // Only create an iterator if there are actually tasks scheduled.
    if (!scheduledTasks.isEmpty()) {

      // Iterate through scheduled tasks until we reach a task that has not met its scheduled time.
      // The tasks list is sorted by time on insertion.
      Iterator<ServerScheduledTask> iterator = scheduledTasks.iterator();
      while (iterator.hasNext()) {
        ServerScheduledTask task = iterator.next();
        if (task.complete(this.timestamp)) {
          context.update(index, Instant.ofEpochMilli(task.time), ServerStateMachineContext.Type.COMMAND);
          task.execute();
          complete.add(task);
          iterator.remove();
        } else {
          break;
        }
      }

      // Iterate through tasks that were completed and reschedule them.
      for (ServerScheduledTask task : complete) {
        task.reschedule(this.timestamp);
      }
      complete.clear();
    }

    // If the pending snapshot is non-null, attempt to complete the snapshot.
    if (pendingSnapshot != null) {
      maybeCompleteSnapshot(index);
    }

    // If a snapshot exists prior to the given index and hasn't yet been installed, install the snapshot.
    maybeInstallSnapshot(index);

    // Expire sessions that have timed out.
    expireSessions(index, this.timestamp);
  }

  /**
   * Expires sessions that have timed out.
   */
  private void expireSessions(long index, long timestamp) {
    // Iterate through registered sessions.
    for (ServerSessionContext session : sessions.sessions.values()) {

      // If the current timestamp minus the session timestamp is greater than the session timeout, expire the session.
      if (timestamp - session.getTimestamp() > session.timeout()) {

        // Remove the session from the sessions list.
        sessions.remove(session);

        // Update the state machine context with the keep-alive entry's index. This ensures that events published
        // as a result of asynchronous callbacks will be executed at the proper index with SEQUENTIAL consistency.
        context.update(index, Instant.ofEpochMilli(timestamp), ServerStateMachineContext.Type.COMMAND);

        // Expire the session.
        session.expire();

        // Iterate through and invoke session listeners.
        for (SessionListener listener : sessions.listeners) {
          listener.expire(session);
          listener.close(session);
        }
      }
    }
  }

  /**
   * Takes a snapshot of the state machine.
   */
  private synchronized void maybeTakeSnapshot(long index, long timestamp) {
    if (pendingSnapshot == null && snapshotTime == 0 || System.currentTimeMillis() - snapshotTime > SNAPSHOT_INTERVAL_MILLIS) {
      LOGGER.info("{} - Taking snapshot {}", server.getCluster().member().address(), index);
      context.update(index, Instant.ofEpochMilli(timestamp), ServerStateMachineContext.Type.SNAPSHOT);
      pendingSnapshot = server.getSnapshotStore().createTemporarySnapshot(context.id(), index);
      try (SnapshotWriter writer = pendingSnapshot.writer()) {
        writer.writeInt(sessions.sessions.size());
        for (ServerSessionContext session : sessions.sessions.values()) {
          writer.writeLong(session.id());
          writer.writeLong(session.timeout());
          writer.writeLong(session.getTimestamp());
        }
        stateMachine.snapshot(writer);
      }
      snapshotTime = System.currentTimeMillis();

      snapshotExecutor.execute(() -> {
        synchronized (this) {
          if (pendingSnapshot != null) {
            pendingSnapshot = pendingSnapshot.persist();
          }
        }
      });
    }
  }

  /**
   * Completes a state machine snapshot.
   */
  private synchronized void maybeCompleteSnapshot(long index) {
    if (pendingSnapshot != null) {
      // Compute the lowest completed index for all sessions that belong to this state machine.
      long lastCompleted = index;
      for (ServerSessionContext session : sessions.sessions.values()) {
        lastCompleted = Math.min(lastCompleted, session.getLastCompleted());
      }

      // If the lowest completed index for all sessions is greater than the snapshot index, complete the snapshot.
      if (lastCompleted >= pendingSnapshot.index()) {
        LOGGER.debug("{} - Completing snapshot {}", server.getCluster().member().address(), pendingSnapshot.index());
        pendingSnapshot.complete();

        // Update the snapshot index to ensure we don't simply install the same snapshot.
        snapshotIndex = pendingSnapshot.index();

        // Reset the pending snapshot.
        pendingSnapshot = null;
      }
    }
  }

  /**
   * Installs a snapshot if one exists.
   */
  private void maybeInstallSnapshot(long index) {
    Snapshot snapshot = server.getSnapshotStore().getSnapshotById(context.id());
    if (snapshot != null && snapshot.index() > snapshotIndex && snapshot.index() <= index) {
      LOGGER.info("{} - Installing snapshot {}", server.getCluster().member().address(), snapshot.index());
      try (SnapshotReader reader = snapshot.reader()) {
        int sessionCount = reader.readInt();
        sessions.sessions.clear();
        for (int i = 0; i < sessionCount; i++) {
          long sessionId = reader.readLong();
          long sessionTimeout = reader.readLong();
          long sessionTimestamp = reader.readLong();
          ServerSessionContext session = new ServerSessionContext(sessionId, context.name(), context.type(), sessionTimeout, this);
          session.setTimestamp(sessionTimestamp);
          session.setLastApplied(snapshot.index());
          sessions.sessions.put(sessionId, session);
        }
        stateMachine.install(reader);
      }
      snapshotIndex = snapshot.index();
    }
  }

  /**
   * Registers the given session.
   *
   * @param index The index of the registration.
   * @param timestamp The timestamp of the registration.
   * @param session The session to register.
   */
  CompletableFuture<Long> openSession(long index, long timestamp, ServerSessionContext session) {
    CompletableFuture<Long> future = new CompletableFuture<>();
    stateMachineExecutor.execute(() -> {
      // Update the session's timestamp to prevent it from being expired.
      session.setTimestamp(timestamp);

      // Trigger time-based events in the state machine.
      tick(index, timestamp);

      // Update the state machine context with the keep-alive entry's index. This ensures that events published
      // as a result of asynchronous callbacks will be executed at the proper index with SEQUENTIAL consistency.
      context.update(index, Instant.ofEpochMilli(timestamp), ServerStateMachineContext.Type.COMMAND);

      // Add the session to the sessions list.
      sessions.add(session);

      // Iterate through and invoke session listeners.
      for (SessionListener listener : sessions.listeners) {
        listener.register(session);
      }

      // Commit the index, causing events to be sent to clients if necessary.
      commit();

      // Complete the future.
      future.complete(session.id());
    });
    return future;
  }

  /**
   * Keeps the given session alive.
   *
   * @param index The index of the keep-alive.
   * @param timestamp The timestamp of the keep-alive.
   * @param session The session to keep-alive.
   * @param commandSequence The session command sequence number.
   * @param eventIndex The session event index.
   */
  CompletableFuture<Void> keepAlive(long index, long timestamp, ServerSessionContext session, long commandSequence, long eventIndex) {
    CompletableFuture<Void> future = new CompletableFuture<>();
    stateMachineExecutor.execute(() -> {
      // Update the session's timestamp to prevent it from being expired.
      session.setTimestamp(timestamp);

      // Trigger time-based events in the state machine.
      tick(index, timestamp);

      // Update the state machine context with the keep-alive entry's index. This ensures that events published
      // as a result of asynchronous callbacks will be executed at the proper index with SEQUENTIAL consistency.
      context.update(index, Instant.ofEpochMilli(timestamp), ServerStateMachineContext.Type.COMMAND);

      // Clear results cached in the session.
      session.clearResults(commandSequence);

      // Resend missing events starting from the last received event index.
      session.resendEvents(eventIndex);

      // Update the session's request sequence number. The command sequence number will be applied
      // iff the existing request sequence number is less than the command sequence number. This must
      // be applied to ensure that request sequence numbers are reset after a leader change since leaders
      // track request sequence numbers in local memory.
      session.resetRequestSequence(commandSequence);

      // Update the sessions' command sequence number. The command sequence number will be applied
      // iff the existing sequence number is less than the keep-alive command sequence number. This should
      // not be the case under normal operation since the command sequence number in keep-alive requests
      // represents the highest sequence for which a client has received a response (the command has already
      // been completed), but since the log compaction algorithm can exclude individual entries from replication,
      // the command sequence number must be applied for keep-alive requests to reset the sequence number in
      // the event the last command for the session was cleaned/compacted from the log.
      session.setCommandSequence(commandSequence);

      // Set the last applied index for the session. This will cause queries to be triggered if enqueued.
      session.setLastApplied(index);

      // Commit the index, causing events to be sent to clients if necessary.
      commit();

      // Complete the future.
      future.complete(null);
    });
    return future;
  }

  /**
   * Unregister the given session.
   *
   * @param index The index of the unregister.
   * @param timestamp The timestamp of the unregister.
   * @param session The session to unregister.
   */
  CompletableFuture<Void> closeSession(long index, long timestamp, ServerSessionContext session) {
    CompletableFuture<Void> future = new CompletableFuture<>();
    stateMachineExecutor.execute(() -> {
      // Update the session's timestamp to prevent it from being expired.
      session.setTimestamp(timestamp);

      // Trigger time-based events in the state machine.
      tick(index, timestamp);

      // Update the state machine context with the keep-alive entry's index. This ensures that events published
      // as a result of asynchronous callbacks will be executed at the proper index with SEQUENTIAL consistency.
      context.update(index, Instant.ofEpochMilli(timestamp), ServerStateMachineContext.Type.COMMAND);

      // Remove the session from the sessions list.
      sessions.remove(session);

      // Close the session.
      session.close();

      // Iterate through and invoke session listeners.
      for (SessionListener listener : sessions.listeners) {
        listener.unregister(session);
        listener.close(session);
      }

      // Commit the index, causing events to be sent to clients if necessary.
      commit();

      // Complete the future.
      future.complete(null);
    });
    return future;
  }

  /**
   * Resends events for the given session.
   *
   * @param index The index from which to resend events.
   * @param session The session for which to resend events.
   */
  void reset(long index, ServerSessionContext session) {
    stateMachineExecutor.execute(() -> session.resendEvents(index));
  }

  /**
   * Executes the given command on the state machine.
   *
   * @param index The index of the command.
   * @param timestamp The timestamp of the command.
   * @param sequence The command sequence number.
   * @param session The session that submitted the command.
   * @param bytes The command to execute.
   * @return A future to be completed with the command result.
   */
  CompletableFuture<OperationResult> executeCommand(long index, long sequence, long timestamp, ServerSessionContext session, byte[] bytes) {
    CompletableFuture<OperationResult> future = new CompletableFuture<>();
    stateMachineExecutor.execute(() -> executeCommand(index, sequence, timestamp, session, bytes, future));
    return future;
  }

  /**
   * Executes a command on the state machine thread.
   */
  private void executeCommand(long index, long sequence, long timestamp, ServerSessionContext session, byte[] bytes, CompletableFuture<OperationResult> future) {
    // Update the session's timestamp to prevent it from being expired.
    session.setTimestamp(timestamp);

    // Trigger time-based events in the state machine.
    tick(index, timestamp);

    // If the session is not open, fail the request.
    if (!session.state().active()) {
      future.completeExceptionally(new UnknownSessionException("Unknown session: " + session.id()));
      return;
    }

    // If the command's sequence number is less than the next session sequence number then that indicates that
    // we've received a command that was previously applied to the state machine. Ensure linearizability by
    // returning the cached response instead of applying it to the user defined state machine.
    if (sequence > 0 && sequence < session.nextCommandSequence()) {
      sequenceCommand(sequence, session, future);
    }
    // If we've made it this far, the command must have been applied in the proper order as sequenced by the
    // session. This should be the case for most commands applied to the state machine.
    else {
      // Execute the command in the state machine thread. Once complete, the CompletableFuture callback will be completed
      // in the state machine thread. Register the result in that thread and then complete the future in the caller's thread.
      applyCommand(index, sequence, timestamp, bytes, session, future);

      // Update the session timestamp and command sequence number. This is done in the caller's thread since all
      // timestamp/index/sequence checks are done in this thread prior to executing operations on the state machine thread.
      session.setCommandSequence(sequence);

      // Take a snapshot if the command count has surpassed the snapshot command count.
      maybeTakeSnapshot(index, timestamp);
    }
  }

  /**
   * Loads and returns a cached command result according to the sequence number.
   */
  private void sequenceCommand(long sequence, ServerSessionContext session, CompletableFuture<OperationResult> future) {
    OperationResult result = session.getResult(sequence);
    if (result == null) {
      LOGGER.debug("Missing command result for {}:{}", session.id(), sequence);
    }
    future.complete(result);
  }

  /**
   * Applies the given commit to the state machine.
   */
  private void applyCommand(long index, long sequence, long timestamp, byte[] bytes, ServerSessionContext session, CompletableFuture<OperationResult> future) {
    // No-op commands will be empty.
    if (bytes.length == 0) {
      future.complete(new OperationResult(index, session.getEventIndex(), null));
      return;
    }

    Command command = stateMachineExecutor.serializer().readObject(HeapBuffer.wrap(bytes));
    ServerCommit commit = new ServerCommit(index, command, session, timestamp);
    context.update(commit.index(), commit.time(), ServerStateMachineContext.Type.COMMAND);

    long eventIndex = session.getEventIndex();

    OperationResult result;
    try {
      // Execute the state machine operation and get the result.
      Object output = applyCommit(commit);

      // Once the operation has been applied to the state machine, commit events published by the command.
      // The state machine context will build a composite future for events published to all sessions.
      commit();

      // Store the result for linearizability and complete the command.
      result = new OperationResult(index, eventIndex, output);
      session.registerResult(sequence, result);
      future.complete(result);
    } catch (Exception e) {
      // If an exception occurs during execution of the command, store the exception.
      result = new OperationResult(index, eventIndex, e);
    }

    session.registerResult(sequence, result);
    future.complete(result);
  }

  /**
   * Executes the given query on the state machine.
   *
   * @param index The index of the query.
   * @param sequence The query sequence number.
   * @param timestamp The timestamp of the query.
   * @param session The session that submitted the query.
   * @param bytes The query to execute.
   * @return A future to be completed with the query result.
   */
  CompletableFuture<OperationResult> executeQuery(long index, long sequence, long timestamp, ServerSessionContext session, byte[] bytes) {
    CompletableFuture<OperationResult> future = new CompletableFuture<>();
    stateMachineExecutor.execute(() -> executeQuery(index, sequence, timestamp, session, bytes, future));
    return future;
  }

  /**
   * Executes a query on the state machine thread.
   */
  private void executeQuery(long index, long sequence, long timestamp, ServerSessionContext session, byte[] bytes, CompletableFuture<OperationResult> future) {
    // If the session is not open, fail the request.
    if (!session.state().active()) {
      future.completeExceptionally(new UnknownSessionException("Unknown session: " + session.id()));
      return;
    }

    // Otherwise, sequence the query.
    sequenceQuery(index, sequence, timestamp, session, bytes, future);
  }

  /**
   * Sequences the given query.
   */
  private void sequenceQuery(long index, long sequence, long timestamp, ServerSessionContext session, byte[] bytes, CompletableFuture<OperationResult> future) {
    // If the query's sequence number is greater than the session's current sequence number, queue the request for
    // handling once the state machine is caught up.
    if (sequence > session.getCommandSequence()) {
      session.registerSequenceQuery(sequence, () -> indexQuery(index, timestamp, session, bytes, future));
    } else {
      indexQuery(index, timestamp, session, bytes, future);
    }
  }

  /**
   * Ensures the given query is applied after the appropriate index.
   */
  private void indexQuery(long index, long timestamp, ServerSessionContext session, byte[] bytes, CompletableFuture<OperationResult> future) {
    // If the query index is greater than the session's last applied index, queue the request for handling once the
    // state machine is caught up.
    if (index > session.getLastApplied()) {
      session.registerIndexQuery(index, () -> applyQuery(index, timestamp, session, bytes, future));
    } else {
      applyQuery(index, timestamp, session, bytes, future);
    }
  }

  /**
   * Applies a query to the state machine.
   */
  private void applyQuery(long index, long timestamp, ServerSessionContext session, byte[] bytes, CompletableFuture<OperationResult> future) {
    // If the session is not open, fail the request.
    if (!session.state().active()) {
      future.completeExceptionally(new UnknownSessionException("Unknown session: " + session.id()));
      return;
    }

    Query query = stateMachineExecutor.serializer().readObject(HeapBuffer.wrap(bytes));
    ServerCommit commit = new ServerCommit(session.getLastApplied(), query, session, timestamp);
    context.update(commit.index(), commit.time(), ServerStateMachineContext.Type.QUERY);

    long eventIndex = session.getEventIndex();

    OperationResult result;
    try {
      result = new OperationResult(index, eventIndex, applyCommit(commit));
    } catch (Exception e) {
      result = new OperationResult(index, eventIndex, e);
    }
    future.complete(result);
  }

  /**
   * Executes an operation.
   */
  @SuppressWarnings("unchecked")
  private <T extends Operation<U>, U> U applyCommit(Commit commit) {
    // If the commit operation is a no-op command, complete the operation.
    if (commit.operation() instanceof NoOpCommand) {
      return null;
    }

    // Get the function registered for the operation. If no function is registered, attempt to
    // use a global function if available.
    Function function = operations.get(commit.type());

    if (function == null) {
      // If no operation function was found for the class, try to find an operation function
      // registered with a parent class.
      for (Map.Entry<Class, Function> entry : operations.entrySet()) {
        if (entry.getKey().isAssignableFrom(commit.type())) {
          function = entry.getValue();
          break;
        }
      }

      // If a parent operation function was found, store the function for future reference.
      if (function != null) {
        operations.put(commit.type(), function);
      }
    }

    if (function == null) {
      throw new IllegalStateException("unknown state machine operation: " + commit.type());
    } else {
      // Execute the operation. If the operation return value is a Future, await the result,
      // otherwise immediately complete the execution future.
      try {
        return (U) function.apply(commit);
      } catch (Exception e) {
        LOGGER.warn("State machine operation failed: {}", e);
        throw new ApplicationException(e, "An application error occurred");
      }
    }
  }

  /**
   * Commits the application of a command to the state machine.
   */
  @SuppressWarnings("unchecked")
  private void commit() {
    // Execute any tasks that were queue during execution of the command.
    if (!tasks.isEmpty()) {
      for (Runnable callback : tasks) {
        context.update(context.index(), context.clock().instant(), ServerStateMachineContext.Type.COMMAND);
        callback.run();
      }
      tasks.clear();
    }
    context.commit();
  }

  @Override
  public void execute(Runnable callback) {
    Assert.state(context.context() == ServerStateMachineContext.Type.COMMAND, "callbacks can only be scheduled during command execution");
    tasks.add(callback);
  }

  @Override
  public Scheduled schedule(Duration delay, Runnable callback) {
    Assert.state(context.context() == ServerStateMachineContext.Type.COMMAND, "callbacks can only be scheduled during command execution");
    LOGGER.trace("Scheduled callback {} with delay {}", callback, delay);
    return new ServerScheduledTask(callback, delay.toMillis()).schedule();
  }

  @Override
  public Scheduled schedule(Duration initialDelay, Duration interval, Runnable callback) {
    Assert.state(context.context() == ServerStateMachineContext.Type.COMMAND, "callbacks can only be scheduled during command execution");
    LOGGER.trace("Scheduled repeating callback {} with initial delay {} and interval {}", callback, initialDelay, interval);
    return new ServerScheduledTask(callback, initialDelay.toMillis(), interval.toMillis()).schedule();
  }

  @Override
  public <T extends Operation<Void>> StateMachineExecutor register(Class<T> type, Consumer<Commit<T>> callback) {
    Assert.notNull(type, "type");
    Assert.notNull(callback, "callback");
    operations.put(type, (Function<Commit<T>, Void>) commit -> {
      callback.accept(commit);
      return null;
    });
    LOGGER.trace("Registered void operation callback {}", type);
    return this;
  }

  @Override
  public <T extends Operation<U>, U> StateMachineExecutor register(Class<T> type, Function<Commit<T>, U> callback) {
    Assert.notNull(type, "type");
    Assert.notNull(callback, "callback");
    operations.put(type, callback);
    LOGGER.trace("Registered value operation callback {}", type);
    return this;
  }

  @Override
  public void close() {
    stateMachineExecutor.close();
  }

  /**
   * Scheduled task.
   */
  private class ServerScheduledTask implements Scheduled {
    private final long delay;
    private final long interval;
    private final Runnable callback;
    private long time;

    private ServerScheduledTask(Runnable callback, long delay) {
      this(callback, delay, 0);
    }

    private ServerScheduledTask(Runnable callback, long delay, long interval) {
      this.delay = delay;
      this.interval = interval;
      this.callback = callback;
      this.time = context.clock().instant().toEpochMilli() + delay;
    }

    /**
     * Schedules the task.
     */
    private Scheduled schedule() {
      // Perform binary search to insert the task at the appropriate position in the tasks list.
      if (scheduledTasks.isEmpty()) {
        scheduledTasks.add(this);
      } else {
        int l = 0;
        int u = scheduledTasks.size() - 1;
        int i;
        while (true) {
          i = (u + l) / 2;
          long t = scheduledTasks.get(i).time;
          if (t == time) {
            scheduledTasks.add(i, this);
            return this;
          } else if (t < time) {
            l = i + 1;
            if (l > u) {
              scheduledTasks.add(i + 1, this);
              return this;
            }
          } else {
            u = i - 1;
            if (l > u) {
              scheduledTasks.add(i, this);
              return this;
            }
          }
        }
      }
      return this;
    }

    /**
     * Reschedules the task.
     */
    private void reschedule(long timestamp) {
      if (interval > 0) {
        time = timestamp + interval;
        schedule();
      }
    }

    /**
     * Returns a boolean value indicating whether the task delay has been met.
     */
    private boolean complete(long timestamp) {
      return timestamp > time;
    }

    /**
     * Executes the task.
     */
    private synchronized void execute() {
      callback.run();
    }

    @Override
    public synchronized void cancel() {
      scheduledTasks.remove(this);
    }
  }
}
