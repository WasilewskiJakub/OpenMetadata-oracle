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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import oracle.odi.core.OdiInstance;
import oracle.odi.core.persistence.IOdiEntityManager;
import oracle.odi.core.security.Authentication;
import oracle.odi.core.security.SecurityManager;
import oracle.odi.domain.topology.OdiContext;
import oracle.odi.domain.topology.finder.IOdiContextFinder;
import org.junit.jupiter.api.Test;
import org.openmetadata.tools.odi.explorer.provider.OdiConnectionException;

class OdiSdkReadProviderTest {
  @Test
  void readAcceptedBeforeCloseIsQueuedBeforeConnectionShutdown() throws Exception {
    final OdiInstance instance = mock(OdiInstance.class);
    final Authentication authentication = mock(Authentication.class);
    final SecurityManager securityManager = mock(SecurityManager.class);
    final IOdiEntityManager entityManager = mock(IOdiEntityManager.class);
    final IOdiContextFinder contextFinder = mock(IOdiContextFinder.class);
    final ControlledSubmissionExecutor executor = new ControlledSubmissionExecutor();
    when(instance.getSecurityManager()).thenReturn(securityManager);
    when(instance.getTransactionalEntityManager()).thenReturn(entityManager);
    when(entityManager.getFinder(OdiContext.class)).thenReturn(contextFinder);
    when(contextFinder.findAll()).thenReturn(List.of());
    final OdiSdkReadProvider provider =
        new OdiSdkReadProvider(
            new OdiSdkConnection(instance, authentication, connectionParameters()),
            executor,
            "WORKREP");

    final CompletableFuture<?> read = CompletableFuture.supplyAsync(provider::contexts);
    assertThat(executor.awaitReadSubmission()).isTrue();
    final CompletableFuture<Void> close = CompletableFuture.runAsync(provider::close);
    executor.awaitPossibleCloseOvertake();
    executor.releaseReadSubmission();

    assertThat(read.get(5, TimeUnit.SECONDS)).isEqualTo(List.of());
    close.get(5, TimeUnit.SECONDS);
    assertThat(executor.didCloseOvertakeRead()).isFalse();
  }

  @Test
  void firstReadTimeoutPoisonsFullQueueAndGuaranteesExactlyOneLateClose() throws Exception {
    final CountDownLatch readStarted = new CountDownLatch(1);
    final CountDownLatch releaseRead = new CountDownLatch(1);
    final CountDownLatch connectionClosed = new CountDownLatch(1);
    final OdiInstance instance = mock(OdiInstance.class);
    final Authentication authentication = mock(Authentication.class);
    final SecurityManager securityManager = mock(SecurityManager.class);
    final IOdiEntityManager entityManager = mock(IOdiEntityManager.class);
    final IOdiContextFinder contextFinder = mock(IOdiContextFinder.class);
    final ReadBacklogExecutor executor = new ReadBacklogExecutor();
    when(instance.getSecurityManager()).thenReturn(securityManager);
    when(instance.getTransactionalEntityManager())
        .thenAnswer(
            invocation -> {
              readStarted.countDown();
              awaitUninterruptibly(releaseRead);
              return entityManager;
            });
    when(entityManager.getFinder(OdiContext.class)).thenReturn(contextFinder);
    when(contextFinder.findAll()).thenReturn(List.of());
    doAnswer(
            invocation -> {
              connectionClosed.countDown();
              return null;
            })
        .when(instance)
        .close();
    final OdiSdkReadProvider provider =
        new OdiSdkReadProvider(
            new OdiSdkConnection(instance, authentication, connectionParameters()),
            executor,
            "WORKREP",
            Duration.ofMillis(500));

    final CompletableFuture<?> firstRead = CompletableFuture.supplyAsync(provider::contexts);
    assertThat(readStarted.await(5, TimeUnit.SECONDS)).isTrue();
    final CompletableFuture<?> queuedRead = CompletableFuture.supplyAsync(provider::contexts);
    assertThat(executor.awaitFullReadBacklog()).isTrue();
    assertThat(executor.getQueue()).hasSize(1);
    assertThat(executor.getQueue().remainingCapacity()).isEqualTo(1);
    try {
      assertThatThrownBy(() -> firstRead.get(5, TimeUnit.SECONDS))
          .cause()
          .isInstanceOf(OdiConnectionException.class)
          .hasMessage("Timed out while reading the ODI repository.");
      assertThatThrownBy(provider::contexts)
          .isInstanceOf(OdiConnectionException.class)
          .hasMessage("The ODI SDK session is closed.");
      assertThat(executor.getQueue()).hasSize(1);
    } finally {
      releaseRead.countDown();
    }

    queuedRead.handle((result, failure) -> null).get(5, TimeUnit.SECONDS);
    assertThat(connectionClosed.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    verify(instance, times(1)).close();
  }

  private OdiSdkConnectionParameters connectionParameters() {
    return new OdiSdkConnectionParameters(
        "jdbc:test",
        "repository-user",
        "repository-secret".toCharArray(),
        "WORKREP",
        "odi-user",
        "odi-secret".toCharArray());
  }

  private void awaitUninterruptibly(CountDownLatch release) {
    boolean wasReleased = false;
    while (!wasReleased) {
      try {
        release.await();
        wasReleased = true;
      } catch (InterruptedException exception) {
        Thread.interrupted();
      }
    }
  }

  private static final class ControlledSubmissionExecutor extends ThreadPoolExecutor {
    private final Semaphore closeSubmission = new Semaphore(0);
    private final AtomicBoolean didCloseOvertakeRead = new AtomicBoolean();
    private final AtomicBoolean isReadSubmitted = new AtomicBoolean();
    private final Semaphore readSubmission = new Semaphore(0);
    private final Semaphore releaseReadSubmission = new Semaphore(0);

    private ControlledSubmissionExecutor() {
      super(1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(2));
    }

    @Override
    public <T> Future<T> submit(Callable<T> task) {
      readSubmission.release();
      acquire(releaseReadSubmission);
      final Future<T> result = super.submit(task);
      isReadSubmitted.set(true);
      return result;
    }

    @Override
    public Future<?> submit(Runnable task) {
      didCloseOvertakeRead.set(!isReadSubmitted.get());
      closeSubmission.release();
      return super.submit(task);
    }

    private boolean awaitReadSubmission() throws InterruptedException {
      return readSubmission.tryAcquire(5, TimeUnit.SECONDS);
    }

    private void awaitPossibleCloseOvertake() throws InterruptedException {
      closeSubmission.tryAcquire(100, TimeUnit.MILLISECONDS);
    }

    private void releaseReadSubmission() {
      releaseReadSubmission.release();
    }

    private boolean didCloseOvertakeRead() {
      return didCloseOvertakeRead.get();
    }

    private void acquire(Semaphore semaphore) {
      try {
        semaphore.acquire();
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new AssertionError("Interrupted while coordinating SDK submission.", exception);
      }
    }
  }

  private static final class ReadBacklogExecutor extends ThreadPoolExecutor {
    private final Semaphore fullReadBacklog = new Semaphore(0);
    private final AtomicInteger readSubmissions = new AtomicInteger();

    private ReadBacklogExecutor() {
      super(1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(2));
    }

    @Override
    public <T> Future<T> submit(Callable<T> task) {
      final Future<T> result = super.submit(task);
      if (readSubmissions.incrementAndGet() == 2) {
        fullReadBacklog.release();
      }
      return result;
    }

    private boolean awaitFullReadBacklog() throws InterruptedException {
      return fullReadBacklog.tryAcquire(5, TimeUnit.SECONDS);
    }
  }
}
