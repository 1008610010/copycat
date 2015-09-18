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
package io.atomix.catalog.server.state;

import io.atomix.catalog.server.RaftServer;
import io.atomix.catalog.server.response.JoinResponse;
import io.atomix.catalog.server.request.JoinRequest;
import io.atomix.catalog.client.response.Response;
import io.atomix.catalyst.util.concurrent.Scheduled;

import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Join state.
 *
 * @author <a href="http://github.com/kuujo">Jordan Halterman</a>
 */
final class JoinState extends InactiveState {
  private Scheduled joinFuture;

  public JoinState(ServerContext context) {
    super(context);
  }

  @Override
  public CompletableFuture<AbstractState> open() {
    return super.open().thenRun(this::startJoinTimeout).thenRun(this::join).thenApply(v -> this);
  }

  @Override
  public RaftServer.State type() {
    return RaftServer.State.JOIN;
  }

  /**
   * Sets a join timeout.
   */
  private void startJoinTimeout() {
    joinFuture = context.getContext().schedule(() -> {
      if (isOpen()) {
        context.getCluster().setActive(true);
        transition(RaftServer.State.FOLLOWER);
      }
    }, context.getElectionTimeout());
  }

  /**
   * Starts joining the cluster.
   */
  private void join() {
    List<MemberState> votingMembers = context.getCluster().getActiveMembers();
    if (votingMembers.isEmpty()) {
      LOGGER.debug("{} - Single member cluster. Transitioning directly to leader.", context.getAddress());
      transition(RaftServer.State.LEADER);
    } else {
      join(context.getCluster().getActiveMembers().iterator());
    }
  }

  /**
   * Recursively attempts to join the cluster.
   */
  private void join(Iterator<MemberState> iterator) {
    if (iterator.hasNext()) {
      MemberState member = iterator.next();
      LOGGER.debug("{} - Attempting to join via {}", context.getAddress(), member.getAddress());

      context.getConnections().getConnection(member.getAddress()).thenCompose(connection -> {
        JoinRequest request = JoinRequest.builder()
          .withMember(context.getAddress())
          .build();
        return connection.<JoinRequest, JoinResponse>send(request);
      }).whenComplete((response, error) -> {
        if (error == null) {
          if (response.status() == Response.Status.OK) {
            LOGGER.info("{} - Successfully joined via {}", context.getAddress(), member.getAddress());

            context.getCluster().configure(response.version(), response.activeMembers(), response.passiveMembers());

            if (context.getCluster().isActive()) {
              transition(RaftServer.State.FOLLOWER);
            } else if (context.getCluster().isPassive()) {
              transition(RaftServer.State.PASSIVE);
            } else {
              throw new IllegalStateException("not a member of the cluster");
            }
          } else {
            LOGGER.debug("{} - Failed to join {}", context.getAddress(), member.getAddress());
            join(iterator);
          }
          response.release();
        } else {
          LOGGER.debug("{} - Failed to join {}", context.getAddress(), member.getAddress());
          join(iterator);
        }
      });
    } else {
      LOGGER.info("{} - Failed to join existing cluster", context.getAddress());
      context.getCluster().setActive(true);
      transition(RaftServer.State.FOLLOWER);
    }
  }

  /**
   * Cancels the join timeout.
   */
  private void cancelJoinTimeout() {
    if (joinFuture != null) {
      LOGGER.info("{} - Cancelling join timeout", context.getAddress());
      joinFuture.cancel();
      joinFuture = null;
    }
  }

  @Override
  public CompletableFuture<Void> close() {
    return super.close().thenRun(this::cancelJoinTimeout);
  }

}
