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

package org.openmetadata.tools.odi.explorer.session;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.openmetadata.tools.odi.explorer.provider.DemoOdiReadProvider;
import org.openmetadata.tools.odi.explorer.provider.OdiReadProvider;

class BoundedSessionStoreTest {
  private static final Instant START = Instant.parse("2026-09-02T12:00:00Z");

  @Test
  void evictsLeastRecentlyUsedSessionAtCapacity() {
    final MutableClock clock = new MutableClock(START);
    try (BoundedSessionStore store = new BoundedSessionStore(2, Duration.ofMinutes(30), clock)) {
      final Session first = store.create(new DemoOdiReadProvider());
      final Session second = store.create(new DemoOdiReadProvider());
      assertTrue(store.find(first.token()).isPresent());
      final Session third = store.create(new DemoOdiReadProvider());

      assertTrue(store.find(first.token()).isPresent());
      assertFalse(store.find(second.token()).isPresent());
      assertTrue(store.find(third.token()).isPresent());
      assertEquals(2, store.size());
    }
  }

  @Test
  void removesExpiredSessionInsteadOfReturningIt() {
    final MutableClock clock = new MutableClock(START);
    try (BoundedSessionStore store = new BoundedSessionStore(2, Duration.ofMinutes(5), clock)) {
      final Session session = store.create(new DemoOdiReadProvider());

      clock.advance(Duration.ofMinutes(6));

      assertTrue(store.find(session.token()).isEmpty());
      assertEquals(0, store.size());
    }
  }

  @Test
  void rejectsUnboundedOrNonExpiringConfiguration() {
    final MutableClock clock = new MutableClock(START);

    assertThrows(
        IllegalArgumentException.class,
        () -> new BoundedSessionStore(0, Duration.ofMinutes(5), clock));
    assertThrows(
        IllegalArgumentException.class, () -> new BoundedSessionStore(2, Duration.ZERO, clock));
  }

  @Test
  void sweeperClosesExpiredProviderWithoutAnotherStoreRequest() throws InterruptedException {
    final CountDownLatch providerClosed = new CountDownLatch(1);
    final OdiReadProvider provider = mock(OdiReadProvider.class);
    doAnswer(
            ignored -> {
              providerClosed.countDown();
              return null;
            })
        .when(provider)
        .close();

    try (BoundedSessionStore store =
        new BoundedSessionStore(2, Duration.ofMillis(20), Clock.systemUTC())) {
      store.create(provider);

      assertTrue(providerClosed.await(2, TimeUnit.SECONDS));
      assertEquals(0, store.size());
    }
  }

  @Test
  void closeAttemptsEveryProviderAndAlwaysClearsSessions() {
    final OdiReadProvider failingProvider = mock(OdiReadProvider.class);
    final OdiReadProvider healthyProvider = mock(OdiReadProvider.class);
    doThrow(new IllegalStateException("sensitive failure")).when(failingProvider).close();
    final BoundedSessionStore store =
        new BoundedSessionStore(2, Duration.ofMinutes(30), Clock.systemUTC());
    store.create(failingProvider);
    store.create(healthyProvider);

    assertDoesNotThrow(store::close);

    verify(failingProvider).close();
    verify(healthyProvider).close();
    assertEquals(0, store.size());
  }

  @Test
  void evictionStoresReplacementWhenEvictedProviderFailsToClose() {
    final OdiReadProvider failingProvider = mock(OdiReadProvider.class);
    final OdiReadProvider replacement = mock(OdiReadProvider.class);
    doThrow(new IllegalStateException("sensitive failure")).when(failingProvider).close();

    try (BoundedSessionStore store =
        new BoundedSessionStore(1, Duration.ofMinutes(30), Clock.systemUTC())) {
      final Session evicted = store.create(failingProvider);
      final Session retained = assertDoesNotThrow(() -> store.create(replacement));

      assertTrue(store.find(evicted.token()).isEmpty());
      assertTrue(store.find(retained.token()).isPresent());
      verify(failingProvider).close();
    }
  }

  @Test
  void expiryAttemptsEveryProviderWhenOneFailsToClose() {
    final MutableClock clock = new MutableClock(START);
    final OdiReadProvider failingProvider = mock(OdiReadProvider.class);
    final OdiReadProvider healthyProvider = mock(OdiReadProvider.class);
    doThrow(new IllegalStateException("sensitive failure")).when(failingProvider).close();

    try (BoundedSessionStore store = new BoundedSessionStore(2, Duration.ofMinutes(5), clock)) {
      store.create(failingProvider);
      store.create(healthyProvider);
      clock.advance(Duration.ofMinutes(6));

      assertEquals(0, store.size());
      verify(failingProvider).close();
      verify(healthyProvider).close();
    }
  }

  @Test
  void logoutRemovesSessionWhenProviderFailsToClose() {
    final OdiReadProvider provider = mock(OdiReadProvider.class);
    doThrow(new IllegalStateException("sensitive failure")).when(provider).close();

    try (BoundedSessionStore store =
        new BoundedSessionStore(1, Duration.ofMinutes(30), Clock.systemUTC())) {
      final Session session = store.create(provider);

      assertTrue(assertDoesNotThrow(() -> store.remove(session.token())));
      assertTrue(store.find(session.token()).isEmpty());
    }
  }

  @Test
  void closeShutsDownInjectedSweeper() {
    final ScheduledThreadPoolExecutor sweeper = new ScheduledThreadPoolExecutor(1);
    final BoundedSessionStore store =
        new BoundedSessionStore(1, Duration.ofMinutes(30), Clock.systemUTC(), sweeper);

    store.close();

    assertTrue(sweeper.isShutdown());
  }

  private static final class MutableClock extends Clock {
    private Instant instant;

    private MutableClock(Instant instant) {
      this.instant = instant;
    }

    private void advance(Duration duration) {
      instant = instant.plus(duration);
    }

    @Override
    public ZoneId getZone() {
      return ZoneId.of("UTC");
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return instant;
    }
  }
}
