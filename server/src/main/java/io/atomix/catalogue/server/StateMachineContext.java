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

package io.atomix.catalogue.server;

import io.atomix.catalogue.server.session.Sessions;

import java.time.Clock;
import java.time.Instant;

/**
 * State machine context.
 * <p>
 * The context is reflective of the current position and state of the Raft state machine. In particular,
 * it exposes the current approximate {@link StateMachineContext#now() time} and all open
 * {@link io.atomix.catalogue.server.session.Sessions}.
 *
 * @author <a href="http://github.com/kuujo>Jordan Halterman</a>
 */
public interface StateMachineContext {

  /**
   * Returns the current state machine version.
   * <p>
   * The state version is indicative of the index of the current {@link io.atomix.catalogue.client.Command}
   * being applied to the server state machine. If a {@link io.atomix.catalogue.client.Query} is being applied,
   * the index of the last command applied will be used.
   *
   * @return The current state machine version.
   */
  long version();

  /**
   * Returns the state machine clock.
   *
   * @return The state machine clock.
   */
  Clock clock();

  /**
   * Returns the current state machine time.
   *
   * @return The current state machine time.
   */
  Instant now();

  /**
   * Returns the state machine sessions.
   *
   * @return The state machine sessions.
   */
  Sessions sessions();

}
