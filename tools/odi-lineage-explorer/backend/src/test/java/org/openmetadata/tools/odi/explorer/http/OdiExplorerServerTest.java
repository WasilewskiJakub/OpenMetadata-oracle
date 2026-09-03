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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.openmetadata.tools.odi.explorer.model.RepositoryInfo;
import org.openmetadata.tools.odi.explorer.provider.DemoOdiReadProvider;
import org.openmetadata.tools.odi.explorer.provider.OdiAuthenticationException;
import org.openmetadata.tools.odi.explorer.provider.OdiConnectionException;
import org.openmetadata.tools.odi.explorer.provider.OdiConnectionRequest;
import org.openmetadata.tools.odi.explorer.provider.OdiProviderFactory;
import org.openmetadata.tools.odi.explorer.provider.OdiReadProvider;
import org.openmetadata.tools.odi.explorer.session.BoundedSessionStore;

class OdiExplorerServerTest {
  private static final String AUTHORIZATION = "Authorization";
  private static final String BEARER = "Bearer ";
  private static final String CONTEXT_QUERY = "?contextCode=DEV";
  private static final String SECRET_FAILURE_MESSAGE = "secret repository password";
  private static final int MAX_REQUEST_BODY_BYTES = 64 * 1024;

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final HttpClient httpClient = HttpClient.newHttpClient();
  private final AtomicReference<OdiConnectionRequest> capturedRequest = new AtomicReference<>();
  private OdiExplorerServer server;
  private BoundedSessionStore sessions;
  private URI baseUri;

  @BeforeEach
  void startServer() throws IOException {
    sessions = new BoundedSessionStore(8, Duration.ofMinutes(30), Clock.systemUTC());
    server =
        OdiExplorerServer.start(
            0, sessions, DemoOdiReadProvider::new, capturingFactory(capturedRequest));
    baseUri = URI.create("http://127.0.0.1:" + server.port());
  }

  @AfterEach
  void stopServer() {
    server.close();
  }

  @Test
  void exposesHealthWithoutAuthentication() throws Exception {
    final HttpResponse<String> response = send("GET", "/api/health", null);

    assertEquals(200, response.statusCode());
    assertEquals("UP", json(response).get("status").asText());
  }

  @Test
  void createsAnEphemeralDemoSessionWithoutReturningCredentials() throws Exception {
    final HttpResponse<String> response = send("POST", "/api/sessions/demo", null);
    final JsonNode body = json(response);

    assertEquals(201, response.statusCode());
    assertFalse(body.get("token").asText().isBlank());
    assertEquals("ODI_DEMO", body.get("repository").get("name").asText());
    assertTrue(body.hasNonNull("expiresAt"));
    assertFalse(body.has("password"));
  }

  @Test
  void createsARealSessionWithoutReturningCredentials() throws Exception {
    final HttpResponse<String> response = sendJson("POST", "/api/sessions", validSessionRequest());
    final JsonNode body = json(response);

    assertEquals(201, response.statusCode());
    assertFalse(body.get("token").asText().isBlank());
    assertEquals("ODI_DEMO", body.get("repository").get("name").asText());
    assertTrue(body.hasNonNull("expiresAt"));
    assertFalse(response.body().contains("repository-secret"));
    assertFalse(response.body().contains("odi-secret"));
  }

