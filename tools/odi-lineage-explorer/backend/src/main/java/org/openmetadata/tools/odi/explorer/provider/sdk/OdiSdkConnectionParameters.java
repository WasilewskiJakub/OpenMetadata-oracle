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

import java.util.Arrays;

record OdiSdkConnectionParameters(
    String jdbcUrl,
    String repositoryUsername,
    char[] repositoryPassword,
    String workRepositoryName,
    String odiUsername,
    char[] odiPassword)
    implements AutoCloseable {
  OdiSdkConnectionParameters {
    repositoryPassword = Arrays.copyOf(repositoryPassword, repositoryPassword.length);
    odiPassword = Arrays.copyOf(odiPassword, odiPassword.length);
  }

  @Override
  public void close() {
    Arrays.fill(repositoryPassword, '\0');
    Arrays.fill(odiPassword, '\0');
  }

  @Override
  public String toString() {
    return "OdiSdkConnectionParameters[jdbcUrl=%s, repositoryUsername=%s, "
        + "repositoryPassword=<redacted>, workRepositoryName=%s, odiUsername=%s, "
        + "odiPassword=<redacted>]"
            .formatted(jdbcUrl, repositoryUsername, workRepositoryName, odiUsername);
  }
}
