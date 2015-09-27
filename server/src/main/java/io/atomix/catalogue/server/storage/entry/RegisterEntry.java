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
package io.atomix.catalogue.server.storage.entry;

import io.atomix.catalyst.buffer.BufferInput;
import io.atomix.catalyst.buffer.BufferOutput;
import io.atomix.catalyst.serializer.SerializeWith;
import io.atomix.catalyst.serializer.Serializer;
import io.atomix.catalyst.util.Assert;
import io.atomix.catalyst.util.ReferenceManager;

import java.util.UUID;

/**
 * Register client entry.
 *
 * @author <a href="http://github.com/kuujo">Jordan Halterman</a>
 */
@SerializeWith(id=303)
public class RegisterEntry extends TimestampedEntry<RegisterEntry> {
  private UUID connection;
  private long timeout;

  public RegisterEntry() {
  }

  public RegisterEntry(ReferenceManager<Entry<?>> referenceManager) {
    super(referenceManager);
  }

  /**
   * Returns the entry connection ID.
   *
   * @return The entry connection ID.
   */
  public UUID getConnection() {
    return connection;
  }

  /**
   * Sets the entry connection ID.
   *
   * @param connection The entry connection ID.
   * @return The register entry.
   * @throws NullPointerException if {@code connection} is null
   */
  public RegisterEntry setConnection(UUID connection) {
    this.connection = Assert.notNull(connection, "connection");
    return this;
  }

  /**
   * Returns the session timeout.
   *
   * @return The session timeout.
   */
  public long getTimeout() {
    return timeout;
  }

  /**
   * Sets the session timeout.
   *
   * @param timeout The session timeout.
   * @return The register entry.
   */
  public RegisterEntry setTimeout(long timeout) {
    this.timeout = Assert.argNot(timeout, timeout <= 0, "timeout must be positive");
    return this;
  }

  @Override
  public void writeObject(BufferOutput buffer, Serializer serializer) {
    super.writeObject(buffer, serializer);
    buffer.writeLong(timeout);
    serializer.writeObject(connection, buffer);
  }

  @Override
  public void readObject(BufferInput buffer, Serializer serializer) {
    super.readObject(buffer, serializer);
    timeout = buffer.readLong();
    connection = serializer.readObject(buffer);
  }

  @Override
  public int hashCode() {
    return connection.hashCode();
  }

  @Override
  public boolean equals(Object object) {
    return object instanceof RegisterEntry && ((RegisterEntry) object).connection.equals(connection);
  }

  @Override
  public String toString() {
    return String.format("%s[index=%d, term=%d, connection=%s, timeout=%d]", getClass().getSimpleName(), getIndex(), getTerm(), getConnection(), getTimestamp());
  }

}
