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

package org.openmetadata.tools.odi.explorer;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.openmetadata.tools.odi.explorer.http.OdiExplorerServer;
import org.openmetadata.tools.odi.explorer.provider.DemoOdiReadProvider;

class OdiExplorerApplicationTest {
  @Test
  void startsDemoApplicationOnEphemeralPort() throws Exception {
    try (OdiExplorerServer server = OdiExplorerApplication.start(0)) {
      assertTrue(server.port() > 0);
    }
  }

  @Test
  void startsApplicationWithAnInjectedRealProviderFactory() throws Exception {
    try (OdiExplorerServer server =
        OdiExplorerApplication.start(0, request -> new DemoOdiReadProvider())) {
      assertTrue(server.port() > 0);
    }
  }
}
