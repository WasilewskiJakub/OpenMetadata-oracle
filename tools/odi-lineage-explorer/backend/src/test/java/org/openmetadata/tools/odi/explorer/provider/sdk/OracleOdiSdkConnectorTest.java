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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.atomic.AtomicReference;
import oracle.odi.core.OdiInstance;
import oracle.odi.core.config.OdiConfigurationException;
import oracle.odi.core.config.OdiInstanceConfig;
import oracle.odi.core.security.Authentication;
import oracle.odi.core.security.AuthenticationException;
import oracle.odi.core.security.SecurityManager;
import org.junit.jupiter.api.Test;
import org.openmetadata.tools.odi.explorer.provider.OdiAuthenticationException;
import org.openmetadata.tools.odi.explorer.provider.OdiConnectionException;

class OracleOdiSdkConnectorTest {
  @Test
  void providesJsonTokenerRequiredByOdiInstanceInitialization() {
    assertThatCode(() -> Class.forName("org.json.JSONTokener")).doesNotThrowAnyException();
  }

  @Test
  void createsBoundedConfigurationAndSetsThreadAuthentication() {
    final OdiInstance instance = mock(OdiInstance.class);
    final SecurityManager securityManager = mock(SecurityManager.class);
    final Authentication authentication = mock(Authentication.class);
    final AtomicReference<OdiInstanceConfig> capturedConfig = new AtomicReference<>();
    final OracleOdiSdkConnector connector =
        new OracleOdiSdkConnector(
            config -> {
              capturedConfig.set(config);
              return instance;
            });
    when(instance.getSecurityManager()).thenReturn(securityManager);
    when(securityManager.createAuthentication(eq("ODI_READER"), any(char[].class)))
        .thenReturn(authentication);
    when(securityManager.hasCurrentThreadAuthentication()).thenReturn(true);

    final OdiSdkConnection connection = connector.connect(parameters());

    final OdiInstanceConfig config = capturedConfig.get();
    assertThat(config.getMasterRepositoryDbInfo().getJdbcUrl())
        .isEqualTo("jdbc:oracle:thin:@//localhost:1521/ODIPDB");
    assertThat(config.getMasterRepositoryDbInfo().getJdbcDriver())
        .isEqualTo("oracle.jdbc.OracleDriver");
    assertThat(config.getMasterRepositoryDbInfo().getJdbcUsername()).isEqualTo("ODI_REPO");
    assertThat(config.getMasterRepositoryDbInfo().getPoolingAttributes().getMaxPoolSize())
        .isEqualTo(4);
    assertThat(config.getWorkRepositoryDbInfo().getWorkName()).isEqualTo("WORKREP");
    verify(securityManager).setCurrentThreadAuthentication(authentication);

    connection.close();
    verify(securityManager).clearCurrentThreadAuthentication();
    verify(authentication).close();
    verify(instance).close();
  }

  @Test
  void mapsSdkAuthenticationFailureAndClosesPartiallyCreatedInstance() {
    final OdiInstance instance = mock(OdiInstance.class);
    final SecurityManager securityManager = mock(SecurityManager.class);
    final AuthenticationException failure = mock(AuthenticationException.class);
    when(instance.getSecurityManager()).thenReturn(securityManager);
    when(securityManager.createAuthentication(eq("ODI_READER"), any(char[].class)))
        .thenThrow(failure);
    final OracleOdiSdkConnector connector = new OracleOdiSdkConnector(config -> instance);

    assertThatThrownBy(() -> connector.connect(parameters()))
        .isInstanceOf(OdiAuthenticationException.class)
        .hasMessage("ODI authentication failed.");
    verify(instance).close();
  }

  @Test
  void mapsConfigurationFailureWithoutExposingConnectionValues() {
    final OdiConfigurationException failure = mock(OdiConfigurationException.class);
    final OracleOdiSdkConnector connector =
        new OracleOdiSdkConnector(
            config -> {
              throw failure;
            });

    assertThatThrownBy(() -> connector.connect(parameters()))
        .isInstanceOf(OdiConnectionException.class)
        .hasMessage("Unable to connect to the ODI repository.")
        .message()
        .doesNotContain("ODI_REPO", "repository-secret");
  }

  @Test
  void mapsUnexpectedSdkInitializationFailureToSanitizedError() {
    final OracleOdiSdkConnector connector =
        new OracleOdiSdkConnector(
            config -> {
              throw new IllegalStateException("SDK failed");
            });

    assertThatThrownBy(() -> connector.connect(parameters()))
        .isInstanceOf(OdiConnectionException.class)
        .hasMessage("Unable to initialize the ODI SDK.");
  }

  @Test
  void closesAuthenticationWhenInstallingThreadContextFails() {
    final OdiInstance instance = mock(OdiInstance.class);
    final SecurityManager securityManager = mock(SecurityManager.class);
    final Authentication authentication = mock(Authentication.class);
    final AuthenticationException failure = mock(AuthenticationException.class);
    when(instance.getSecurityManager()).thenReturn(securityManager);
    when(securityManager.createAuthentication(eq("ODI_READER"), any(char[].class)))
        .thenReturn(authentication);
    doThrow(failure).when(securityManager).setCurrentThreadAuthentication(authentication);
    final OracleOdiSdkConnector connector = new OracleOdiSdkConnector(config -> instance);

    assertThatThrownBy(() -> connector.connect(parameters()))
        .isInstanceOf(OdiAuthenticationException.class);
    verify(authentication).close();
    verify(instance).close();
  }

  @Test
  void preservesSdkErrorAndAttachesCleanupErrorAsSuppressed() {
    final OdiInstance instance = mock(OdiInstance.class);
    final SecurityManager securityManager = mock(SecurityManager.class);
    final AssertionError initializationFailure = new AssertionError("initialization failed");
    final AssertionError cleanupFailure = new AssertionError("cleanup failed");
    when(instance.getSecurityManager()).thenReturn(securityManager);
    when(securityManager.createAuthentication(eq("ODI_READER"), any(char[].class)))
        .thenThrow(initializationFailure);
    doThrow(cleanupFailure).when(instance).close();
    final OracleOdiSdkConnector connector = new OracleOdiSdkConnector(config -> instance);

    assertThatThrownBy(() -> connector.connect(parameters()))
        .isSameAs(initializationFailure)
        .satisfies(failure -> assertThat(failure.getSuppressed()).containsExactly(cleanupFailure));
  }

  private OdiSdkConnectionParameters parameters() {
    return new OdiSdkConnectionParameters(
        "jdbc:oracle:thin:@//localhost:1521/ODIPDB",
        "ODI_REPO",
        "repository-secret".toCharArray(),
        "WORKREP",
        "ODI_READER",
        "odi-secret".toCharArray());
  }
}
