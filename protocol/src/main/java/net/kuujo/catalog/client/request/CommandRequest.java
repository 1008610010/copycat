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
package net.kuujo.catalog.client.request;

import net.kuujo.catalog.client.Command;
import net.kuujo.catalyst.buffer.BufferInput;
import net.kuujo.catalyst.buffer.BufferOutput;
import net.kuujo.catalyst.serializer.SerializeWith;
import net.kuujo.catalyst.serializer.Serializer;
import net.kuujo.catalyst.util.Assert;
import net.kuujo.catalyst.util.BuilderPool;
import net.kuujo.catalyst.util.ReferenceManager;

import java.util.Objects;

/**
 * Protocol command request.
 *
 * @author <a href="http://github.com/kuujo">Jordan Halterman</a>
 */
@SerializeWith(id=258)
public class CommandRequest extends SessionRequest<CommandRequest> {

  /**
   * The unique identifier for the command request type.
   */
  public static final byte TYPE = 0x01;

  private static final BuilderPool<Builder, CommandRequest> POOL = new BuilderPool<>(Builder::new);

  /**
   * Returns a new submit request builder.
   *
   * @return A new submit request builder.
   */
  public static Builder builder() {
    return POOL.acquire();
  }

  /**
   * Returns a submit request builder for an existing request.
   *
   * @param request The request to build.
   * @return The submit request builder.
   * @throws NullPointerException if {@code request} is null
   */
  public static Builder builder(CommandRequest request) {
    return POOL.acquire(Assert.notNull(request, "request"));
  }

  private long sequence;
  private Command command;

  /**
   * @throws NullPointerException if {@code referenceManager} is null
   */
  public CommandRequest(ReferenceManager<CommandRequest> referenceManager) {
    super(referenceManager);
  }

  @Override
  public byte type() {
    return TYPE;
  }

  /**
   * Returns the request sequence number.
   *
   * @return The request sequence number.
   */
  public long sequence() {
    return sequence;
  }

  /**
   * Returns the command.
   *
   * @return The command.
   */
  public Command command() {
    return command;
  }

  @Override
  public void readObject(BufferInput buffer, Serializer serializer) {
    super.readObject(buffer, serializer);
    sequence = buffer.readLong();
    command = serializer.readObject(buffer);
  }

  @Override
  public void writeObject(BufferOutput buffer, Serializer serializer) {
    super.writeObject(buffer, serializer);
    buffer.writeLong(sequence);
    serializer.writeObject(command, buffer);
  }

  @Override
  public int hashCode() {
    return Objects.hash(getClass(), session, sequence, command);
  }

  @Override
  public boolean equals(Object object) {
    if (object instanceof CommandRequest) {
      CommandRequest request = (CommandRequest) object;
      return request.session == session
        && request.sequence == sequence
        && request.command.equals(command);
    }
    return false;
  }

  @Override
  public String toString() {
    return String.format("%s[session=%d, sequence=%d, command=%s]", getClass().getSimpleName(), session, sequence, command);
  }

  /**
   * Write request builder.
   */
  public static class Builder extends SessionRequest.Builder<Builder, CommandRequest> {
    
    /**
     * @throws NullPointerException if {@code pool} is null
     */
    protected Builder(BuilderPool<Builder, CommandRequest> pool) {
      super(pool, CommandRequest::new);
    }

    @Override
    protected void reset() {
      super.reset();
      request.sequence = 0;
      request.command = null;
    }

    /**
     * Sets the command sequence number.
     *
     * @param sequence The command sequence number.
     * @return The request builder.
     * @throws IllegalArgumentException if {@code sequence} is less than 1
     */
    public Builder withSequence(long sequence) {
      request.sequence = Assert.argNot(sequence, sequence < 1, "sequence cannot be less than 1");
      return this;
    }

    /**
     * Sets the request command.
     *
     * @param command The request command.
     * @return The request builder.
     * @throws NullPointerException if {@code command} is null
     */
    public Builder withCommand(Command command) {
      request.command = Assert.notNull(command, "command");
      return this;
    }

    /**
     * @throws IllegalStateException if session or sequence are less than 1, or command is null
     */
    @Override
    public CommandRequest build() {
      super.build();
      Assert.stateNot(request.session < 1, "session cannot be less than 1");
      Assert.stateNot(request.sequence < 1, "sequence cannot be less than 1");
      Assert.stateNot(request.command == null, "command cannot be null");
      return request;
    }

    @Override
    public int hashCode() {
      return Objects.hash(request);
    }

    @Override
    public boolean equals(Object object) {
      return object instanceof Builder && ((Builder) object).request.equals(request);
    }

    @Override
    public String toString() {
      return String.format("%s[request=%s]", getClass().getCanonicalName(), request);
    }

  }

}
