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

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Clock;
import java.time.Duration;
import org.openmetadata.tools.odi.explorer.http.OdiExplorerServer;
import org.openmetadata.tools.odi.explorer.provider.DemoOdiReadProvider;
import org.openmetadata.tools.odi.explorer.provider.OdiConnectionException;
import org.openmetadata.tools.odi.explorer.provider.OdiProviderFactory;
import org.openmetadata.tools.odi.explorer.provider.sdk.OdiSdkProviderFactory;
import org.openmetadata.tools.odi.explorer.session.BoundedSessionStore;

public final class OdiExplorerApplication {
  private static final Logger LOGGER = System.getLogger(OdiExplorerApplication.class.getName());
  private static final String PORT_ENVIRONMENT = "ODI_EXPLORER_PORT";
  private static final int DEFAULT_PORT = 8080;
  private static final int SESSION_CAPACITY = 32;
  private static final Duration SESSION_TIME_TO_LIVE = Duration.ofMinutes(30);

  private OdiExplorerApplication() {}

  public static void main(String[] args) throws IOException {
    final OdiExplorerServer server = start(configuredPort(), new OdiSdkProviderFactory());
    Runtime.getRuntime().addShutdownHook(new Thread(server::close, "odi-explorer-shutdown"));
    LOGGER.log(Level.INFO, "ODI Lineage Explorer backend listening on port {0}", server.port());
  }

  static OdiExplorerServer start(int port) throws IOException {
    final OdiProviderFactory unavailableFactory =
        request -> {
          throw new OdiConnectionException("ODI provider factory is not configured");
        };
    return start(port, unavailableFactory);
  }

  static OdiExplorerServer start(int port, OdiProviderFactory providerFactory) throws IOException {
    final BoundedSessionStore sessions =
        new BoundedSessionStore(SESSION_CAPACITY, SESSION_TIME_TO_LIVE, Clock.systemUTC());
    return OdiExplorerServer.start(port, sessions, DemoOdiReadProvider::new, providerFactory);
  }

  private static int configuredPort() {
    final String value =
        System.getenv().getOrDefault(PORT_ENVIRONMENT, String.valueOf(DEFAULT_PORT));
    return Integer.parseInt(value);
  }
}
