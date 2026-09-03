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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import oracle.odi.core.OdiInstance;
import oracle.odi.core.config.PoolingAttributes;
import oracle.odi.core.persistence.IOdiEntityManager;
import oracle.odi.core.security.Authentication;
import oracle.odi.core.security.SecurityManager;
import oracle.odi.domain.mapping.Mapping;
import oracle.odi.domain.mapping.finder.IMappingFinder;
import oracle.odi.domain.runtime.loadplan.OdiLoadPlan;
import oracle.odi.domain.runtime.loadplan.finder.IOdiLoadPlanFinder;
import oracle.odi.domain.topology.OdiContext;
import oracle.odi.domain.topology.OdiMasterRepositoryInfo;
import oracle.odi.domain.topology.OdiWorkRepositoryInfo;
import oracle.odi.domain.topology.finder.IOdiContextFinder;
import oracle.odi.domain.topology.finder.IOdiMasterRepositoryInfoFinder;
import oracle.odi.domain.topology.finder.IOdiWorkRepositoryInfoFinder;
import org.junit.jupiter.api.Test;
import org.openmetadata.tools.odi.explorer.provider.OdiAuthenticationException;
import org.openmetadata.tools.odi.explorer.provider.OdiConnectionException;
import org.openmetadata.tools.odi.explorer.provider.OdiConnectionRequest;
import org.openmetadata.tools.odi.explorer.provider.OdiReadProvider;
import org.openmetadata.tools.odi.explorer.provider.ResourceNotFoundException;

class OdiSdkProviderFactoryTest {
  @Test
  void usesOneSdkThreadForConnectReadAndCloseWithoutCallingWriteApis() {
    final OdiInstance instance = mock(OdiInstance.class);
    final Authentication authentication = mock(Authentication.class);
    final SecurityManager securityManager = mock(SecurityManager.class);
    final IOdiEntityManager entityManager = mock(IOdiEntityManager.class);
    final IOdiContextFinder contextFinder = mock(IOdiContextFinder.class);
    final Set<Long> threadIds = ConcurrentHashMap.newKeySet();
    final OdiSdkConnector connector =
        parameters -> {
          threadIds.add(Thread.currentThread().threadId());
          return new OdiSdkConnection(instance, authentication, parameters);
        };
    when(instance.getSecurityManager()).thenReturn(securityManager);
    when(instance.getTransactionalEntityManager())
        .thenAnswer(
            invocation -> {
              threadIds.add(Thread.currentThread().threadId());
              return entityManager;
            });
    when(entityManager.getFinder(OdiContext.class)).thenReturn(contextFinder);
    when(contextFinder.findAll()).thenReturn(List.of());
    doAnswer(
            invocation -> {
              threadIds.add(Thread.currentThread().threadId());
              return null;
            })
        .when(securityManager)
        .clearCurrentThreadAuthentication();
    doAnswer(
            invocation -> {
              threadIds.add(Thread.currentThread().threadId());
              return null;
            })
        .when(authentication)
        .close();
    doAnswer(
            invocation -> {
              threadIds.add(Thread.currentThread().threadId());
              return null;
            })
        .when(instance)
        .close();

    final OdiReadProvider provider =
        new OdiSdkProviderFactory(connector).create(connectionRequest());
    assertThat(provider.contexts()).isEmpty();
    provider.close();

    assertThat(threadIds).hasSize(1);
    verify(instance, never()).createEntityManager();
    verify(instance, never()).getTransactionManager();
    verify(instance, never()).saveEntityManager(entityManager);
    verify(entityManager, never()).persist(any());
    verify(entityManager, never()).merge(any());
    verify(entityManager, never()).remove(any());
    verify(entityManager, never()).flush();
  }

  @Test
  void clearsTemporaryPasswordCopiesAfterConnecting() {
    final AtomicReference<char[]> repositoryPassword = new AtomicReference<>();
    final AtomicReference<char[]> odiPassword = new AtomicReference<>();
    final OdiInstance instance = mock(OdiInstance.class);
    final Authentication authentication = mock(Authentication.class);
    when(instance.getSecurityManager()).thenReturn(mock(SecurityManager.class));
    final OdiSdkConnector connector =
        parameters -> {
          repositoryPassword.set(parameters.repositoryPassword());
          odiPassword.set(parameters.odiPassword());
          return new OdiSdkConnection(instance, authentication, parameters);
        };

    final OdiReadProvider provider =
        new OdiSdkProviderFactory(connector).create(connectionRequest());

    assertThat(repositoryPassword.get()).containsExactly("repository-secret".toCharArray());
    assertThat(odiPassword.get()).containsExactly("odi-secret".toCharArray());
    provider.close();
    assertThat(repositoryPassword.get()).containsOnly('\0');
    assertThat(odiPassword.get()).containsOnly('\0');
  }

