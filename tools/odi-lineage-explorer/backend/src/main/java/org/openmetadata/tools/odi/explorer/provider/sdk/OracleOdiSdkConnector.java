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

import oracle.odi.core.OdiInstance;
import oracle.odi.core.config.MasterRepositoryDbInfo;
import oracle.odi.core.config.OdiConfigurationException;
import oracle.odi.core.config.OdiInstanceConfig;
import oracle.odi.core.config.PoolingAttributes;
import oracle.odi.core.config.WorkRepositoryDbInfo;
import oracle.odi.core.security.Authentication;
import oracle.odi.core.security.AuthenticationException;
import oracle.odi.core.security.SecurityManager;
import org.openmetadata.tools.odi.explorer.provider.OdiAuthenticationException;
import org.openmetadata.tools.odi.explorer.provider.OdiConnectionException;

final class OracleOdiSdkConnector implements OdiSdkConnector {
  private static final int INACTIVE_CONNECTION_TIMEOUT_SECONDS = 120;
  private static final int INITIAL_POOL_SIZE = 1;
  private static final int MAX_POOL_SIZE = 4;
  private static final int MIN_POOL_SIZE = 1;
  private static final int STATEMENT_CACHE_SIZE = 32;
  private static final String JDBC_DRIVER = "oracle.jdbc.OracleDriver";
  private final OdiInstanceCreator instanceCreator;

  OracleOdiSdkConnector() {
    this(config -> OdiInstance.createInstance(config));
  }

  OracleOdiSdkConnector(OdiInstanceCreator instanceCreator) {
    this.instanceCreator = instanceCreator;
  }

  @Override
  public OdiSdkConnection connect(OdiSdkConnectionParameters parameters) {
    OdiInstance instance = null;
    Authentication authentication = null;
    try {
      instance = createInstance(parameters);
      authentication = authenticate(instance, parameters);
      return new OdiSdkConnection(instance, authentication, parameters);
    } catch (AuthenticationException exception) {
      closeAfterFailure(instance, authentication, exception);
      throw new OdiAuthenticationException("ODI authentication failed.", exception);
    } catch (OdiConfigurationException exception) {
      closeAfterFailure(instance, authentication, exception);
      throw new OdiConnectionException("Unable to connect to the ODI repository.", exception);
    } catch (RuntimeException exception) {
      closeAfterFailure(instance, authentication, exception);
      throw new OdiConnectionException("Unable to initialize the ODI SDK.", exception);
    } catch (Error error) {
      closeAfterFailure(instance, authentication, error);
      throw error;
    }
  }

  static PoolingAttributes poolingAttributes() {
    return new PoolingAttributes(
        INITIAL_POOL_SIZE,
        MAX_POOL_SIZE,
        MIN_POOL_SIZE,
        INACTIVE_CONNECTION_TIMEOUT_SECONDS,
        STATEMENT_CACHE_SIZE);
  }

  private OdiInstance createInstance(OdiSdkConnectionParameters parameters) {
    final PoolingAttributes pooling = poolingAttributes();
    final MasterRepositoryDbInfo master =
        new MasterRepositoryDbInfo(
            parameters.jdbcUrl(),
            JDBC_DRIVER,
            parameters.repositoryUsername(),
            parameters.repositoryPassword(),
            pooling);
    final WorkRepositoryDbInfo work =
        new WorkRepositoryDbInfo(parameters.workRepositoryName(), pooling);
    return instanceCreator.create(new OdiInstanceConfig(master, work));
  }

  private Authentication authenticate(OdiInstance instance, OdiSdkConnectionParameters parameters) {
    final SecurityManager securityManager = instance.getSecurityManager();
    final Authentication authentication =
        securityManager.createAuthentication(parameters.odiUsername(), parameters.odiPassword());
    installThreadAuthentication(securityManager, authentication);
    return authentication;
  }

  private void installThreadAuthentication(
      SecurityManager securityManager, Authentication authentication) {
    try {
      securityManager.setCurrentThreadAuthentication(authentication);
    } catch (RuntimeException | Error failure) {
      try {
        authentication.close();
      } catch (RuntimeException | Error closeFailure) {
        failure.addSuppressed(closeFailure);
      }
      throw failure;
    }
  }

  private void closeAfterFailure(
      OdiInstance instance, Authentication authentication, Throwable failure) {
    if (instance != null) {
      try {
        closeSdkResources(instance, authentication);
      } catch (RuntimeException | Error closeFailure) {
        failure.addSuppressed(closeFailure);
      }
    }
  }

  private void closeSdkResources(OdiInstance instance, Authentication authentication) {
    try {
      final SecurityManager securityManager = instance.getSecurityManager();
      if (securityManager.hasCurrentThreadAuthentication()) {
        securityManager.clearCurrentThreadAuthentication();
      }
    } finally {
      try {
        if (authentication != null) {
          authentication.close();
        }
      } finally {
        instance.close();
      }
    }
  }

  @FunctionalInterface
  interface OdiInstanceCreator {
    OdiInstance create(OdiInstanceConfig config);
  }
}
