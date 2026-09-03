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

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Instant;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.function.Supplier;
import org.openmetadata.tools.odi.explorer.model.RepositoryInfo;
import org.openmetadata.tools.odi.explorer.provider.OdiAuthenticationException;
import org.openmetadata.tools.odi.explorer.provider.OdiConnectionException;
import org.openmetadata.tools.odi.explorer.provider.OdiConnectionRequest;
import org.openmetadata.tools.odi.explorer.provider.OdiProviderFactory;
import org.openmetadata.tools.odi.explorer.provider.OdiReadProvider;
import org.openmetadata.tools.odi.explorer.provider.ResourceNotFoundException;
import org.openmetadata.tools.odi.explorer.session.BoundedSessionStore;
import org.openmetadata.tools.odi.explorer.session.Session;

final class ExplorerApi {
  private static final Logger LOGGER = System.getLogger(ExplorerApi.class.getName());
  private static final String GET = "GET";
  private static final String POST = "POST";
  private static final String DELETE = "DELETE";
  private static final String ALLOW = "Allow";
  private static final String WWW_AUTHENTICATE = "WWW-Authenticate";
  private static final String BEARER_CHALLENGE = "Bearer";
  private static final String CONTEXT_CODE = "contextCode";
  private static final String HEALTH_PATH = "/api/health";
  private static final String SESSION_PATH = "/api/sessions";
  private static final String DEMO_SESSION_PATH = "/api/sessions/demo";
  private static final String CURRENT_SESSION_PATH = "/api/sessions/current";
  private static final String CONTEXTS_PATH = "/api/contexts";
  private static final String LOAD_PLANS_PATH = "/api/load-plans";
  private static final String LOAD_PLAN_PREFIX = LOAD_PLANS_PATH + "/";
  private static final String MAPPINGS_PATH = "/api/mappings";
  private static final String MAPPING_PREFIX = MAPPINGS_PATH + "/";
  private static final String INTERNAL_ERROR_MESSAGE = "An unexpected server error occurred";
  private static final String AUTHENTICATION_ERROR_MESSAGE =
      "Authentication to the ODI repository failed";
  private static final String CONNECTION_ERROR_MESSAGE =
      "Could not connect to the requested ODI repository";
  private static final int MAX_CAUSE_DEPTH = 8;
  private static final int MAX_STACK_DEPTH = 8;

  private final BoundedSessionStore sessions;
  private final Supplier<OdiReadProvider> demoProviderFactory;
  private final OdiProviderFactory providerFactory;
  private final JsonResponder responder = new JsonResponder();
  private final SessionRequestReader requestReader = new SessionRequestReader();
  private final SessionAuthenticator authenticator;

  ExplorerApi(
      BoundedSessionStore sessions,
      Supplier<OdiReadProvider> demoProviderFactory,
      OdiProviderFactory providerFactory) {
    this.sessions = sessions;
    this.demoProviderFactory = demoProviderFactory;
    this.providerFactory = providerFactory;
    authenticator = new SessionAuthenticator(sessions);
  }

  void register(HttpServer server) {
    server.createContext(HEALTH_PATH, safely(this::health));
    server.createContext(SESSION_PATH, safely(this::createSession));
    server.createContext(DEMO_SESSION_PATH, safely(this::createDemoSession));
    server.createContext(CURRENT_SESSION_PATH, safely(this::deleteCurrentSession));
    server.createContext(CONTEXTS_PATH, safely(this::contexts));
    server.createContext(LOAD_PLANS_PATH, safely(this::loadPlans));
    server.createContext(MAPPINGS_PATH, safely(this::mapping));
  }

  private void health(HttpExchange exchange) throws IOException {
    if (acceptExact(exchange, GET, HEALTH_PATH)) {
      responder.json(exchange, 200, new HealthResponse("UP"));
    }
  }

  private void createDemoSession(HttpExchange exchange) throws IOException {
    if (acceptExact(exchange, POST, DEMO_SESSION_PATH)) {
      createSessionResponse(exchange, demoProviderFactory.get());
    }
  }

