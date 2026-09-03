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

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

final class JsonResponder {
  private static final String CONTENT_TYPE = "Content-Type";
  private static final String JSON_CONTENT_TYPE = "application/json; charset=utf-8";
  private static final String CACHE_CONTROL = "Cache-Control";
  private static final String NO_STORE = "no-store";

  private final ObjectMapper objectMapper;

  JsonResponder() {
    objectMapper = new ObjectMapper();
    objectMapper.registerModule(new JavaTimeModule());
    objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    objectMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
  }

  void json(HttpExchange exchange, int status, Object body) throws IOException {
    final byte[] bytes = objectMapper.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
    setResponseHeaders(exchange);
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream output = exchange.getResponseBody()) {
      output.write(bytes);
    }
  }

  void error(HttpExchange exchange, int status, String code, String message) throws IOException {
    json(exchange, status, new ErrorResponse(code, message));
  }

  void empty(HttpExchange exchange, int status) throws IOException {
    exchange.getResponseHeaders().set(CACHE_CONTROL, NO_STORE);
    exchange.sendResponseHeaders(status, -1);
    exchange.close();
  }

  private void setResponseHeaders(HttpExchange exchange) {
    exchange.getResponseHeaders().set(CONTENT_TYPE, JSON_CONTENT_TYPE);
    exchange.getResponseHeaders().set(CACHE_CONTROL, NO_STORE);
  }

  private record ErrorResponse(String code, String message) {}
}
