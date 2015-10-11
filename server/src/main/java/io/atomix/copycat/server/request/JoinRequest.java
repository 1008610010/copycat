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
package io.atomix.copycat.server.request;

import io.atomix.catalyst.buffer.BufferInput;
import io.atomix.catalyst.buffer.BufferOutput;
import io.atomix.catalyst.serializer.SerializeWith;
import io.atomix.catalyst.serializer.Serializer;
import io.atomix.catalyst.transport.Address;
import io.atomix.catalyst.util.Assert;
import io.atomix.copycat.client.request.AbstractRequest;

import java.util.Objects;

/**
 * Protocol join request.
 *
 * @author <a href="http://github.com/kuujo">Jordan Halterman</a>
 */
@SerializeWith(id=268)
public class JoinRequest extends AbstractRequest<JoinRequest> {

  /**
   * Returns a new join request builder.
   *
   * @return A new join request builder.
   */
  public static Builder builder() {
    return new Builder(new JoinRequest());
  }

  /**
   * Returns an join request builder for an existing request.
   *
   * @param request The request to build.
   * @return The join request builder.
   */
  public static Builder builder(JoinRequest request) {
    return new Builder(request);
  }

  private Address member;

  /**
   * Returns the joining member.
   *
   * @return The joining member.
   */
  public Address member() {
    return member;
  }

  @Override
  public void writeObject(BufferOutput<?> buffer, Serializer serializer) {
    serializer.writeObject(member, buffer);
  }

  @Override
  public void readObject(BufferInput<?> buffer, Serializer serializer) {
    member = serializer.readObject(buffer);
  }

  @Override
  public int hashCode() {
    return Objects.hash(getClass(), member);
  }

  @Override
  public boolean equals(Object object) {
    if (object instanceof JoinRequest) {
      JoinRequest request = (JoinRequest) object;
      return request.member.equals(member);
    }
    return false;
  }

  @Override
  public String toString() {
    return String.format("%s[member=%s]", getClass().getSimpleName(), member);
  }

  /**
   * Join request builder.
   */
  public static class Builder extends AbstractRequest.Builder<Builder, JoinRequest> {
    protected Builder(JoinRequest request) {
      super(request);
    }

    /**
     * Sets the request member.
     *
     * @param member The request member.
     * @return The request builder.
     * @throws NullPointerException if {@code member} is null
     */
    public Builder withMember(Address member) {
      request.member = Assert.notNull(member, "member");
      return this;
    }

    /**
     * @throws IllegalStateException if member is null
     */
    @Override
    public JoinRequest build() {
      super.build();
      Assert.state(request.member != null, "member cannot be null");
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
