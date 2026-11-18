/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Camunda License 1.0. You may not use this file
 * except in compliance with the Camunda License 1.0.
 */
package io.camunda.zeebe.broker.bootstrap;

import io.camunda.zeebe.journal.fs.PosixFs;
import io.camunda.zeebe.scheduler.ConcurrencyControl;
import io.camunda.zeebe.scheduler.future.ActorFuture;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Drops the cached pages of the files in the data directory before anything reads them.
 *
 * <p>After a failed sync, the page cache can hold data which never reached the disk, and serve it
 * to anyone reading the file, even after a restart of the broker. Dropping the cached pages makes a
 * restart on the same node read what is on disk, like a restart on another node does.
 *
 * <p>This is best effort, see {@link PosixFs#dropPageCache(Path)}: it never fails the startup.
 */
final class DropDataDirectoryPageCacheStep extends AbstractBrokerStartupStep {
  private static final Logger LOG = LoggerFactory.getLogger(DropDataDirectoryPageCacheStep.class);

  private final PosixFs posixFs;

  DropDataDirectoryPageCacheStep() {
    this(new PosixFs());
  }

  DropDataDirectoryPageCacheStep(final PosixFs posixFs) {
    this.posixFs = posixFs;
  }

  @Override
  public String getName() {
    return "Drop Data Directory Page Cache";
  }

  @Override
  void startupInternal(
      final BrokerStartupContext brokerStartupContext,
      final ConcurrencyControl concurrencyControl,
      final ActorFuture<BrokerStartupContext> startupFuture) {
    final var dataDirectory =
        Path.of(brokerStartupContext.getBrokerConfiguration().getData().getDirectory());
    if (posixFs.isDropPageCacheSupported() && Files.isDirectory(dataDirectory)) {
      dropPageCache(dataDirectory);
    }

    startupFuture.complete(brokerStartupContext);
  }

  @Override
  void shutdownInternal(
      final BrokerStartupContext brokerShutdownContext,
      final ConcurrencyControl concurrencyControl,
      final ActorFuture<BrokerStartupContext> shutdownFuture) {
    shutdownFuture.complete(brokerShutdownContext);
  }

  private void dropPageCache(final Path dataDirectory) {
    // the real path, as walking a symbolic link to the directory would only visit the link
    try (final var files = Files.walk(dataDirectory.toRealPath())) {
      final var droppedFiles =
          files.filter(Files::isRegularFile).filter(posixFs::dropPageCache).count();
      LOG.debug(
          "Dropped the cached pages of {} files in the data directory {}",
          droppedFiles,
          dataDirectory);
    } catch (final IOException | UncheckedIOException e) {
      LOG.warn(
          "Failed to drop the cached pages of the files in the data directory {}",
          dataDirectory,
          e);
    }
  }
}
