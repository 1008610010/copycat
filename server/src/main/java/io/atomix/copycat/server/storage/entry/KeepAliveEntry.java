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
package io.atomix.copycat.server.storage.entry;

import io.atomix.catalyst.buffer.BufferInput;
import io.atomix.catalyst.buffer.BufferOutput;

import java.util.Arrays;

/**
 * Stores a client keep-alive request.
 *
 * @author <a href="http://github.com/kuujo">Jordan Halterman</a>
 */
public class KeepAliveEntry extends TimestampedEntry<KeepAliveEntry> {
  private final long[] sessionIds;
  private final long[] commandSequences;
  private final long[] eventIndexes;
  private final long[] connections;

  public KeepAliveEntry(long timestamp, long[] sessionIds, long[] commandSequences, long[] eventIndexes, long[] connections) {
    super(timestamp);
    this.sessionIds = sessionIds;
    this.commandSequences = commandSequences;
    this.eventIndexes = eventIndexes;
    this.connections = connections;
  }

  @Override
  public Type<KeepAliveEntry> type() {
    return Type.KEEP_ALIVE;
  }

  /**
   * Returns the session identifiers.
   *
   * @return The session identifiers.
   */
  public long[] sessionIds() {
    return sessionIds;
  }

  /**
   * Returns the command sequence numbers.
   *
   * @return The command sequence numbers.
   */
  public long[] commandSequences() {
    return commandSequences;
  }

  /**
   * Returns the event indexes.
   *
   * @return The event indexes.
   */
  public long[] eventIndexes() {
    return eventIndexes;
  }

  /**
   * Returns the connection IDs.
   *
   * @return The connection IDs.
   */
  public long[] connections() {
    return connections;
  }

  @Override
  public String toString() {
    return String.format("%s[sessionIds=%s, commandSequences=%s, eventIndexes=%s, connections=%s, timestamp=%d]", getClass().getSimpleName(), Arrays.toString(sessionIds()), Arrays.toString(commandSequences()), Arrays.toString(eventIndexes()), Arrays.toString(connections()), timestamp());
  }

  /**
   * Keep-alive entry serializer.
   */
  public static class Serializer implements TimestampedEntry.Serializer<KeepAliveEntry> {
    @Override
    public void writeObject(BufferOutput output, KeepAliveEntry entry) {
      output.writeLong(entry.timestamp);

      output.writeInt(entry.sessionIds.length);
      for (long sessionId : entry.sessionIds) {
        output.writeLong(sessionId);
      }

      output.writeInt(entry.commandSequences.length);
      for (long commandSequence : entry.commandSequences) {
        output.writeLong(commandSequence);
      }

      output.writeInt(entry.eventIndexes.length);
      for (long eventIndex : entry.eventIndexes) {
        output.writeLong(eventIndex);
      }

      output.writeInt(entry.connections.length);
      for (long connection : entry.connections) {
        output.writeLong(connection);
      }
    }

    @Override
    public KeepAliveEntry readObject(BufferInput input, Class<KeepAliveEntry> type) {
      long timestamp = input.readLong();

      int sessionsLength = input.readInt();
      long[] sessionIds = new long[sessionsLength];
      for (int i = 0; i < sessionsLength; i++) {
        sessionIds[i] = input.readLong();
      }

      int commandSequencesLength = input.readInt();
      long[] commandSequences = new long[commandSequencesLength];
      for (int i = 0; i < commandSequencesLength; i++) {
        commandSequences[i] = input.readLong();
      }

      int eventIndexesLength = input.readInt();
      long[] eventIndexes = new long[eventIndexesLength];
      for (int i = 0; i < eventIndexesLength; i++) {
        eventIndexes[i] = input.readLong();
      }

      int connectionsLength = input.readInt();
      long[] connections = new long[connectionsLength];
      for (int i = 0; i < connectionsLength; i++) {
        connections[i] = input.readLong();
      }
      return new KeepAliveEntry(timestamp, sessionIds, commandSequences, eventIndexes, connections);
    }
  }
}
