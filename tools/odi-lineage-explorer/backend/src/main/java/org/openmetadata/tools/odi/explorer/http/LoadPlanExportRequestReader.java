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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.openmetadata.tools.odi.explorer.export.LoadPlanLineageExportService;

final class LoadPlanExportRequestReader {
  static final int MAX_REQUEST_BODY_BYTES = 64 * 1024;

  private static final String INVALID_JSON_MESSAGE = "Request body must be valid JSON";
  private final ObjectMapper objectMapper = new ObjectMapper();

  LoadPlanExportRequest read(HttpExchange exchange) throws IOException {
    final byte[] body = exchange.getRequestBody().readNBytes(MAX_REQUEST_BODY_BYTES + 1);
    final LoadPlanExportRequest request = parseAndClearBody(body);
    validate(request);
    return copy(request);
  }

  private LoadPlanExportRequest parseAndClearBody(byte[] body) {
    LoadPlanExportRequest result;
    try {
      requireBoundedBody(body);
      result = parse(body);
    } finally {
      Arrays.fill(body, (byte) 0);
    }
    return result;
  }

  private LoadPlanExportRequest parse(byte[] body) {
    LoadPlanExportRequest result;
    try {
      result = objectMapper.readValue(body, LoadPlanExportRequest.class);
    } catch (IOException exception) {
      throw new BadRequestException(INVALID_JSON_MESSAGE);
    }
    if (result == null) {
      throw new BadRequestException(INVALID_JSON_MESSAGE);
    }
    return result;
  }

  private void validate(LoadPlanExportRequest request) {
    requireText(request.loadPlanId(), "loadPlanId");
    requireText(request.contextCode(), "contextCode");
    requireOccurrences(request.mappingOccurrenceIds());
  }

  private void requireOccurrences(List<String> occurrenceIds) {
    if (occurrenceIds == null || occurrenceIds.isEmpty()) {
      throw new BadRequestException("At least one mapping occurrence is required");
    }
    if (occurrenceIds.size() > LoadPlanLineageExportService.MAX_MAPPING_OCCURRENCES) {
      throw new BadRequestException("At most 500 mapping occurrences can be exported");
    }
    requireUniqueNonBlankOccurrences(occurrenceIds);
  }

  private void requireUniqueNonBlankOccurrences(List<String> occurrenceIds) {
    final Set<String> uniqueIds = new HashSet<>();
    for (final String occurrenceId : occurrenceIds) {
      requireText(occurrenceId, "mappingOccurrenceIds");
      if (!uniqueIds.add(occurrenceId)) {
        throw new BadRequestException("Mapping occurrence IDs must be unique");
      }
    }
  }

  private void requireBoundedBody(byte[] body) {
    if (body.length > MAX_REQUEST_BODY_BYTES) {
      throw new BadRequestException("Request body exceeds the 64 KiB limit");
    }
  }

  private void requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new BadRequestException("Field '%s' is required".formatted(field));
    }
  }

  private LoadPlanExportRequest copy(LoadPlanExportRequest request) {
    return new LoadPlanExportRequest(
        request.loadPlanId(), request.contextCode(), List.copyOf(request.mappingOccurrenceIds()));
  }
}
