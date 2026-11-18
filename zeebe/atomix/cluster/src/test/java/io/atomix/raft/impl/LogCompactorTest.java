/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Camunda License 1.0. You may not use this file
 * except in compliance with the Camunda License 1.0.
 */
package io.atomix.raft.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatCode;
import static org.assertj.core.api.AssertionsForClassTypes.catchThrowable;

import io.atomix.raft.metrics.RaftServiceMetrics;
import io.atomix.raft.snapshot.InMemorySnapshot;
import io.atomix.raft.snapshot.TestSnapshotStore;
import io.atomix.raft.storage.log.RaftLog;
import io.atomix.utils.concurrent.ThreadContext;
import io.camunda.zeebe.journal.JournalException.SyncFailed;
import io.camunda.zeebe.util.exception.UnrecoverableException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;

final class LogCompactorTest {
  private ThreadContext threadContext;
  private RaftLog raftLog;
  private final List<Throwable> storageFailures = new ArrayList<>();
  private LogCompactor compactor;
  @AutoClose private final MeterRegistry meterRegistry = new SimpleMeterRegistry();

  @BeforeEach
  void beforeEach() {
    threadContext = Mockito.mock(ThreadContext.class);
    raftLog = Mockito.mock(RaftLog.class);
    // immediately run anything to be executed
    Mockito.doAnswer(
            i -> {
              i.getArgument(0, Runnable.class).run();
              return null;
            })
        .when(threadContext)
        .execute(Mockito.any());

    compactor =
        new LogCompactor(
            threadContext,
            raftLog,
            5,
            new RaftServiceMetrics("1", meterRegistry),
            (message, cause) -> {
              storageFailures.add(cause);
              return new UnrecoverableException(message, cause);
            });
  }

  @Test
  void shouldCompact() {
    // given
    compactor.setCompactableIndex(12);

    // when
    compactor.compact();

    // then - should have compacted the log up to the compactable index - the replication threshold
    Mockito.verify(
            raftLog,
            Mockito.times(1)
                .description("should compact up to index minus the replication threshold"))
        .deleteUntil(7);
  }

  @Test
  void shouldFailServerWhenCompactionFailsToSync() {
    // given - e.g. syncing the directory after marking a segment for deletion failed
    final var syncError = new SyncFailed("Failed to sync", new IOException("Input/output error"));
    Mockito.when(raftLog.deleteUntil(Mockito.anyLong())).thenThrow(syncError);
    compactor.setCompactableIndex(12);

    // when
    final var thrown = catchThrowable(compactor::compact);

    // then
    assertThat(thrown).isInstanceOf(UnrecoverableException.class).hasCause(syncError);
    assertThat(storageFailures).containsExactly(syncError);
  }

  @Test
  void shouldCompactIgnoringThreshold() {
    // given
    compactor.setCompactableIndex(12);

    // when
    compactor.compactIgnoringReplicationThreshold();

    // then
    Mockito.verify(raftLog, Mockito.times(1).description("should compact until compactable index"))
        .deleteUntil(12);
  }

  @ParameterizedTest
  @MethodSource("provideCompactors")
  void shouldNotCompactOnDifferentThread(final Consumer<LogCompactor> compactMethod) {
    // given
    Mockito.doThrow(new IllegalStateException("Invalid thread")).when(threadContext).checkThread();
    compactor.setCompactableIndex(12);

    // when
    assertThatCode(() -> compactMethod.accept(compactor)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void shouldCompactBasedOnOldestSnapshot() {
    // given
    final var store = new TestSnapshotStore(new AtomicReference<>());
    InMemorySnapshot.newPersistedSnapshot(0, 10L, 1, 30, store).reserve();
    InMemorySnapshot.newPersistedSnapshot(0, 30L, 1, 30, store);

    // when
    compactor.compactFromSnapshots(store);

    // then
    Mockito.verify(
            raftLog,
            Mockito.times(1)
                .description(
                    "should compact up to lowest snapshot index, minus replication threshold"))
        .deleteUntil(Mockito.eq(5L));
  }

  private static Stream<Named<Consumer<LogCompactor>>> provideCompactors() {
    return Stream.of(
        Named.of("#compact", LogCompactor::compact),
        Named.of(
            "#compactIgnoringReplicationThreshold",
            LogCompactor::compactIgnoringReplicationThreshold));
  }
}
