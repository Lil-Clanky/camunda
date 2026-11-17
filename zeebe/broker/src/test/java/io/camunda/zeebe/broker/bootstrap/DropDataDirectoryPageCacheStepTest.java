/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Camunda License 1.0. You may not use this file
 * except in compliance with the Camunda License 1.0.
 */
package io.camunda.zeebe.broker.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.camunda.zeebe.broker.system.configuration.BrokerCfg;
import io.camunda.zeebe.journal.fs.PosixFs;
import io.camunda.zeebe.scheduler.testing.TestConcurrencyControl;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DropDataDirectoryPageCacheStepTest {

  private static final Duration TIME_OUT = Duration.ofSeconds(10);

  @TempDir private Path root;
  private Path dataDirectory;
  private final PosixFs posixFs = mock(PosixFs.class);
  private final DropDataDirectoryPageCacheStep sut = new DropDataDirectoryPageCacheStep(posixFs);
  private BrokerStartupContext context;

  @BeforeEach
  void setUp() {
    dataDirectory = root.resolve("data");
    final var brokerCfg = new BrokerCfg();
    brokerCfg.getData().setDirectory(dataDirectory.toString());

    context = mock(BrokerStartupContext.class);
    when(context.getBrokerConfiguration()).thenReturn(brokerCfg);
    when(context.getConcurrencyControl()).thenReturn(new TestConcurrencyControl());
  }

  @Test
  void shouldDropPageCacheOfAllFilesInDataDirectory() throws Exception {
    // given
    final var segment = dataDirectory.resolve("default/partitions/1/journal-1.log");
    final var snapshotFile = dataDirectory.resolve("default/partitions/1/snapshots/1-1/1.sst");
    final var topology = dataDirectory.resolve(".topology.meta");
    for (final var file : new Path[] {segment, snapshotFile, topology}) {
      Files.createDirectories(file.getParent());
      Files.writeString(file, "content");
    }

    // when
    final var started = sut.startup(context);

    // then
    assertThat(started).succeedsWithin(TIME_OUT);
    verify(posixFs).dropPageCache(segment);
    verify(posixFs).dropPageCache(snapshotFile);
    verify(posixFs).dropPageCache(topology);
  }

  @Test
  void shouldStartWhenPageCacheCannotBeDropped() throws Exception {
    // given
    final var file = dataDirectory.resolve("file");
    Files.createDirectories(dataDirectory);
    Files.writeString(file, "content");
    when(posixFs.dropPageCache(any())).thenReturn(false);

    // when
    final var started = sut.startup(context);

    // then
    assertThat(started).succeedsWithin(TIME_OUT);
  }

  @Test
  void shouldStartWithoutDataDirectory() {
    // when
    final var started = sut.startup(context);

    // then
    assertThat(started).succeedsWithin(TIME_OUT);
    verifyNoInteractions(posixFs);
  }
}