  @Test
  void passesConnectionFieldsToTheProviderFactoryAndClearsPasswordsAfterwards() throws Exception {
    final HttpResponse<String> response = sendJson("POST", "/api/sessions", validSessionRequest());
    final OdiConnectionRequest request = capturedRequest.get();

    assertEquals(201, response.statusCode());
    assertNotNull(request);
    assertEquals("jdbc:oracle:thin:@localhost:1521/ODIPDB", request.jdbcUrl());
    assertEquals("ODI_REPO", request.repositoryUsername());
    assertEquals("WORKREP", request.workRepositoryName());
    assertEquals("SUPERVISOR", request.odiUsername());
    assertTrue(Boolean.TRUE.equals(request.withRepositoryPassword(this::allCleared)));
    assertTrue(Boolean.TRUE.equals(request.withOdiPassword(this::allCleared)));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "jdbcUrl",
        "repositoryUsername",
        "repositoryPassword",
        "workRepositoryName",
        "odiUsername",
        "odiPassword"
      })
  void rejectsMissingConnectionFields(String field) throws Exception {
    final Map<String, Object> request = validSessionRequest();
    request.remove(field);

    final HttpResponse<String> response = sendJson("POST", "/api/sessions", request);

    assertEquals(400, response.statusCode());
    assertEquals("BAD_REQUEST", json(response).get("code").asText());
    assertFalse(response.body().contains("repository-secret"));
    assertFalse(response.body().contains("odi-secret"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "jdbcUrl",
        "repositoryUsername",
        "repositoryPassword",
        "workRepositoryName",
        "odiUsername",
        "odiPassword"
      })
  void rejectsBlankConnectionFields(String field) throws Exception {
    final Map<String, Object> request = validSessionRequest();
    request.put(field, "   ");

    final HttpResponse<String> response = sendJson("POST", "/api/sessions", request);

    assertEquals(400, response.statusCode());
    assertEquals("BAD_REQUEST", json(response).get("code").asText());
  }

  @Test
  void rejectsMalformedSessionJson() throws Exception {
    final HttpResponse<String> response = sendJsonText("POST", "/api/sessions", "{not-json");

    assertEquals(400, response.statusCode());
    assertEquals("BAD_REQUEST", json(response).get("code").asText());
  }

  @Test
  void rejectsJsonNullSessionRequest() throws Exception {
    final HttpResponse<String> response = sendJsonText("POST", "/api/sessions", "null");

    assertEquals(400, response.statusCode());
    assertEquals("BAD_REQUEST", json(response).get("code").asText());
  }

  @Test
  void rejectsSessionRequestLargerThanTheBoundedBodyLimit() throws Exception {
    final String oversizedBody = "x".repeat(MAX_REQUEST_BODY_BYTES + 1);

    final HttpResponse<String> response = sendJsonText("POST", "/api/sessions", oversizedBody);

    assertEquals(400, response.statusCode());
    assertEquals("BAD_REQUEST", json(response).get("code").asText());
  }

  @Test
  void sanitizesRepositoryAuthenticationFailure() throws Exception {
    restartWithFactory(
        request -> {
          throw new OdiAuthenticationException(SECRET_FAILURE_MESSAGE);
        });

    final HttpResponse<String> response = sendJson("POST", "/api/sessions", validSessionRequest());
    final JsonNode body = json(response);

    assertEquals(401, response.statusCode());
    assertEquals("AUTHENTICATION_FAILED", body.get("code").asText());
    assertEquals("Authentication to the ODI repository failed", body.get("message").asText());
    assertFalse(response.body().contains(SECRET_FAILURE_MESSAGE));
  }

  @Test
  void diagnosticCauseChainContainsOnlyTypesAndNeverExceptionMessages() {
    final OdiConnectionException failure =
        new OdiConnectionException(
            SECRET_FAILURE_MESSAGE, new IllegalStateException("another secret value"));

    final String diagnostic = ExplorerApi.sanitizedCauseTypes(failure);

    assertEquals(
        "org.openmetadata.tools.odi.explorer.provider.OdiConnectionException"
            + " -> java.lang.IllegalStateException",
        diagnostic);
    assertFalse(diagnostic.contains(SECRET_FAILURE_MESSAGE));
    assertFalse(diagnostic.contains("another secret value"));
  }

  @Test
  void diagnosticStackContainsOnlyCodeLocationsAndNeverExceptionMessages() {
    final OdiConnectionException failure = new OdiConnectionException(SECRET_FAILURE_MESSAGE);
    failure.setStackTrace(
        new StackTraceElement[] {
          new StackTraceElement("oracle.odi.domain.Mapping", "read", "Mapping.java", 42),
          new StackTraceElement("org.openmetadata.Mapper", "map", "Mapper.java", 87)
        });

    final String diagnostic = ExplorerApi.sanitizedStackLocations(failure);

    assertEquals("oracle.odi.domain.Mapping.read:42 -> org.openmetadata.Mapper.map:87", diagnostic);
    assertFalse(diagnostic.contains(SECRET_FAILURE_MESSAGE));
  }

  @Test
  void clearsPasswordsAfterFailedProviderCreation() throws Exception {
    restartWithFactory(
        request -> {
          capturedRequest.set(request);
          throw new OdiAuthenticationException(SECRET_FAILURE_MESSAGE);
        });

    final HttpResponse<String> response = sendJson("POST", "/api/sessions", validSessionRequest());
    final OdiConnectionRequest request = capturedRequest.get();

    assertEquals(401, response.statusCode());
    assertNotNull(request);
    assertTrue(Boolean.TRUE.equals(request.withRepositoryPassword(this::allCleared)));
    assertTrue(Boolean.TRUE.equals(request.withOdiPassword(this::allCleared)));
  }

  @Test
  void sanitizesRepositoryConnectionFailure() throws Exception {
    restartWithFactory(
        request -> {
          throw new OdiConnectionException(SECRET_FAILURE_MESSAGE);
        });

    final HttpResponse<String> response = sendJson("POST", "/api/sessions", validSessionRequest());
    final JsonNode body = json(response);

    assertEquals(400, response.statusCode());
    assertEquals("CONNECTION_FAILED", body.get("code").asText());
    assertEquals("Could not connect to the requested ODI repository", body.get("message").asText());
    assertFalse(response.body().contains(SECRET_FAILURE_MESSAGE));
  }

  @Test
  void rejectsProtectedResourcesWithoutBearerToken() throws Exception {
    final HttpResponse<String> response = send("GET", "/api/contexts", null);

    assertEquals(401, response.statusCode());
    assertEquals("UNAUTHORIZED", json(response).get("code").asText());
  }

  @Test
  void returnsContextsAndLoadPlansForAuthenticatedSession() throws Exception {
    final String token = createSessionToken();

    final JsonNode contexts = json(send("GET", "/api/contexts", token));
    final JsonNode loadPlans = json(send("GET", "/api/load-plans", token));

    assertEquals(Set.of("DEV", "PROD"), textValues(contexts, "code"));
    assertTrue(contexts.get(0).get("isDefault").asBoolean());
    assertFalse(contexts.get(1).get("isDefault").asBoolean());
    assertEquals("Daily Sales Load", loadPlans.get(0).get("name").asText());
  }

  @Test
  void resolvesScenarioMappingsForSelectedLoadPlanContext() throws Exception {
    final String token = createSessionToken();

    final HttpResponse<String> response =
        send("GET", "/api/load-plans/lp-sales" + CONTEXT_QUERY, token);
    final JsonNode body = json(response);
    final JsonNode mappingStep = firstWithField(body.get("steps"), "mappingId");

    assertEquals(200, response.statusCode());
    assertEquals("DEV", body.get("contextCode").asText());
    assertEquals("map-orders", mappingStep.get("mappingId").asText());
    assertEquals("SCEN_LOAD_ORDERS", mappingStep.get("scenarioName").asText());
    assertEquals("RESOLVED", mappingStep.get("resolution").asText());
  }

  @Test
  void keepsMappingAliasesSeparateFromCanonicalDatabaseIdentity() throws Exception {
    final String token = createSessionToken();

    final HttpResponse<String> response =
        send("GET", "/api/mappings/map-orders" + CONTEXT_QUERY, token);
    final JsonNode component = json(response).get("components").get(0);

    assertEquals(200, response.statusCode());
    assertEquals("ORDERS_SRC", component.get("componentAlias").asText());
    assertEquals("Orders", component.get("datastoreName").asText());
    assertEquals("Sales Source Model", component.get("modelName").asText());
    assertEquals("ORDERS", component.get("resourceName").asText());
    assertEquals("SALES_LOGICAL", component.get("logicalSchema").asText());
    assertEquals("oracle-dev", component.get("physicalLocation").get("dataServer").asText());
    assertEquals("ODIPDB", component.get("physicalLocation").get("catalog").asText());
    assertEquals("SALES_DEV", component.get("physicalLocation").get("schema").asText());
  }

  @Test
  void invalidatesSessionOnLogout() throws Exception {
    final String token = createSessionToken();

    final HttpResponse<String> logout = send("DELETE", "/api/sessions/current", token);
    final HttpResponse<String> contexts = send("GET", "/api/contexts", token);

    assertEquals(204, logout.statusCode());
    assertEquals(401, contexts.statusCode());
  }

  @Test
  void rejectsLoadPlanDetailWithoutContext() throws Exception {
    final String token = createSessionToken();

    final HttpResponse<String> response = send("GET", "/api/load-plans/lp-sales", token);

    assertEquals(400, response.statusCode());
    assertEquals("BAD_REQUEST", json(response).get("code").asText());
  }

  @Test
  void returnsNotFoundForUnknownMapping() throws Exception {
    final String token = createSessionToken();

    final HttpResponse<String> response =
        send("GET", "/api/mappings/unknown" + CONTEXT_QUERY, token);

    assertEquals(404, response.statusCode());
    assertEquals("NOT_FOUND", json(response).get("code").asText());
  }

  @Test
  void rejectsUnsupportedHttpMethod() throws Exception {
    final String token = createSessionToken();

    final HttpResponse<String> response = send("POST", "/api/contexts", token);

    assertEquals(405, response.statusCode());
    assertEquals("GET", response.headers().firstValue("Allow").orElseThrow());
  }

  @Test
  void sanitizesUnexpectedRuntimeFailure() throws Exception {
    restartWithDemoFactory(
        () -> {
          throw new IllegalStateException(SECRET_FAILURE_MESSAGE);
        });

    final HttpResponse<String> response = send("POST", "/api/sessions/demo", null);
    final JsonNode body = json(response);

    assertEquals(500, response.statusCode());
    assertEquals("INTERNAL_ERROR", body.get("code").asText());
    assertEquals("An unexpected server error occurred", body.get("message").asText());
    assertFalse(response.body().contains(SECRET_FAILURE_MESSAGE));
  }

  @Test
  void removesDemoSessionWhenRepositoryMetadataCannotBeRead() throws Exception {
    final OdiReadProvider provider = mock(OdiReadProvider.class);
    when(provider.repository()).thenThrow(new IllegalStateException(SECRET_FAILURE_MESSAGE));
    restartWithDemoFactory(() -> provider);

    final HttpResponse<String> response = send("POST", "/api/sessions/demo", null);

    assertEquals(500, response.statusCode());
    assertEquals(0, sessions.size());
    verify(provider).close();
    assertFalse(response.body().contains(SECRET_FAILURE_MESSAGE));
  }

  @Test
  void removesRealSessionWhenResponseSerializationFails() throws Exception {
    final OdiReadProvider provider = mock(OdiReadProvider.class);
    final RepositoryInfo repository = mock(RepositoryInfo.class);
    when(provider.repository()).thenReturn(repository);
    when(repository.name()).thenThrow(new IllegalStateException(SECRET_FAILURE_MESSAGE));
    doThrow(new IllegalStateException("another sensitive failure")).when(provider).close();
    restartWithFactory(request -> provider);

    assertThrows(IOException.class, () -> sendJson("POST", "/api/sessions", validSessionRequest()));

    assertEquals(0, sessions.size());
    verify(provider).close();
  }

  @Test
  void closesProviderWhenSessionStoreRejectsCreation() throws Exception {
    final OdiReadProvider provider = mock(OdiReadProvider.class);
    doThrow(new IllegalStateException("sensitive close failure")).when(provider).close();
    server.close();
    sessions = new BoundedSessionStore(8, Duration.ofMinutes(30), Clock.systemUTC());
    sessions.close();
    server = OdiExplorerServer.start(0, sessions, DemoOdiReadProvider::new, request -> provider);
    baseUri = URI.create("http://127.0.0.1:" + server.port());

    final HttpResponse<String> response = sendJson("POST", "/api/sessions", validSessionRequest());

    assertEquals(500, response.statusCode());
    assertEquals(0, sessions.size());
    verify(provider).close();
    assertFalse(response.body().contains("sensitive close failure"));
  }

  private String createSessionToken() throws Exception {
    final HttpResponse<String> response = send("POST", "/api/sessions/demo", null);
    return json(response).get("token").asText();
  }

  private OdiProviderFactory capturingFactory(
      AtomicReference<OdiConnectionRequest> requestReference) {
    return request -> {
      requestReference.set(request);
      return new DemoOdiReadProvider();
    };
  }

  private void restartWithFactory(OdiProviderFactory providerFactory) throws IOException {
    restart(DemoOdiReadProvider::new, providerFactory);
  }

  private void restartWithDemoFactory(Supplier<OdiReadProvider> demoFactory) throws IOException {
    restart(demoFactory, capturingFactory(capturedRequest));
  }

  private void restart(Supplier<OdiReadProvider> demoFactory, OdiProviderFactory providerFactory)
      throws IOException {
    server.close();
    sessions = new BoundedSessionStore(8, Duration.ofMinutes(30), Clock.systemUTC());
    server = OdiExplorerServer.start(0, sessions, demoFactory, providerFactory);
    baseUri = URI.create("http://127.0.0.1:" + server.port());
  }

  private HttpResponse<String> send(String method, String path, String token)
      throws IOException, InterruptedException {
    final HttpRequest.Builder request = HttpRequest.newBuilder(baseUri.resolve(path));
    if (token != null) {
      request.header(AUTHORIZATION, BEARER + token);
    }
    request.method(method, HttpRequest.BodyPublishers.noBody());
    return httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> sendJson(String method, String path, Object body)
      throws IOException, InterruptedException {
    return sendJsonText(method, path, objectMapper.writeValueAsString(body));
  }

  private HttpResponse<String> sendJsonText(String method, String path, String body)
      throws IOException, InterruptedException {
    final HttpRequest request =
        HttpRequest.newBuilder(baseUri.resolve(path))
            .header("Content-Type", "application/json")
            .method(method, HttpRequest.BodyPublishers.ofString(body))
            .build();
    return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private Map<String, Object> validSessionRequest() {
    final Map<String, Object> request = new LinkedHashMap<>();
    request.put("jdbcUrl", "jdbc:oracle:thin:@localhost:1521/ODIPDB");
    request.put("repositoryUsername", "ODI_REPO");
    request.put("repositoryPassword", "repository-secret");
    request.put("workRepositoryName", "WORKREP");
    request.put("odiUsername", "SUPERVISOR");
    request.put("odiPassword", "odi-secret");
    return request;
  }

  private boolean allCleared(char[] value) {
    return value.length > 0 && Arrays.equals(value, new char[value.length]);
  }

  private JsonNode json(HttpResponse<String> response) throws IOException {
    return objectMapper.readTree(response.body());
  }

  private Set<String> textValues(JsonNode array, String field) {
    final Set<String> values = new HashSet<>();
    array.forEach(item -> values.add(item.get(field).asText()));
    return Set.copyOf(values);
  }

  private JsonNode firstWithField(JsonNode array, String field) {
    JsonNode result = null;
    for (final JsonNode item : array) {
      if (result == null && item.has(field)) {
        result = item;
      }
    }
    assertNotNull(result);
    return result;
  }
}
