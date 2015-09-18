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
package io.atomix.catalog.server.response;

import io.atomix.catalog.client.error.RaftError;
import io.atomix.catalog.client.response.AbstractResponse;
import io.atomix.catalog.client.response.Response;
import io.atomix.catalyst.buffer.BufferInput;
import io.atomix.catalyst.buffer.BufferOutput;
import io.atomix.catalyst.serializer.SerializeWith;
import io.atomix.catalyst.serializer.Serializer;
import io.atomix.catalyst.util.Assert;
import io.atomix.catalyst.util.BuilderPool;
import io.atomix.catalyst.util.ReferenceManager;

import java.util.Objects;

/**
 * Protocol poll response.
 *
 * @author <a href="http://github.com/kuujo">Jordan Halterman</a>
 */
@SerializeWith(id=267)
public class PollResponse extends AbstractResponse<PollResponse> {

  /**
   * The unique identifier for the poll response type.
   */
  public static final byte TYPE = 0x10;

  private static final BuilderPool<Builder, PollResponse> POOL = new BuilderPool<>(Builder::new);

  /**
   * Returns a new poll response builder.
   *
   * @return A new poll response builder.
   */
  public static Builder builder() {
    return POOL.acquire();
  }

  /**
   * Returns a poll response builder for an existing response.
   *
   * @param response The response to build.
   * @return The poll response builder.
   */
  public static Builder builder(PollResponse response) {
    return POOL.acquire(response);
  }

  private long term;
  private boolean accepted;

  /**
   * @throws NullPointerException if {@code referenceManager} is null
   */
  public PollResponse(ReferenceManager<PollResponse> referenceManager) {
    super(referenceManager);
  }

  @Override
  public byte type() {
    return TYPE;
  }

  /**
   * Returns the responding node's current term.
   *
   * @return The responding node's current term.
   */
  public long term() {
    return term;
  }

  /**
   * Returns a boolean indicating whether the poll was accepted.
   *
   * @return Indicates whether the poll was accepted.
   */
  public boolean accepted() {
    return accepted;
  }

  @Override
  public void readObject(BufferInput buffer, Serializer serializer) {
    status = Response.Status.forId(buffer.readByte());
    if (status == Response.Status.OK) {
      error = null;
      term = buffer.readLong();
      accepted = buffer.readBoolean();
    } else {
      error = RaftError.forId(buffer.readByte());
    }
  }

  @Override
  public void writeObject(BufferOutput buffer, Serializer serializer) {
    buffer.writeByte(status.id());
    if (status == Response.Status.OK) {
      buffer.writeLong(term).writeBoolean(accepted);
    } else {
      buffer.writeByte(error.id());
    }
  }

  @Override
  public int hashCode() {
    return Objects.hash(getClass(), status, term, accepted);
  }

  @Override
  public boolean equals(Object object) {
    if (object instanceof PollResponse) {
      PollResponse response = (PollResponse) object;
      return response.status == status
        && response.term == term
        && response.accepted == accepted;
    }
    return false;
  }

  @Override
  public String toString() {
    return String.format("%s[status=%s, term=%d, accepted=%b]", getClass().getSimpleName(), status, term, accepted);
  }

  /**
   * Poll response builder.
   */
  public static class Builder extends AbstractResponse.Builder<Builder, PollResponse> {

    protected Builder(BuilderPool<Builder, PollResponse> pool) {
      super(pool, PollResponse::new);
    }

    @Override
    protected void reset() {
      super.reset();
      response.term = 0;
      response.accepted = false;
    }

    /**
     * Sets the response term.
     *
     * @param term The response term.
     * @return The poll response builder.
     * @throws IllegalArgumentException if {@code term} is not positive
     */
    public Builder withTerm(long term) {
      response.term = Assert.argNot(term, term < 0, "term must be positive");
      return this;
    }

    /**
     * Sets whether the poll was granted.
     *
     * @param accepted Whether the poll was granted.
     * @return The poll response builder.
     */
    public Builder withAccepted(boolean accepted) {
      response.accepted = accepted;
      return this;
    }

    /**
     * @throws IllegalStateException if status is OK and {@code term} is not positive
     */
    @Override
    public PollResponse build() {
      super.build();
      if (response.status == Response.Status.OK) {
        Assert.stateNot(response.term < 0, "term must be positive");
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
