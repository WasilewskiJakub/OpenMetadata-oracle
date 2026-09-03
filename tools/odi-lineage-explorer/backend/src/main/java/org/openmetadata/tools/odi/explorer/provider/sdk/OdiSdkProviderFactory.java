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
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.openmetadata.tools.odi.explorer.provider.OdiConnectionException;
import org.openmetadata.tools.odi.explorer.provider.OdiConnectionRequest;
import org.openmetadata.tools.odi.explorer.provider.OdiProviderFactory;
import org.openmetadata.tools.odi.explorer.provider.OdiReadProvider;

public final class OdiSdkProviderFactory implements OdiProviderFactory {
  private static final Duration CONNECTION_TIMEOUT = Duration.ofSeconds(120);
  private static final int MAX_ACTIVE_SDK_EXECUTORS = 32;
  private static final int SDK_READ_QUEUE_CAPACITY = 32;
  private static final int SDK_QUEUE_CAPACITY = SDK_READ_QUEUE_CAPACITY + 1;
  private static final Semaphore SDK_EXECUTOR_PERMITS =
      new Semaphore(MAX_ACTIVE_SDK_EXECUTORS, true);
  private static final AtomicInteger THREAD_SEQUENCE = new AtomicInteger();
  private final Duration connectionTimeout;
  private final OdiSdkConnector connector;
  private final Semaphore sdkExecutorPermits;

  public OdiSdkProviderFactory() {
    this(new OracleOdiSdkConnector(), CONNECTION_TIMEOUT, SDK_EXECUTOR_PERMITS);
  }

  OdiSdkProviderFactory(OdiSdkConnector connector) {
    this(connector, CONNECTION_TIMEOUT, SDK_EXECUTOR_PERMITS);
  }

  OdiSdkProviderFactory(OdiSdkConnector connector, Duration connectionTimeout) {
    this(connector, connectionTimeout, SDK_EXECUTOR_PERMITS);
  }

  OdiSdkProviderFactory(
      OdiSdkConnector connector, Duration connectionTimeout, Semaphore sdkExecutorPermits) {
    this.connector = connector;
    this.connectionTimeout = connectionTimeout;
    this.sdkExecutorPermits = sdkExecutorPermits;
  }

  @Override
  public OdiReadProvider create(OdiConnectionRequest request) {
    return request.withRepositoryPassword(
        repositoryPassword ->
            request.withOdiPassword(
                odiPassword -> create(request, repositoryPassword, odiPassword)));
  }

  private OdiReadProvider create(
      OdiConnectionRequest request, char[] repositoryPassword, char[] odiPassword) {
    final ThreadPoolExecutor executor = createPermittedExecutor();
    try {
      final OdiSdkConnectionParameters parameters =
          parameters(request, repositoryPassword, odiPassword);
      final OdiSdkConnection connection = connect(executor, parameters);
      return new OdiSdkReadProvider(connection, executor, request.workRepositoryName());
    } catch (RuntimeException | Error exception) {
      executor.shutdownNow();
      throw exception;
    }
  }

  private OdiSdkConnectionParameters parameters(
      OdiConnectionRequest request, char[] repositoryPassword, char[] odiPassword) {
    return new OdiSdkConnectionParameters(
        request.jdbcUrl(),
        request.repositoryUsername(),
        repositoryPassword,
        request.workRepositoryName(),
        request.odiUsername(),
        odiPassword);
  }

  private OdiSdkConnection connect(
      ThreadPoolExecutor executor, OdiSdkConnectionParameters parameters) {
    final ConnectionAttempt attempt =
        new ConnectionAttempt(connector, executor, parameters, connectionTimeout);
    try {
      return attempt.connect();
    } catch (RuntimeException | Error exception) {
      attempt.cleanupAfterFailure();
      throw exception;
    }
  }

  private static final class ConnectionAttempt {
    private final Duration connectionTimeout;
    private final OdiSdkConnector connector;
    private final Runnable connectionTask = this::connectOnSdkThread;
    private final AtomicBoolean isCleanupStarted = new AtomicBoolean();
    private final AtomicBoolean isTaskScheduled = new AtomicBoolean();
    private final CompletableFuture<OdiSdkConnection> outcome = new CompletableFuture<>();
    private final OdiSdkConnectionParameters parameters;
    private final ThreadPoolExecutor sdkExecutor;