  private void createSession(HttpExchange exchange) throws IOException {
    if (acceptExact(exchange, POST, SESSION_PATH)) {
      try (OdiConnectionRequest request = requestReader.read(exchange)) {
        final OdiReadProvider provider = providerFactory.create(request);
        createSessionResponse(exchange, provider);
      }
    }
  }

  private void createSessionResponse(HttpExchange exchange, OdiReadProvider provider)
      throws IOException {
    final Session session = storeProvider(provider);
    boolean responseWritten = false;
    try {
      responder.json(exchange, 201, sessionInfo(session));
      responseWritten = true;
    } finally {
      removeUnpublishedSession(session, responseWritten);
    }
  }

  private void removeUnpublishedSession(Session session, boolean responseWritten) {
    if (!responseWritten) {
      sessions.remove(session.token());
    }
  }

  private Session storeProvider(OdiReadProvider provider) {
    Session result;
    try {
      result = sessions.create(provider);
    } catch (RuntimeException exception) {
      closeProviderAfterStoreFailure(provider);
      throw exception;
    }
    return result;
  }

  private void closeProviderAfterStoreFailure(OdiReadProvider provider) {
    if (provider != null) {
      try {
        provider.close();
      } catch (RuntimeException exception) {
        LOGGER.log(
            Level.WARNING,
            "ODI provider rollback close failed with type {0}",
            exception.getClass().getName());
      }
    }
  }

  private SessionInfo sessionInfo(Session session) {
    return new SessionInfo(session.token(), session.provider().repository(), session.expiresAt());
  }

  private void deleteCurrentSession(HttpExchange exchange) throws IOException {
    if (acceptExact(exchange, DELETE, CURRENT_SESSION_PATH)) {
      final Optional<Session> session = authorize(exchange);
      if (session.isPresent()) {
        sessions.remove(session.get().token());
        responder.empty(exchange, 204);
      }
    }
  }

  private void contexts(HttpExchange exchange) throws IOException {
    if (acceptExact(exchange, GET, CONTEXTS_PATH)) {
      final Optional<Session> session = authorize(exchange);
      if (session.isPresent()) {
        responder.json(exchange, 200, session.get().provider().contexts());
      }
    }
  }

  private void loadPlans(HttpExchange exchange) throws IOException {
    if (acceptPathPrefix(exchange, GET, LOAD_PLANS_PATH, LOAD_PLAN_PREFIX)) {
      final Optional<Session> session = authorize(exchange);
      if (session.isPresent()) {
        writeLoadPlans(exchange, session.get().provider());
      }
    }
  }

  private void writeLoadPlans(HttpExchange exchange, OdiReadProvider provider) throws IOException {
    final String path = exchange.getRequestURI().getPath();
    if (LOAD_PLANS_PATH.equals(path)) {
      responder.json(exchange, 200, provider.loadPlans());
    } else {
      final String id = RequestParameters.pathIdentifier(exchange, LOAD_PLAN_PREFIX);
      final String contextCode = RequestParameters.requiredQuery(exchange, CONTEXT_CODE);
      responder.json(exchange, 200, provider.loadPlan(id, contextCode));
    }
  }

  private void mapping(HttpExchange exchange) throws IOException {
    if (acceptPathPrefix(exchange, GET, null, MAPPING_PREFIX)) {
      final Optional<Session> session = authorize(exchange);
      if (session.isPresent()) {
        final String id = RequestParameters.pathIdentifier(exchange, MAPPING_PREFIX);
        final String contextCode = RequestParameters.requiredQuery(exchange, CONTEXT_CODE);
        responder.json(exchange, 200, session.get().provider().mapping(id, contextCode));
      }
    }
  }

  private Optional<Session> authorize(HttpExchange exchange) throws IOException {
    final Optional<Session> session = authenticator.authenticate(exchange);
    if (session.isEmpty()) {
      exchange.getResponseHeaders().set(WWW_AUTHENTICATE, BEARER_CHALLENGE);
      responder.error(exchange, 401, "UNAUTHORIZED", "A valid bearer session is required");
    }
    return session;
  }

