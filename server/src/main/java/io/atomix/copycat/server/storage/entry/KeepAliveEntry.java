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
import io.atomix.catalyst.serializer.SerializeWith;
import io.atomix.catalyst.serializer.Serializer;
import io.atomix.catalyst.util.ReferenceManager;

/**
 * Keep alive entry.
 *
 * @author <a href="http://github.com/kuujo">Jordan Halterman</a>
 */
@SerializeWith(id=304)
public class KeepAliveEntry extends SessionEntry<KeepAliveEntry> {
  private long commandSequence;
  private long eventVersion;
  private long eventSequence;

  public KeepAliveEntry() {
  }

  public KeepAliveEntry(ReferenceManager<Entry<?>> referenceManager) {
    super(referenceManager);
  }

  /**
   * Returns the command sequence number.
   *
   * @return The command sequence number.
   */
  public long getCommandSequence() {
    return commandSequence;
  }

  /**
   * Sets the command sequence number.
   *
   * @param commandSequence The command sequence number.
   * @return The keep alive entry.
   */
  public KeepAliveEntry setCommandSequence(long commandSequence) {
    this.commandSequence = commandSequence;
    return this;
  }

  /**
   * Returns the event version number.
   *
   * @return The event version number.
   */
  public long getEventVersion() {
    return eventVersion;
  }

  /**
   * Sets the event version number.
   *
   * @param eventVersion The event version number.
   * @return The keep alive entry.
   */
  public KeepAliveEntry setEventVersion(long eventVersion) {
    this.eventVersion = eventVersion;
    return this;
  }

  /**
   * Returns the event sequence number.
   *
   * @return The event sequence number.
   */
  public long getEventSequence() {
    return eventSequence;
  }

  /**
   * Sets the event sequence number.
   *
   * @param eventSequence The event sequence number.
   * @return The keep alive entry.
   */
  public KeepAliveEntry setEventSequence(long eventSequence) {
    this.eventSequence = eventSequence;
    return this;
  }

  @Override
  public void readObject(BufferInput buffer, Serializer serializer) {
    super.readObject(buffer, serializer);
    commandSequence = buffer.readLong();
    eventVersion = buffer.readLong();
    eventSequence = buffer.readLong();
  }

  @Override
  public void writeObject(BufferOutput buffer, Serializer serializer) {
    super.writeObject(buffer, serializer);
    buffer.writeLong(commandSequence);
    buffer.writeLong(eventVersion);
    buffer.writeLong(eventSequence);
  }

  @Override
  public String toString() {
    return String.format("%s[index=%d, term=%d, session=%d, commandSequence=%d, eventVersion=%d, eventSequence=%d, timestamp=%d]", getClass().getSimpleName(), getIndex(), getTerm(), getSession(), getCommandSequence(), getEventVersion(), getEventSequence(), getTimestamp());
  }

}
