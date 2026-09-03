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
import oracle.odi.core.security.Authentication;
import oracle.odi.core.security.SecurityManager;

record OdiSdkConnection(
    OdiInstance instance,
    Authentication authentication,
    OdiSdkConnectionParameters connectionParameters)
    implements AutoCloseable {
  @Override
  public void close() {
    try {
      closeSdkResources();
    } finally {
      connectionParameters.close();
    }
  }

  private void closeSdkResources() {
    try {
      clearThreadAuthentication();
    } finally {
      try {
        closeAuthentication();
      } finally {
        instance.close();
      }
    }
  }

  private void clearThreadAuthentication() {
    final SecurityManager securityManager = instance.getSecurityManager();
    if (securityManager.hasCurrentThreadAuthentication()) {
      securityManager.clearCurrentThreadAuthentication();
    }
  }

  private void closeAuthentication() {
    if (authentication != null) {
      authentication.close();
    }
  }
}
