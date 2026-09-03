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

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.openmetadata.tools.odi.explorer.provider.OdiReadProvider;

public final class BoundedSessionStore implements AutoCloseable {
  private static final Logger LOGGER = System.getLogger(BoundedSessionStore.class.getName());
  private static final int TOKEN_BYTES = 32;
  private static final Duration MAX_SWEEP_INTERVAL = Duration.ofMinutes(1);

  private final int capacity;
  private final Duration timeToLive;
  private final Clock clock;
  private final SecureRandom secureRandom = new SecureRandom();
  private final Map<String, Session> sessions = new LinkedHashMap<>(16, 0.75F, true);
  private final ScheduledExecutorService sweeper;
  private final ScheduledFuture<?> sweepTask;
  private boolean closed;

  public BoundedSessionStore(int capacity, Duration timeToLive, Clock clock) {
    this(configuration(capacity, timeToLive, clock), newSweeper());
  }

  BoundedSessionStore(
      int capacity, Duration timeToLive, Clock clock, ScheduledExecutorService sweeper) {
    this(configuration(capacity, timeToLive, clock), sweeper);
  }

  private BoundedSessionStore(Configuration configuration, ScheduledExecutorService sweeper) {
    capacity = configuration.capacity();
    timeToLive = configuration.timeToLive();
    clock = configuration.clock();
    this.sweeper = Objects.requireNonNull(sweeper);
    sweepTask = startSweeper(sweeper, sweepInterval(timeToLive));
  }

  public Session create(OdiReadProvider provider) {
    final OdiReadProvider sessionProvider = Objects.requireNonNull(provider);
    final List<Session> retired = new ArrayList<>();
    final Session session;
    synchronized (this) {
      requireOpen();
      retired.addAll(removeExpired());
      evictOldestAtCapacity().ifPresent(retired::add);
      session = new Session(newToken(), clock.instant().plus(timeToLive), sessionProvider);
      sessions.put(session.token(), session);
    }
    closeProviders(retired, "session maintenance");
    return session;
  }

  public Optional<Session> find(String token) {
    final List<Session> expired;
    final Optional<Session> result;
    synchronized (this) {
      expired = closed ? List.of() : removeExpired();
      result = Optional.ofNullable(sessions.get(token));
    }
    closeProviders(expired, "session expiry");
    return result;
  }

  public boolean remove(String token) {
    final Session removed;
    synchronized (this) {
      removed = sessions.remove(token);
    }
    closeProviders(optionalSession(removed), "session removal");
    return removed != null;
  }

  public int size() {
    final List<Session> expired;
    final int result;
    synchronized (this) {
      expired = closed ? List.of() : removeExpired();
      result = sessions.size();
    }
    closeProviders(expired, "session expiry");
    return result;
  }

  @Override
  public void close() {
    final List<Session> retained;
    synchronized (this) {
      retained = closed ? List.of() : List.copyOf(sessions.values());
      sessions.clear();
      closed = true;
    }
    sweepTask.cancel(false);
    sweeper.shutdownNow();
    closeProviders(retained, "session store shutdown");
  }

  private void sweepExpired() {
    final List<Session> expired;
    synchronized (this) {
      expired = closed ? List.of() : removeExpired();
    }
    closeProviders(expired, "scheduled session expiry");
  }

  private void sweepSafely() {
    try {
      sweepExpired();
    } catch (RuntimeException exception) {
      LOGGER.log(
          Level.WARNING,
          "Session expiry sweep failed with type {0}",
          exception.getClass().getName());
    }
  }

  private List<Session> removeExpired() {
    final List<Session> expired = new ArrayList<>();
    final Instant now = clock.instant();
    final Iterator<Session> iterator = sessions.values().iterator();
    while (iterator.hasNext()) {
      final Session session = iterator.next();
      if (!session.expiresAt().isAfter(now)) {
        iterator.remove();
        expired.add(session);
      }
    }
    return expired;
  }

  private Optional<Session> evictOldestAtCapacity() {
    Session evicted = null;
    if (sessions.size() >= capacity) {
      final Iterator<Session> iterator = sessions.values().iterator();
      evicted = iterator.next();
      iterator.remove();
    }
    return Optional.ofNullable(evicted);
  }

  private void closeProviders(List<Session> retired, String reason) {
    int failureCount = 0;
    final Set<String> failureTypes = new LinkedHashSet<>();
    for (Session session : retired) {
      try {
        session.provider().close();
      } catch (RuntimeException exception) {
        failureCount++;
        failureTypes.add(exception.getClass().getName());
      }
    }
    logCloseFailures(reason, failureCount, failureTypes);
  }

  private void logCloseFailures(String reason, int failureCount, Set<String> failureTypes) {
    if (failureCount > 0) {
      LOGGER.log(
          Level.WARNING,
          "Failed to close {0} ODI provider(s) during {1}; failure types: {2}",
          failureCount,
          reason,
          failureTypes);
    }
  }

  private String newToken() {
    final byte[] bytes = new byte[TOKEN_BYTES];
    secureRandom.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private void requireOpen() {
    if (closed) {
      throw new IllegalStateException("Session store is closed");
    }
  }

  private static List<Session> optionalSession(Session session) {
    return session == null ? List.of() : List.of(session);
  }

  private ScheduledFuture<?> startSweeper(ScheduledExecutorService sweeper, Duration interval) {
    final long intervalMillis = Math.max(1, interval.toMillis());
    ScheduledFuture<?> result;
    try {
      result =
          sweeper.scheduleWithFixedDelay(
              this::sweepSafely, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
    } catch (RuntimeException exception) {
      sweeper.shutdownNow();
      throw exception;
    }
    return result;
  }

  private static ScheduledExecutorService newSweeper() {
    return Executors.newSingleThreadScheduledExecutor(
        Thread.ofPlatform().daemon().name("odi-session-sweeper").factory());
  }

  private static Duration sweepInterval(Duration timeToLive) {
    return timeToLive.compareTo(MAX_SWEEP_INTERVAL) < 0 ? timeToLive : MAX_SWEEP_INTERVAL;
  }

  private static Configuration configuration(int capacity, Duration timeToLive, Clock clock) {
    if (capacity < 1 || timeToLive.isZero() || timeToLive.isNegative()) {
      throw new IllegalArgumentException("Session capacity and time-to-live must be positive");
    }
    return new Configuration(capacity, timeToLive, Objects.requireNonNull(clock));
  }

  private record Configuration(int capacity, Duration timeToLive, Clock clock) {}
}