    private ConnectionAttempt(
        OdiSdkConnector connector,
        ThreadPoolExecutor sdkExecutor,
        OdiSdkConnectionParameters parameters,
        Duration connectionTimeout) {
      this.connector = connector;
      this.sdkExecutor = sdkExecutor;
      this.parameters = parameters;
      this.connectionTimeout = connectionTimeout;
    }

    private OdiSdkConnection connect() {
      sdkExecutor.execute(connectionTask);
      isTaskScheduled.set(true);
      OdiSdkConnection result;
      try {
        result = outcome.get(connectionTimeout.toNanos(), TimeUnit.NANOSECONDS);
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        result = abandonOrReadCompleted(interruptedFailure(exception));
      } catch (TimeoutException exception) {
        result = abandonOrReadCompleted(timeoutFailure(exception));
      } catch (ExecutionException exception) {
        throw propagateFailure(exception.getCause());
      }
      return result;
    }

    private void connectOnSdkThread() {
      try {
        final OdiSdkConnection connection = connector.connect(parameters);
        // MasterRepositoryDbInfo retains this array for lazy pooled connections until SDK close.
        if (!outcome.complete(connection)) {
          closeLateConnection(connection);
        }
      } catch (RuntimeException | Error exception) {
        parameters.close();
        if (!outcome.completeExceptionally(exception)) {
          sdkExecutor.shutdownNow();
        }
        if (exception instanceof Error error) {
          throw error;
        }
      }
    }

    private OdiSdkConnection abandonOrReadCompleted(OdiConnectionException failure) {
      OdiSdkConnection result;
      if (outcome.completeExceptionally(failure)) {
        throw failure;
      }
      try {
        result = outcome.join();
      } catch (CompletionException exception) {
        throw propagateFailure(exception.getCause());
      }
      return result;
    }

    private void closeLateConnection(OdiSdkConnection connection) {
      try {
        connection.close();
      } finally {
        sdkExecutor.shutdownNow();
      }
    }

    private void cleanupAfterFailure() {
      if (isCleanupStarted.compareAndSet(false, true)) {
        final List<Runnable> droppedTasks = sdkExecutor.shutdownNow();
        if (!isTaskScheduled.get() || droppedTasks.contains(connectionTask)) {
          parameters.close();
        }
      }
    }

    private OdiConnectionException interruptedFailure(InterruptedException exception) {
      return new OdiConnectionException("Interrupted while connecting to ODI.", exception);
    }

    private OdiConnectionException timeoutFailure(TimeoutException exception) {
      return new OdiConnectionException("Timed out while connecting to ODI.", exception);
    }

    private RuntimeException propagateFailure(Throwable cause) {
      if (cause instanceof Error error) {
        throw error;
      }
      return cause instanceof RuntimeException runtimeException
          ? runtimeException
          : new OdiConnectionException("Unable to connect to ODI.", cause);
    }
  }

  private ThreadPoolExecutor createPermittedExecutor() {
    if (!sdkExecutorPermits.tryAcquire()) {
      throw new OdiConnectionException("ODI SDK session capacity is exhausted.");
    }
    ThreadPoolExecutor result;
    try {
      result = newExecutor();
    } catch (RuntimeException | Error exception) {
      sdkExecutorPermits.release();
      throw exception;
    }
    return result;
  }

  private ThreadPoolExecutor newExecutor() {
    return new PermitReleasingExecutor(SDK_QUEUE_CAPACITY, sdkThreadFactory(), sdkExecutorPermits);
  }

  private ThreadFactory sdkThreadFactory() {
    return task -> {
      final Thread thread = new Thread(task, "odi-sdk-reader-" + THREAD_SEQUENCE.incrementAndGet());
      thread.setContextClassLoader(OdiSdkProviderFactory.class.getClassLoader());
      thread.setDaemon(true);
      return thread;
    };
  }

  private static final class PermitReleasingExecutor extends ThreadPoolExecutor {
    private final Semaphore permits;

    private PermitReleasingExecutor(
        int queueCapacity, ThreadFactory threadFactory, Semaphore permits) {
      super(
          1,
          1,
          0L,
          TimeUnit.MILLISECONDS,
          new ArrayBlockingQueue<>(queueCapacity),
          threadFactory,
          new ThreadPoolExecutor.AbortPolicy());
      this.permits = permits;
    }

    @Override
    protected void terminated() {
      try {
        super.terminated();
      } finally {
        permits.release();
      }
    }
  }
}
