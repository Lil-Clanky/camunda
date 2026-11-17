/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Camunda License 1.0. You may not use this file
 * except in compliance with the Camunda License 1.0.
 */
package io.atomix.raft.roles;

import static org.assertj.core.api.Assertions.assertThat;

import io.atomix.cluster.MemberId;
import io.atomix.raft.FaultyFlusherConfigurator;
import io.atomix.raft.RaftRule;
import io.atomix.raft.RaftServer.Role;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.awaitility.Awaitility;
import org.junit.Rule;
import org.junit.Test;

public class RaftFollowerFlushErrorTest {

  private static final int MEMBERS = 3;
  private static final int FAULTY_MEMBERS = (MEMBERS - 1) / 2;
  public AtomicBoolean isFaulty = new AtomicBoolean(false);
  public AtomicInteger flushFailedCount = new AtomicInteger(0);

  @Rule
  public RaftRule raftRule =
      RaftRule.withBootstrappedNodes(
          MEMBERS,
          new FaultyFlusherConfigurator(
              FAULTY_MEMBERS, isFaulty::get, flushFailedCount::incrementAndGet, false, false));

  @Test
  public void shouldFailFollowerWhenItFailsToFlush() throws Throwable {
    // given
    final var leader = raftRule.getLeader().orElseThrow();
    // the priority election config keeps the faulty member "1" from becoming leader
    assertThat(Integer.parseInt(leader.name())).isGreaterThan(FAULTY_MEMBERS);
    final var faultyFollower = raftRule.getServer("1");
    raftRule.awaitSameLogSizeOnAllNodes(raftRule.appendEntry());

    // when - a single flush fails; like a failed fsync, the error is reported only once
    isFaulty.set(true);
    final var commitListener = raftRule.appendEntryAsync();
    Awaitility.await("faulty follower fails to flush").until(() -> flushFailedCount.get() > 0);
    isFaulty.set(false);

    // then - the faulty follower fails instead of retrying the flush
    Awaitility.await("faulty follower fails")
        .untilAsserted(
            () -> {
              assertThat(faultyFollower.getRole()).isEqualTo(Role.INACTIVE);
              assertThat(faultyFollower.getContext().getHealthReport().isDead()).isTrue();
            });
    final var issue = faultyFollower.getContext().getHealthReport().getIssue();
    assertThat(issue).isNotNull();
    assertThat(issue.throwable())
        .hasMessageContaining("Failed to flush the journal before acknowledging entries");

    // then - the remaining members still commit, but the faulty follower never acknowledges again
    final var committedIndex = commitListener.awaitCommit(Duration.ofSeconds(5));
    final var nextIndex = raftRule.appendEntry();
    assertThat(nextIndex).isGreaterThan(committedIndex);
    assertThat(faultyFollower.getRole()).isEqualTo(Role.INACTIVE);
    assertThat(
            leader
                .getContext()
                .getCluster()
                .getMemberContext(MemberId.from(faultyFollower.name()))
                .getMatchIndex())
        .isLessThan(committedIndex);
  }
}
