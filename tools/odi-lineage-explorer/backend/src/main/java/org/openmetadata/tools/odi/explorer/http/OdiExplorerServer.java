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

package org.openmetadata.tools.odi.explorer.http;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.openmetadata.tools.odi.explorer.provider.OdiProviderFactory;
import org.openmetadata.tools.odi.explorer.provider.OdiReadProvider;
import org.openmetadata.tools.odi.explorer.session.BoundedSessionStore;

public final class OdiExplorerServer implements AutoCloseable {
  private static final int CORE_THREADS = 2;
  private static final int MAX_THREADS = 4;
  private static final int REQUEST_QUEUE_CAPACITY = 64;

  private final HttpServer server;
  private final ExecutorService executor;
  private final BoundedSessionStore sessions;

  private OdiExplorerServer(
      HttpServer server, ExecutorService executor, BoundedSessionStore sessions) {
    this.server = server;
    this.executor = executor;
    this.sessions = sessions;
  }

  public static OdiExplorerServer start(
      int port,
      BoundedSessionStore sessions,
      Supplier<OdiReadProvider> demoProviderFactory,
      OdiProviderFactory providerFactory)
      throws IOException {
    final InetSocketAddress address = new InetSocketAddress(InetAddress.getLoopbackAddress(), port);
    final HttpServer server = HttpServer.create(address, 0);
    final ExecutorService executor = requestExecutor();
    new ExplorerApi(sessions, demoProviderFactory, providerFactory).register(server);
    server.setExecutor(executor);
    server.start();
    return new OdiExplorerServer(server, executor, sessions);
  }

  public int port() {
    return server.getAddress().getPort();
  }

  @Override
  public void close() {
    server.stop(0);
    executor.shutdownNow();
    sessions.close();
  }

  private static ExecutorService requestExecutor() {
    return new ThreadPoolExecutor(
        CORE_THREADS,
        MAX_THREADS,
        30,
        TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(REQUEST_QUEUE_CAPACITY),
        Thread.ofPlatform().name("odi-explorer-http-", 0).factory(),
        new ThreadPoolExecutor.AbortPolicy());
  }
}
