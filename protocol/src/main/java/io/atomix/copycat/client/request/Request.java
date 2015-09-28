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
package io.atomix.copycat.client.request;

import io.atomix.catalyst.serializer.CatalystSerializable;
import io.atomix.catalyst.util.BuilderPool;
import io.atomix.catalyst.util.ReferenceCounted;

/**
 * Protocol request.
 *
 * @author <a href="http://github.com/kuujo">Jordan Halterman</a>
 */
public interface Request<T extends Request<T>> extends ReferenceCounted<T>, CatalystSerializable {

  /**
   * Request builder.
   *
   * @param <T> The builder type.
   */
  abstract class Builder<T extends Builder<T, U>, U extends Request> extends io.atomix.catalyst.util.Builder<U> {
    /**
     * @throws NullPointerException if {@code pool} is null
     */
    protected Builder(BuilderPool pool) {
      super(pool);
    }
  }

}
