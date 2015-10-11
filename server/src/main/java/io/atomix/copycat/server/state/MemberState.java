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
package io.atomix.copycat.server.state;

import io.atomix.catalyst.transport.Address;
import io.atomix.catalyst.util.Assert;

/**
 * Cluster member state.
 *
 * @author <a href="http://github.com/kuujo">Jordan Halterman</a>
 */
class MemberState {
  private final Address address;
  private int index;
  private long matchIndex;
  private long nextIndex;
  private long attemptTime;
  private int attemptCount;
  private long commitTime;

  public MemberState(Address address) {
    this.address = Assert.notNull(address, "address");
  }

  /**
   * Returns the member address.
   *
   * @return The member address.
   */
  public Address getAddress() {
    return address;
  }

  /**
   * Returns the member index.
   *
   * @return The member index.
   */
  public int getIndex() {
    return index;
  }

  /**
   * Sets the member index.
   *
   * @param index The member index.
   * @return The member state.
   */
  MemberState setIndex(int index) {
    this.index = index;
    return this;
  }

  /**
   * Returns the member's match index.
   *
   * @return The member's match index.
   */
  long getMatchIndex() {
    return matchIndex;
  }

  /**
   * Sets the member's match index.
   *
   * @param matchIndex The member's match index.
   * @return The member state.
   */
  MemberState setMatchIndex(long matchIndex) {
    this.matchIndex = matchIndex;
    return this;
  }

  /**
   * Returns the member's next index.
   *
   * @return The member's next index.
   */
  long getNextIndex() {
    return nextIndex;
  }

  /**
   * Sets the member's next index.
   *
   * @param nextIndex The member's next index.
   * @return The member state.
   */
  MemberState setNextIndex(long nextIndex) {
    this.nextIndex = nextIndex;
    return this;
  }

  /**
   * Returns the member commit attempt time.
   *
   * @return The member commit attempt time.
   */
  long getAttemptTime() {
    return attemptTime;
  }

  /**
   * Sets the member commit attempt time.
   *
   * @param attemptTime The member commit attempt time.
   * @return The member state.
   */
  MemberState setAttemptTime(long attemptTime) {
    this.attemptTime = attemptTime;
    return this;
  }

  /**
   * Returns the commit attempt count.
   *
   * @return The commit attempt count.
   */
  int getAttemptCount() {
    return attemptCount;
  }

  /**
   * Resets the commit attempt count.
   *
   * @return The member state.
   */
  MemberState resetAttemptCount() {
    this.attemptCount = 0;
    this.attemptTime = 0;
    return this;
  }

  /**
   * Increments the commit attempt count.
   *
   * @return The member state.
   */
  MemberState incrementAttemptCount() {
    attemptCount++;
    return this;
  }

  /**
   * Returns the member commit time.
   *
   * @return The member commit time.
   */
  long getCommitTime() {
    return commitTime;
  }

  /**
   * Sets the member commit time.
   *
   * @param commitTime The member commit time.
   * @return The member state.
   */
  MemberState setCommitTime(long commitTime) {
    this.commitTime = commitTime;
    return this;
  }

  @Override
  public String toString() {
    return address.toString();
  }

}
