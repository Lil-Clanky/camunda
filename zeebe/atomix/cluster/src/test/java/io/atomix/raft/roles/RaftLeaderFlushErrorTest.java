/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Camunda License 1.0. You may not use this file
 * except in compliance with the Camunda License 1.0.
 */
package io.atomix.raft.roles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.atomix.raft.FaultyFlusherConfigurator;
import io.atomix.raft.RaftRule;
import io.atomix.raft.RaftServer.Role;
import java.time.Duration;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.awaitility.Awaitility;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public class RaftLeaderFlushErrorTest {

  private static final int MEMBERS = 3;

  @Parameter public boolean withDataLoss;

  @Rule
  @Parameter(1)
  public RaftRule raftRule; // =

  @Parameter(2)
  public AtomicBoolean isFaulty;

  @Parameter(3)
  public AtomicInteger flushFailedCount;

  @Parameters(name = "{index}: withDataLoss = {0}")
  public static Collection<Object[]> parameters() {
    return Stream.of(true, false)
        .map(
            withDataLoss -> {
              final var isFaulty = new AtomicBoolean(false);
              final var flushFailedCount = new AtomicInteger(0);
              return new Object[] {
                withDataLoss,
                RaftRule.withBootstrappedNodes(
                    MEMBERS,
                    new FaultyFlusherConfigurator(
                        (MEMBERS - 1) / 2,
                        isFaulty::get,
                        flushFailedCount::incrementAndGet,
                        true,
                        withDataLoss)),
                isFaulty,
                flushFailedCount
              };
            })
        .toList();
  }

  @Test
  public void shouldFailLeaderWhenItFailsToFlush() throws Exception {
    // given
    final var leader = raftRule.getLeader().orElseThrow();
    // the priority election config makes the faulty member "1" the leader
    assertThat(leader.name()).isEqualTo("1");
    raftRule.appendEntry();

    // when - a single flush fails; like a failed fsync, the error is reported only once
    isFaulty.set(true);
    final var commitListener = raftRule.appendEntryAsync();
    Awaitility.await("leader fails to flush").until(() -> flushFailedCount.get() > 0);
    isFaulty.set(false);

    // then - the leader fails instead of stepping down and keeping the entries
    assertThatThrownBy(() -> commitListener.awaitCommit(Duration.ofSeconds(5)))
        .isInstanceOf(Exception.class);
    Awaitility.await("leader fails")
        .untilAsserted(
            () -> {
              assertThat(leader.getRole()).isEqualTo(Role.INACTIVE);
              assertThat(leader.getContext().getHealthReport().isDead()).isTrue();
            });
    final var issue = leader.getContext().getHealthReport().getIssue();
    assertThat(issue).isNotNull();
    assertThat(issue.throwable())
        .hasMessageContaining("Failed to flush the journal before committing index");

    // then - the remaining members elect a new leader and keep committing
    raftRule.awaitNewLeader();
    raftRule.appendEntry();
    assertThat(leader.getRole()).isEqualTo(Role.INACTIVE);
  }
}