  @Test
  void preservesSanitizedAuthenticationFailure() {
    final OdiSdkConnector connector =
        parameters -> {
          throw new OdiAuthenticationException("ODI authentication failed.");
        };

    assertThatThrownBy(() -> new OdiSdkProviderFactory(connector).create(connectionRequest()))
        .isInstanceOf(OdiAuthenticationException.class)
        .hasMessage("ODI authentication failed.");
  }

  @Test
  void sdkThreadUsesAdapterClassLoaderWhenCallerContextCannotSeeOracleDependencies() {
    final AtomicReference<ClassLoader> observedClassLoader = new AtomicReference<>();
    final OdiInstance instance = mock(OdiInstance.class);
    final Authentication authentication = mock(Authentication.class);
    when(instance.getSecurityManager()).thenReturn(mock(SecurityManager.class));
    final OdiSdkConnector connector =
        parameters -> {
          observedClassLoader.set(Thread.currentThread().getContextClassLoader());
          return new OdiSdkConnection(instance, authentication, parameters);
        };
    final ClassLoader isolatedCallerClassLoader = new ClassLoader(null) {};

    final OdiReadProvider provider =
        createWithCallerContext(isolatedCallerClassLoader, new OdiSdkProviderFactory(connector));
    provider.close();

    assertThat(observedClassLoader.get()).isSameAs(OdiSdkProviderFactory.class.getClassLoader());
  }

