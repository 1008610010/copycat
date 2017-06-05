/*
 * Copyright 2017-present Open Networking Laboratory
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
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

/**
 * Close session entry.
 */
public class CloseSessionEntry extends SessionEntry<CloseSessionEntry> {

  public CloseSessionEntry(long timestamp, long session) {
    super(timestamp, session);
  }

  @Override
  public Type<CloseSessionEntry> type() {
    return Type.CLOSE_SESSION;
  }

  @Override
  public String toString() {
    return String.format("%s[session=%d, timestamp=%d]", getClass().getSimpleName(), session, timestamp);
  }

  /**
   * Close session entry serializer.
   */
  public static class Serializer implements SessionEntry.Serializer<CloseSessionEntry> {
    @Override
    public void writeObject(BufferOutput output, CloseSessionEntry entry) {
      output.writeLong(entry.timestamp);
      output.writeLong(entry.session);
    }

    @Override
    public CloseSessionEntry readObject(BufferInput input, Class<CloseSessionEntry> type) {
      return new CloseSessionEntry(input.readLong(), input.readLong());
    }
  }
}
