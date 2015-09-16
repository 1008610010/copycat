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
package net.kuujo.catalog.server.response;

import net.kuujo.catalog.client.error.RaftError;
import net.kuujo.catalog.client.response.AbstractResponse;
import net.kuujo.catalog.client.response.Response;
import net.kuujo.catalyst.buffer.BufferInput;
import net.kuujo.catalyst.buffer.BufferOutput;
import net.kuujo.catalyst.serializer.SerializeWith;
import net.kuujo.catalyst.serializer.Serializer;
import net.kuujo.catalyst.util.Assert;
import net.kuujo.catalyst.util.BuilderPool;
import net.kuujo.catalyst.util.ReferenceManager;

import java.util.Objects;

/**
 * Protocol append response.
 *
 * @author <a href="http://github.com/kuujo">Jordan Halterman</a>
 */
@SerializeWith(id=257)
public class AppendResponse extends AbstractResponse<AppendResponse> {

  /**
   * The unique identifier for the append response type.
   */
  public static final byte TYPE = 0x14;

  private static final BuilderPool<Builder, AppendResponse> POOL = new BuilderPool<>(Builder::new);

  /**
   * Returns a new append response builder.
   *
   * @return A new append response builder.
   */
  public static Builder builder() {
    return POOL.acquire();
  }

  /**
   * Returns an append response builder for an existing response.
   *
   * @param response The response to build.
   * @return The append response builder.
   */
  public static Builder builder(AppendResponse response) {
    return POOL.acquire(response);
  }

  private long term;
  private boolean succeeded;
  private long logIndex;

  /**
   * @throws NullPointerException if {@code referenceManager} is null
   */
  public AppendResponse(ReferenceManager<AppendResponse> referenceManager) {
    super(referenceManager);
  }

  @Override
  public byte type() {
    return TYPE;
  }

  /**
   * Returns the requesting node's current term.
   *
   * @return The requesting node's current term.
   */
  public long term() {
    return term;
  }

  /**
   * Returns a boolean indicating whether the append was successful.
   *
   * @return Indicates whether the append was successful.
   */
  public boolean succeeded() {
    return succeeded;
  }

  /**
   * Returns the last index of the replica's log.
   *
   * @return The last index of the responding replica's log.
   */
  public long logIndex() {
    return logIndex;
  }

  @Override
  public void readObject(BufferInput buffer, Serializer serializer) {
    status = Status.forId(buffer.readByte());
    if (status == Response.Status.OK) {
      error = null;
      term = buffer.readLong();
      succeeded = buffer.readBoolean();
      logIndex = buffer.readLong();
    } else {
      error = RaftError.forId(buffer.readByte());
    }
  }

  @Override
  public void writeObject(BufferOutput buffer, Serializer serializer) {
    buffer.writeByte(status.id());
    if (status == Response.Status.OK) {
      buffer.writeLong(term)
        .writeBoolean(succeeded)
        .writeLong(logIndex);
    } else {
      buffer.writeByte(error.id());
    }
  }

  @Override
  public int hashCode() {
    return Objects.hash(getClass(), status, term, succeeded, logIndex);
  }

  @Override
  public boolean equals(Object object) {
    if (object instanceof AppendResponse) {
      AppendResponse response = (AppendResponse) object;
      return response.status == status
        && response.term == term
        && response.succeeded == succeeded
        && response.logIndex == logIndex;
    }
    return false;
  }

  @Override
  public String toString() {
    return String.format("%s[status=%s, term=%d, succeeded=%b, logIndex=%d]", getClass().getSimpleName(), status, term, succeeded, logIndex);
  }

  /**
   * Append response builder.
   */
  public static class Builder extends AbstractResponse.Builder<Builder, AppendResponse> {

    protected Builder(BuilderPool<Builder, AppendResponse> pool) {
      super(pool, AppendResponse::new);
    }

    /**
     * Sets the response term.
     *
     * @param term The response term.
     * @return The append response builder
     * @throws IllegalArgumentException if {@code term} is not positive
     */
    public Builder withTerm(long term) {
      response.term = Assert.argNot(term, term <= 0, "term must be positive");
      return this;
    }

    /**
     * Sets whether the request succeeded.
     *
     * @param succeeded Whether the append request succeeded.
     * @return The append response builder.
     */
    public Builder withSucceeded(boolean succeeded) {
      response.succeeded = succeeded;
      return this;
    }

    /**
     * Sets the last index of the replica's log.
     *
     * @param index The last index of the replica's log.
     * @return The append response builder.
     * @throws IllegalArgumentException if {@code index} is negative
     */
    public Builder withLogIndex(long index) {
      response.logIndex = Assert.argNot(index, index < 0, "term must not be negative");
      return this;
    }

    /**
     * @throws IllegalStateException if status is ok and term is not positive or log index is negative
     */
    @Override
    public AppendResponse build() {
      super.build();
      if (response.status == Response.Status.OK) {
        Assert.stateNot(response.term <= 0, "term must be positive");
        Assert.stateNot(response.logIndex < 0, "log index must be positive");
      }
      return response;
    }

    @Override
    public int hashCode() {
      return Objects.hash(response);
    }

    @Override
    public boolean equals(Object object) {
      return object instanceof Builder && ((Builder) object).response.equals(response);
    }

    @Override
    public String toString() {
      return String.format("%s[response=%s]", getClass().getCanonicalName(), response);
    }

  }

}