  @Test
  void closesLateSuccessfulConnectionOnSdkThreadWithoutCallerThreadScrub() throws Exception {
    final CountDownLatch connectorStarted = new CountDownLatch(1);
    final CountDownLatch releaseConnector = new CountDownLatch(1);
    final CountDownLatch connectionClosed = new CountDownLatch(1);
    final AtomicReference<char[]> repositoryPassword = new AtomicReference<>();
    final AtomicReference<char[]> odiPassword = new AtomicReference<>();
    final AtomicReference<Long> connectThreadId = new AtomicReference<>();
    final AtomicReference<Long> closeThreadId = new AtomicReference<>();
    final OdiInstance instance = mock(OdiInstance.class);
    final Authentication authentication = mock(Authentication.class);
    when(instance.getSecurityManager()).thenReturn(mock(SecurityManager.class));
    doAnswer(
            invocation -> {
              closeThreadId.set(Thread.currentThread().threadId());
              connectionClosed.countDown();
              return null;
            })
        .when(instance)
        .close();
    final OdiSdkConnector connector =
        parameters -> {
          connectThreadId.set(Thread.currentThread().threadId());
          repositoryPassword.set(parameters.repositoryPassword());
          odiPassword.set(parameters.odiPassword());
          connectorStarted.countDown();
          awaitUninterruptibly(releaseConnector);
          return new OdiSdkConnection(instance, authentication, parameters);
        };

    assertThatThrownBy(
            () ->
                new OdiSdkProviderFactory(connector, Duration.ofMillis(250))
                    .create(connectionRequest()))
        .isInstanceOf(OdiConnectionException.class)
        .hasMessage("Timed out while connecting to ODI.");
    assertThat(connectorStarted.await(5, TimeUnit.SECONDS)).isTrue();
    try {
      assertThat(repositoryPassword.get()).containsExactly("repository-secret".toCharArray());
      assertThat(odiPassword.get()).containsExactly("odi-secret".toCharArray());
    } finally {
      releaseConnector.countDown();
    }
    assertThat(connectionClosed.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(closeThreadId.get()).isEqualTo(connectThreadId.get());
    assertThat(repositoryPassword.get()).containsOnly('\0');
    assertThat(odiPassword.get()).containsOnly('\0');
  }

  @Test
  void boundsBlockedSdkExecutorsUntilTheirRunningTaskActuallyTerminates() throws Exception {
    final CountDownLatch connectorStarted = new CountDownLatch(1);
    final CountDownLatch releaseConnector = new CountDownLatch(1);
    final CountDownLatch connectionClosed = new CountDownLatch(1);
    final AtomicBoolean wasInterrupted = new AtomicBoolean();
    final AtomicInteger connectorCalls = new AtomicInteger();
    final AtomicReference<char[]> repositoryPassword = new AtomicReference<>();
    final AtomicReference<char[]> odiPassword = new AtomicReference<>();
    final OdiInstance instance = mock(OdiInstance.class);
    final Authentication authentication = mock(Authentication.class);
    when(instance.getSecurityManager()).thenReturn(mock(SecurityManager.class));
    doAnswer(
            invocation -> {
              connectionClosed.countDown();
              return null;
            })
        .when(instance)
        .close();
    final OdiSdkConnector connector =
        parameters -> {
          connectorCalls.incrementAndGet();
          repositoryPassword.set(parameters.repositoryPassword());
          odiPassword.set(parameters.odiPassword());
          connectorStarted.countDown();
          wasInterrupted.set(awaitUninterruptibly(releaseConnector));
          return new OdiSdkConnection(instance, authentication, parameters);
        };
    final Semaphore sdkExecutorPermits = new Semaphore(1, true);
    final OdiSdkProviderFactory factory =
        new OdiSdkProviderFactory(connector, Duration.ofMillis(250), sdkExecutorPermits);

    assertThatThrownBy(() -> factory.create(connectionRequest()))
        .isInstanceOf(OdiConnectionException.class)
        .hasMessage("Timed out while connecting to ODI.");
    assertThat(connectorStarted.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(repositoryPassword.get()).containsExactly("repository-secret".toCharArray());
    assertThat(odiPassword.get()).containsExactly("odi-secret".toCharArray());

    assertThatThrownBy(() -> factory.create(connectionRequest()))
        .isInstanceOf(OdiConnectionException.class)
        .hasMessage("ODI SDK session capacity is exhausted.")
        .hasMessageNotContaining("repository-secret")
        .hasMessageNotContaining("odi-secret");
    assertThat(connectorCalls).hasValue(1);

    releaseConnector.countDown();
    assertThat(connectionClosed.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(wasInterrupted).isTrue();
    assertThat(repositoryPassword.get()).containsOnly('\0');
    assertThat(odiPassword.get()).containsOnly('\0');
    assertThat(sdkExecutorPermits.tryAcquire(5, TimeUnit.SECONDS)).isTrue();
    sdkExecutorPermits.release();
  }

  @Test
  void configuresABoundedConnectionPool() {
    final PoolingAttributes pooling = OracleOdiSdkConnector.poolingAttributes();

    assertThat(pooling.getInitialPoolSize()).isEqualTo(1);
    assertThat(pooling.getMinPoolSize()).isEqualTo(1);
    assertThat(pooling.getMaxPoolSize()).isEqualTo(4);
    assertThat(pooling.getStatementCacheSize()).isEqualTo(32);
    assertThat(pooling.getInactiveConnectionTimeout()).isEqualTo(120);
  }

  @Test
  void delegatesEveryProviderReadAndRejectsReadsAfterClose() {
    final OdiInstance instance = mock(OdiInstance.class);
    final Authentication authentication = mock(Authentication.class);
    final IOdiEntityManager entityManager = mock(IOdiEntityManager.class);
    final IOdiContextFinder contextFinder = mock(IOdiContextFinder.class);
    final IOdiLoadPlanFinder loadPlanFinder = mock(IOdiLoadPlanFinder.class);
    final IMappingFinder mappingFinder = mock(IMappingFinder.class);
    final IOdiMasterRepositoryInfoFinder masterFinder = mock(IOdiMasterRepositoryInfoFinder.class);
    final IOdiWorkRepositoryInfoFinder workFinder = mock(IOdiWorkRepositoryInfoFinder.class);
    final OdiContext context = mock(OdiContext.class);
    when(instance.getSecurityManager()).thenReturn(mock(SecurityManager.class));
    when(instance.getTransactionalEntityManager()).thenReturn(entityManager);
    when(entityManager.getFinder(OdiContext.class)).thenReturn(contextFinder);
    when(entityManager.getFinder(OdiLoadPlan.class)).thenReturn(loadPlanFinder);
    when(entityManager.getFinder(Mapping.class)).thenReturn(mappingFinder);
    when(entityManager.getFinder(OdiMasterRepositoryInfo.class)).thenReturn(masterFinder);
    when(entityManager.getFinder(OdiWorkRepositoryInfo.class)).thenReturn(workFinder);
    when(contextFinder.findAll()).thenReturn(List.of());
    when(contextFinder.findByCode("DEV")).thenReturn(context);
    when(loadPlanFinder.findAll()).thenReturn(List.of());
    final OdiSdkConnector connector =
        parameters -> new OdiSdkConnection(instance, authentication, parameters);
    final OdiReadProvider provider =
        new OdiSdkProviderFactory(connector).create(connectionRequest());

    assertThat(provider.repository().workRepository()).isEqualTo("WORKREP");
    assertThat(provider.contexts()).isEmpty();
    assertThat(provider.loadPlans()).isEmpty();
    assertThatThrownBy(() -> provider.loadPlan("404", "DEV"))
        .isInstanceOf(ResourceNotFoundException.class);
    assertThatThrownBy(() -> provider.mapping("404", "DEV"))
        .isInstanceOf(ResourceNotFoundException.class);
    provider.close();
    assertThatThrownBy(provider::contexts)
        .isInstanceOf(OdiConnectionException.class)
        .hasMessage("The ODI SDK session is closed.");
  }

  @Test
  void neverIncludesPasswordsInInternalConnectionDescription() {
    final OdiSdkConnectionParameters parameters =
        new OdiSdkConnectionParameters(
            "jdbc:test",
            "repository-user",
            "repository-secret".toCharArray(),
            "WORKREP",
            "odi-user",
            "odi-secret".toCharArray());

    assertThat(parameters.toString())
        .contains("repositoryPassword=<redacted>", "odiPassword=<redacted>")
        .doesNotContain("repository-secret", "odi-secret");
    parameters.close();
  }

  @Test
  void shutsDownSdkExecutorWhenConnectionCloseFails() {
    final OdiInstance instance = mock(OdiInstance.class);
    final Authentication authentication = mock(Authentication.class);
    final OdiSdkConnectionParameters parameters = connectionParameters();
    final ThreadPoolExecutor executor = testExecutor();
    when(instance.getSecurityManager()).thenReturn(mock(SecurityManager.class));
    doAnswer(
            invocation -> {
              throw new IllegalStateException("close failed");
            })
        .when(instance)
        .close();
    final OdiReadProvider provider =
        new OdiSdkReadProvider(
            new OdiSdkConnection(instance, authentication, parameters), executor, "WORKREP");

    assertThatThrownBy(provider::close).isInstanceOf(IllegalStateException.class);

    assertThat(executor.isShutdown()).isTrue();
    parameters.close();
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

  private OdiReadProvider createWithCallerContext(
      ClassLoader callerClassLoader, OdiSdkProviderFactory factory) {
    final ClassLoader originalClassLoader = Thread.currentThread().getContextClassLoader();
    OdiReadProvider result;
    try {
      Thread.currentThread().setContextClassLoader(callerClassLoader);
      result = factory.create(connectionRequest());
    } finally {
      Thread.currentThread().setContextClassLoader(originalClassLoader);
    }
    return result;
  }

  private boolean awaitUninterruptibly(CountDownLatch release) {
    boolean wasInterrupted = false;
    boolean wasReleased = false;
    while (!wasReleased) {
      try {
        release.await();
        wasReleased = true;
      } catch (InterruptedException exception) {
        wasInterrupted = true;
      }
    }
    if (wasInterrupted) {
      Thread.currentThread().interrupt();
    }
    return wasInterrupted;
  }

  private ThreadPoolExecutor testExecutor() {
    return new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1));
  }

  private OdiConnectionRequest connectionRequest() {
    return new OdiConnectionRequest(
        "jdbc:oracle:thin:@//localhost:1521/ODIPDB",
        "ODI_REPO",
        "repository-secret".toCharArray(),
        "WORKREP",
        "ODI_READER",
        "odi-secret".toCharArray());
  }
}
