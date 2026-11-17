/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Camunda License 1.0. You may not use this file
 * except in compliance with the Camunda License 1.0.
 */
package io.atomix.raft;

import static org.assertj.core.api.Assertions.assertThat;

import io.atomix.cluster.MemberId;
import io.atomix.raft.RaftServer.Role;
import io.atomix.raft.impl.RaftContext;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * After a failed flush, the kernel marks the pages clean and reports the error only once, so a
 * retried flush succeeds without writing anything. A member which fails to flush must therefore
 * fail, instead of retrying the flush and later treating the entries as persisted.
 */
final class FlushFailureTest {
  private static final MemberId LEADER = MemberId.from("2");
  private static final MemberId FOLLOWER = MemberId.from("1");
  private static final MemberId OTHER_FOLLOWER = MemberId.from("0");
  private static final List<MemberId> MEMBERS = List.of(LEADER, FOLLOWER, OTHER_FOLLOWER);

  @TempDir private Path directory;
  private ControllableRaftContexts raft;

  @BeforeEach
  void setup() throws Exception {
    raft = new ControllableRaftContexts(3);
    raft.setup(directory, new Random(1));
    electLeader();
  }

  @AfterEach
  void shutdown() throws IOException {
    raft.shutdown();
  }

  @Test
  void shouldFailFollowerWhenItFailsToFlushAppendedEntries() {
    // given
    final long lastIndex = context(LEADER).getLog().getLastIndex();
    raft.failNextFlush(FOLLOWER);

    // when
    raft.clientAppendOnLeader();
    replicate();

    // then - the follower fails instead of rejecting the append and flushing again on a resend
    assertFailed(FOLLOWER, "Failed to flush the journal before acknowledging entries");

    // then - it never acknowledges the entry, which is committed with the other follower
    assertThat(context(LEADER).getCommitIndex()).isEqualTo(lastIndex + 1);
    assertThat(matchIndexOnLeader(FOLLOWER)).isEqualTo(lastIndex);
    assertThat(context(FOLLOWER).getCommitIndex()).isLessThanOrEqualTo(lastIndex);
  }

  @Test
  void shouldNotRecoverFollowerAfterFailedFlush() {
    // given
    final long lastIndex = context(LEADER).getLog().getLastIndex();
    raft.failNextFlush(FOLLOWER);
    raft.clientAppendOnLeader();
    replicate();

    // when - more entries are replicated, and the follower's next flush would succeed
    raft.clientAppendOnLeader();
    replicate();

    // then
    assertFailed(FOLLOWER, "Failed to flush the journal before acknowledging entries");
    assertThat(context(LEADER).getCommitIndex()).isEqualTo(lastIndex + 2);
    assertThat(matchIndexOnLeader(FOLLOWER)).isEqualTo(lastIndex);
  }

  @Test
  void shouldFailLeaderWhenItFailsToFlushBeforeCommitting() {
    // given
    final long lastIndex = context(LEADER).getLog().getLastIndex();
    raft.failNextFlush(LEADER);

    // when
    raft.clientAppendOnLeader();
    replicate();

    // then - the leader fails instead of stepping down and keeping the entry
    assertFailed(LEADER, "Failed to flush the journal before committing index " + (lastIndex + 1));
    assertThat(context(LEADER).getCommitIndex()).isEqualTo(lastIndex);
  }

  private void assertFailed(final MemberId member, final String expectedMessage) {
    final var context = context(member);
    assertThat(context.getRole()).isEqualTo(Role.INACTIVE);
    final var health = context.getHealthReport();
    assertThat(health.isDead()).isTrue();
    assertThat(health.getIssue()).isNotNull();
    assertThat(health.getIssue().throwable()).hasMessageContaining(expectedMessage);
  }

  private long matchIndexOnLeader(final MemberId member) {
    return context(LEADER).getCluster().getMemberContext(member).getMatchIndex();
  }

  private void electLeader() {
    raft.tickElectionTimeout(LEADER);
    for (int i = 0; i < 100 && context(LEADER).getRole() != Role.LEADER; i++) {
      raft.tickHeartbeatTimeout();
      raft.processAllMessage();
      raft.runUntilDone();
    }
    assertThat(context(LEADER).getRole()).isEqualTo(Role.LEADER);
    // records the leader, which clientAppendOnLeader appends on
    raft.assertAtMostOneLeader();
    replicate();
  }

  private void replicate() {
    for (int i = 0; i < 10; i++) {
      raft.runUntilDone();
      raft.processAllMessage();
      raft.runUntilDone();
      raft.tickHeartbeatTimeout(LEADER);
    }
  }

  private RaftContext context(final MemberId member) {
    return raft.getRaftContext(member);
  }
}
