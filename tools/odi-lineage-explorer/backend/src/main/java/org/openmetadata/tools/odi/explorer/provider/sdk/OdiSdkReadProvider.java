/*
 *  Copyright 2026 Collate
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *  http://www.apache.org/licenses/LICENSE-2.0
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.openmetadata.tools.odi.explorer.provider.sdk;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import oracle.odi.core.persistence.IOdiEntityManager;
import org.openmetadata.tools.odi.explorer.model.ContextInfo;
import org.openmetadata.tools.odi.explorer.model.LoadPlanDetail;
import org.openmetadata.tools.odi.explorer.model.LoadPlanSummary;
import org.openmetadata.tools.odi.explorer.model.MappingDetail;
import org.openmetadata.tools.odi.explorer.model.RepositoryInfo;
import org.openmetadata.tools.odi.explorer.provider.OdiConnectionException;
import org.openmetadata.tools.odi.explorer.provider.OdiReadProvider;

final class OdiSdkReadProvider implements OdiReadProvider {
  private static final Duration OPERATION_TIMEOUT = Duration.ofSeconds(120);
  private final OdiSdkConnection connection;
  private Future<?> closeTask;
  private final AtomicBoolean isClosed = new AtomicBoolean();
  private final Object lifecycleLock = new Object();
  private final Duration operationTimeout;
  private final Set<Future<?>> pendingReads = new LinkedHashSet<>();
  private final ThreadPoolExecutor sdkExecutor;
  private final String workRepositoryName;

  OdiSdkReadProvider(
      OdiSdkConnection connection, ThreadPoolExecutor sdkExecutor, String workRepositoryName) {
    this(connection, sdkExecutor, workRepositoryName, OPERATION_TIMEOUT);
  }

  OdiSdkReadProvider(
      OdiSdkConnection connection,
      ThreadPoolExecutor sdkExecutor,
      String workRepositoryName,
      Duration operationTimeout) {
    this.connection = connection;
    this.sdkExecutor = sdkExecutor;
    this.workRepositoryName = workRepositoryName;
    this.operationTimeout = operationTimeout;
  }

  @Override
  public RepositoryInfo repository() {
    return execute(OdiSdkRepositoryReader::repository);
  }

  @Override
  public List<ContextInfo> contexts() {
    return execute(OdiSdkRepositoryReader::contexts);
  }

  @Override
  public List<LoadPlanSummary> loadPlans() {
    return execute(OdiSdkRepositoryReader::loadPlans);
  }

  @Override
  public LoadPlanDetail loadPlan(String id, String contextCode) {
    return execute(reader -> reader.loadPlan(id, contextCode));
  }

  @Override
  public MappingDetail mapping(String id, String contextCode) {
    return execute(reader -> reader.mapping(id, contextCode));
  }

  @Override
  public void close() {
    final Future<?> requestedClose = requestClose(false);
    if (requestedClose != null) {
      try {
        awaitClose(requestedClose);
      } finally {
        preserveQueuedClose(requestedClose);
      }
    }
  }

  private Future<?> requestClose(boolean shouldCancelPendingReads) {
    synchronized (lifecycleLock) {
      if (isClosed.compareAndSet(false, true)) {
        if (shouldCancelPendingReads) {
          cancelPendingReads();
        }
        try {
          closeTask = submitClose(this::closeConnection);
        } catch (RuntimeException exception) {
          sdkExecutor.shutdownNow();
          throw exception;
        }
      }
    }
    return closeTask;
  }

  private void cancelPendingReads() {
    for (final Future<?> pendingRead : pendingReads) {
      pendingRead.cancel(true);
    }
    sdkExecutor.purge();
  }

  private void closeConnection() {
    try {
      connection.close();
    } finally {
      sdkExecutor.shutdown();
    }
  }

  private <T> T execute(ReaderOperation<T> operation) {
    final Future<T> task;
    synchronized (lifecycleLock) {
      if (isClosed.get()) {
        throw new OdiConnectionException("The ODI SDK session is closed.");
      }
      task = submitRead(() -> read(operation));
      pendingReads.add(task);
    }
    try {
      return awaitRead(task);
    } finally {
      synchronized (lifecycleLock) {
        pendingReads.remove(task);
      }
    }
  }

  private <T> T read(ReaderOperation<T> operation) {
    final IOdiEntityManager entityManager = connection.instance().getTransactionalEntityManager();
    return operation.read(new OdiSdkRepositoryReader(entityManager, workRepositoryName));
  }

  private <T> Future<T> submitRead(java.util.concurrent.Callable<T> task) {
    if (sdkExecutor.getQueue().remainingCapacity() <= 1) {
      throw new OdiConnectionException("The ODI SDK read queue is full.");
    }
    try {
      return sdkExecutor.submit(task);
    } catch (RejectedExecutionException exception) {
      throw new OdiConnectionException("The ODI SDK reader is busy or closed.", exception);
    }
  }

  private Future<?> submitClose(Runnable task) {
    try {
      return sdkExecutor.submit(task);
    } catch (RejectedExecutionException exception) {
      throw new OdiConnectionException("The ODI SDK reader is busy or closed.", exception);
    }
  }

  private <T> T awaitRead(Future<T> task) {
    T result;
    try {
      result = task.get(operationTimeout.toNanos(), TimeUnit.NANOSECONDS);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      cancelRead(task);
      throw new OdiConnectionException("Interrupted while using the ODI SDK.", exception);
    } catch (TimeoutException exception) {
      cancelRead(task);
      requestClose(true);
      throw new OdiConnectionException("Timed out while reading the ODI repository.", exception);
    } catch (CancellationException exception) {
      throw new OdiConnectionException("The ODI SDK session is closed.", exception);
    } catch (ExecutionException exception) {
      throw propagate(exception.getCause());
    }
    return result;
  }

  private void cancelRead(Future<?> task) {
    task.cancel(true);
    sdkExecutor.purge();
  }

  private void awaitClose(Future<?> task) {
    try {
      task.get(operationTimeout.toNanos(), TimeUnit.NANOSECONDS);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new OdiConnectionException("Interrupted while using the ODI SDK.", exception);
    } catch (TimeoutException exception) {
      throw new OdiConnectionException("Timed out while closing the ODI SDK session.", exception);
    } catch (ExecutionException exception) {
      throw propagate(exception.getCause());
    }
  }

  private void preserveQueuedClose(Future<?> closeTask) {
    sdkExecutor.shutdown();
    if (closeTask.isDone()) {
      sdkExecutor.shutdownNow();
    }
  }

  private RuntimeException propagate(Throwable cause) {
    if (cause instanceof Error error) {
      throw error;
    }
    final RuntimeException result =
        cause instanceof RuntimeException runtimeException
            ? runtimeException
            : new OdiConnectionException("Unable to read the ODI repository.", cause);
    return result;
  }

  @FunctionalInterface
  private interface ReaderOperation<T> {
    T read(OdiSdkRepositoryReader reader);
  }
}
