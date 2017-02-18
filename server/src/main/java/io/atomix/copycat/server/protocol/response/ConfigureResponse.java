/*
 * Copyright 2016 the original author or authors.
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
 * limitations under the License
 */
package io.atomix.copycat.server.protocol.response;

import io.atomix.copycat.protocol.response.AbstractResponse;
import io.atomix.copycat.protocol.response.ProtocolResponse;

import java.util.Objects;

/**
 * Configuration installation response.
 * <p>
 * Configuration installation responses are sent in response to configuration installation requests to
 * indicate the simple success of the installation of a configuration. If the response {@link #status()}
 * is {@link Status#OK} then the installation was successful.
 *
 * @author <a href="http://github.com/kuujo">Jordan Halterman</a>
 */
public class ConfigureResponse extends AbstractResponse implements RaftProtocolResponse {

  /**
   * Returns a new configure response builder.
   *
   * @return A new configure response builder.
   */
  public static Builder builder() {
    return new Builder();
  }

  public ConfigureResponse(Status status, ProtocolResponse.Error error) {
    super(status, error);
  }

  @Override
  public RaftProtocolResponse.Type type() {
    return RaftProtocolResponse.Type.CONFIGURE;
  }

  @Override
  public int hashCode() {
    return Objects.hash(getClass(), status);
  }

  @Override
  public boolean equals(Object object) {
    if (object instanceof ConfigureResponse) {
      ConfigureResponse response = (ConfigureResponse) object;
      return response.status == status;
    }
    return false;
  }

  @Override
  public String toString() {
    return String.format("%s[status=%s]", getClass().getSimpleName(), status);
  }

  /**
   * Heartbeat response builder.
   */
  public static class Builder extends AbstractResponse.Builder<ConfigureResponse.Builder, ConfigureResponse> {
    @Override
    public ConfigureResponse copy(ConfigureResponse response) {
      return new ConfigureResponse(response.status, response.error);
    }

    @Override
    public ConfigureResponse build() {
      return new ConfigureResponse(status, error);
    }
  }
}