  private boolean acceptExact(HttpExchange exchange, String method, String path)
      throws IOException {
    return acceptPathPrefix(exchange, method, path, null);
  }

  private boolean acceptPathPrefix(
      HttpExchange exchange, String method, String exactPath, String pathPrefix)
      throws IOException {
    final String actualPath = exchange.getRequestURI().getPath();
    final boolean hasExpectedPath =
        actualPath.equals(exactPath) || (pathPrefix != null && actualPath.startsWith(pathPrefix));
    final boolean accepted = hasExpectedPath && method.equals(exchange.getRequestMethod());
    writeRejection(exchange, method, hasExpectedPath, accepted);
    return accepted;
  }

  private void writeRejection(
      HttpExchange exchange, String method, boolean hasExpectedPath, boolean accepted)
      throws IOException {
    if (!accepted && !hasExpectedPath) {
      responder.error(exchange, 404, "NOT_FOUND", "The requested resource was not found");
    } else if (!accepted) {
      exchange.getResponseHeaders().set(ALLOW, method);
      responder.error(exchange, 405, "METHOD_NOT_ALLOWED", "The HTTP method is not allowed");
    }
  }

  private HttpHandler safely(ApiOperation operation) {
    return exchange -> {
      try {
        operation.handle(exchange);
      } catch (BadRequestException exception) {
        responder.error(exchange, 400, "BAD_REQUEST", exception.getMessage());
      } catch (OdiAuthenticationException exception) {
        logSanitizedFailure(exception);
        responder.error(exchange, 401, "AUTHENTICATION_FAILED", AUTHENTICATION_ERROR_MESSAGE);
      } catch (OdiConnectionException exception) {
        logSanitizedFailure(exception);
        responder.error(exchange, 400, "CONNECTION_FAILED", CONNECTION_ERROR_MESSAGE);
      } catch (ResourceNotFoundException exception) {
        responder.error(exchange, 404, "NOT_FOUND", exception.getMessage());
      } catch (RuntimeException exception) {
        logUnexpectedFailure(exception);
        responder.error(exchange, 500, "INTERNAL_ERROR", INTERNAL_ERROR_MESSAGE);
      }
    };
  }

  private void logSanitizedFailure(RuntimeException exception) {
    LOGGER.log(
        Level.WARNING,
        "ODI connection failed with cause types {0}",
        sanitizedCauseTypes(exception));
  }

  private void logUnexpectedFailure(RuntimeException exception) {
    LOGGER.log(
        Level.ERROR,
        "Unhandled API failure with cause types {0}; stack locations {1}",
        sanitizedCauseTypes(exception),
        sanitizedStackLocations(exception));
  }

  static String sanitizedCauseTypes(Throwable failure) {
    final StringJoiner types = new StringJoiner(" -> ");
    Throwable current = failure;
    int depth = 0;
    while (current != null && depth < MAX_CAUSE_DEPTH) {
      types.add(current.getClass().getName());
      current = current.getCause();
      depth++;
    }
    return types.toString();
  }

  static String sanitizedStackLocations(Throwable failure) {
    final StringJoiner locations = new StringJoiner(" -> ");
    final StackTraceElement[] stackTrace = failure.getStackTrace();
    final int frameCount = Math.min(stackTrace.length, MAX_STACK_DEPTH);
    for (int index = 0; index < frameCount; index++) {
      final StackTraceElement frame = stackTrace[index];
      locations.add(
          "%s.%s:%d".formatted(frame.getClassName(), frame.getMethodName(), frame.getLineNumber()));
    }
    return locations.toString();
  }

  @FunctionalInterface
  private interface ApiOperation {
    void handle(HttpExchange exchange) throws IOException;
  }

  private record HealthResponse(String status) {}

  private record SessionInfo(String token, RepositoryInfo repository, Instant expiresAt) {}
}
