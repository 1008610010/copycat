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
import net.kuujo.catalyst.buffer.BufferInput;
import net.kuujo.catalyst.buffer.BufferOutput;
import net.kuujo.catalyst.serializer.SerializeWith;
import net.kuujo.catalyst.serializer.Serializer;
import net.kuujo.catalyst.transport.Address;
import net.kuujo.catalyst.util.Assert;
import net.kuujo.catalyst.util.BuilderPool;
import net.kuujo.catalyst.util.ReferenceManager;

import java.util.Collection;
import java.util.Objects;

/**
 * Protocol join response.
 *
 * @author <a href="http://github.com/kuujo">Jordan Halterman</a>
 */
@SerializeWith(id=261)
public class JoinResponse extends AbstractResponse<JoinResponse> {

  /**
   * The unique identifier for the join response type.
   */
  public static final byte TYPE = 0x0C;

  private static final BuilderPool<Builder, JoinResponse> POOL = new BuilderPool<>(Builder::new);

  /**
   * Returns a new join response builder.
   *
   * @return A new join response builder.
   */
  public static Builder builder() {
    return POOL.acquire();
  }

  /**
   * Returns an join response builder for an existing response.
   *
   * @param response The response to build.
   * @return The join response builder.
   */
  public static Builder builder(JoinResponse response) {
    return POOL.acquire(response);
  }

  private long version;
  private Collection<Address> activeMembers;
  private Collection<Address> passiveMembers;

  /**
   * @throws NullPointerException if {@code referenceManager} is null
   */
  public JoinResponse(ReferenceManager<JoinResponse> referenceManager) {
    super(referenceManager);
  }

  @Override
  public byte type() {
    return TYPE;
  }

  /**
   * Returns the response version.
   *
   * @return The response version.
   */
  public long version() {
    return version;
  }

  /**
   * Returns the join members list.
   *
   * @return The join members list.
   */
  public Collection<Address> activeMembers() {
    return activeMembers;
  }

  /**
   * Returns the join members list.
   *
   * @return The join members list.
   */
  public Collection<Address> passiveMembers() {
    return passiveMembers;
  }

  @Override
  public void readObject(BufferInput buffer, Serializer serializer) {
    status = Status.forId(buffer.readByte());
    if (status == Status.OK) {
      error = null;
      version = buffer.readLong();
      activeMembers = serializer.readObject(buffer);
      passiveMembers = serializer.readObject(buffer);
    } else {
      error = RaftError.forId(buffer.readByte());
    }
  }

  @Override
  public void writeObject(BufferOutput buffer, Serializer serializer) {
    buffer.writeByte(status.id());
    if (status == Status.OK) {
      buffer.writeLong(version);
      serializer.writeObject(activeMembers, buffer);
      serializer.writeObject(passiveMembers, buffer);
    } else {
      buffer.writeByte(error.id());
    }
  }

  @Override
  public int hashCode() {
    return Objects.hash(getClass(), status, version, activeMembers, passiveMembers);
  }

  @Override
  public boolean equals(Object object) {
    if (object instanceof JoinResponse) {
      JoinResponse response = (JoinResponse) object;
      return response.status == status
        && response.version == version
        && response.activeMembers.equals(activeMembers)
        && response.passiveMembers.equals(passiveMembers);
    }
    return false;
  }

  @Override
  public String toString() {
    return String.format("%s[status=%s, version=%d, activeMembers=%b, passiveMembers=%s]", getClass().getSimpleName(), status, version, activeMembers, passiveMembers);
  }

  /**
   * Join response builder.
   */
  public static class Builder extends AbstractResponse.Builder<Builder, JoinResponse> {

    protected Builder(BuilderPool<Builder, JoinResponse> pool) {
      super(pool, JoinResponse::new);
    }

    @Override
    protected void reset() {
      super.reset();
      response.version = 0;
      response.activeMembers = null;
      response.passiveMembers = null;
    }

    /**
     * Sets the response version.
     *
     * @param version The response version.
     * @return The response builder.
     * @throws IllegalArgumentException if {@code version} is negative
     */
    public Builder withVersion(long version) {
      response.version = Assert.argNot(version, version < 0, "version cannot be negative");
      return this;
    }

    /**
     * Sets the response members.
     *
     * @param members The response members.
     * @return The response builder.
     * @throws NullPointerException if {@code members} is null
     */
    public Builder withActiveMembers(Collection<Address> members) {
      response.activeMembers = Assert.notNull(members, "members");
      return this;
    }

    /**
     * Sets the response members.
     *
     * @param members The response members.
     * @return The response builder.
     * @throws NullPointerException if {@code members} is null
     */
    public Builder withPassiveMembers(Collection<Address> members) {
      response.passiveMembers = Assert.notNull(members, "members");
      return this;
    }

    @Override
    public int hashCode() {
      return Objects.hash(response);
    }

    @Override
    public boolean equals(Object object) {
      return object instanceof Builder && ((Builder) object).response.equals(response);
    }

    /**
     * @throws IllegalStateException if active members or passive members are null
     */
    @Override
    public JoinResponse build() {
      super.build();
      if (response.status == Status.OK) {
        Assert.state(response.activeMembers != null, "activeMembers members cannot be null");
        Assert.state(response.passiveMembers != null, "passiveMembers members cannot be null");
      }
      return response;
    }

    @Override
    public String toString() {
      return String.format("%s[response=%s]", getClass().getCanonicalName(), response);
    }

  }

}
