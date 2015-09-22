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
package io.atomix.catalog.client;

import io.atomix.catalyst.util.BuilderPool;

/**
 * Raft state commands modify system state.
 * <p>
 * Commands are submitted by clients to a Raft server and used to modify Raft cluster-wide state. The Raft
 * consensus protocol dictates that commands must be forwarded to the cluster leader and replicated to a majority of
 * followers before being applied to the cluster state. Thus, in contrast to {@link Query queries},
 * commands are not dictated by different consistency levels.
 *
 * @author <a href="http://github.com/kuujo">Jordan Halterman</a>
 */
public interface Command<T> extends Operation<T> {

  /**
   * Returns the memory address of the command.
   *
   * @return The memory address of the command.
   */
  default long address() {
    return 0;
  }

  /**
   * Base builder for commands.
   */
  abstract class Builder<T extends Builder<T, U, V>, U extends Command<V>, V> extends Operation.Builder<T, U, V> {
    protected U command;

    protected Builder(BuilderPool<T, U> pool) {
      super(pool);
    }

    @Override
    protected void reset(U command) {
      super.reset(command);
      this.command = command;
    }

    @Override
    public U build() {
      close();
      return command;
    }
  }

}
