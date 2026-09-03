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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.util.Arrays;
import org.openmetadata.tools.odi.explorer.provider.OdiConnectionRequest;

final class SessionRequestReader {
  static final int MAX_REQUEST_BODY_BYTES = 64 * 1024;

  private static final String INVALID_JSON_MESSAGE = "Request body must be valid JSON";

  private final ObjectMapper objectMapper = new ObjectMapper();

  OdiConnectionRequest read(HttpExchange exchange) throws IOException {
    final byte[] body = exchange.getRequestBody().readNBytes(MAX_REQUEST_BODY_BYTES + 1);
    final OdiConnectionRequest request = parseAndClearBody(body);
    try {
      validate(request);
    } catch (BadRequestException exception) {
      request.close();
      throw exception;
    }
    return request;
  }

  private OdiConnectionRequest parseAndClearBody(byte[] body) {
    OdiConnectionRequest result;
    try {
      requireBoundedBody(body);
      result = parse(body);
    } finally {
      Arrays.fill(body, (byte) 0);
    }
    return result;
  }

  private OdiConnectionRequest parse(byte[] body) {
    OdiConnectionRequest result;
    try {
      result = objectMapper.readValue(body, OdiConnectionRequest.class);
    } catch (IOException exception) {
      throw new BadRequestException(INVALID_JSON_MESSAGE);
    }
    if (result == null) {
      throw new BadRequestException(INVALID_JSON_MESSAGE);
    }
    return result;
  }

  private void validate(OdiConnectionRequest request) {
    requireText(request.jdbcUrl(), "jdbcUrl");
    requireText(request.repositoryUsername(), "repositoryUsername");
    requirePassword(request.hasRepositoryPassword(), "repositoryPassword");
    requireText(request.workRepositoryName(), "workRepositoryName");
    requireText(request.odiUsername(), "odiUsername");
    requirePassword(request.hasOdiPassword(), "odiPassword");
  }

  private void requireBoundedBody(byte[] body) {
    if (body.length > MAX_REQUEST_BODY_BYTES) {
      throw new BadRequestException("Request body exceeds the 64 KiB limit");
    }
  }

  private void requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw requiredField(field);
    }
  }

  private void requirePassword(boolean hasPassword, String field) {
    if (!hasPassword) {
      throw requiredField(field);
    }
  }

  private BadRequestException requiredField(String field) {
    return new BadRequestException("Field '%s' is required".formatted(field));
  }
}
