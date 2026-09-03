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
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

final class RequestParameters {
  private static final String QUERY_SEPARATOR = "&";
  private static final String VALUE_SEPARATOR = "=";
  private static final String PATH_SEPARATOR = "/";

  private RequestParameters() {}

  static String requiredQuery(HttpExchange exchange, String name) {
    final String value = findQueryValue(exchange.getRequestURI().getRawQuery(), name);
    if (value == null || value.isBlank()) {
      throw new BadRequestException("Query parameter '%s' is required".formatted(name));
    }
    return value;
  }

  static String pathIdentifier(HttpExchange exchange, String prefix) {
    final String rawPath = exchange.getRequestURI().getRawPath();
    final String rawIdentifier =
        rawPath.startsWith(prefix) ? rawPath.substring(prefix.length()) : "";
    if (rawIdentifier.isBlank() || rawIdentifier.contains(PATH_SEPARATOR)) {
      throw new BadRequestException("A single resource identifier is required");
    }
    return decode(rawIdentifier);
  }

  private static String findQueryValue(String rawQuery, String name) {
    String result = null;
    if (rawQuery != null) {
      for (final String pair : rawQuery.split(QUERY_SEPARATOR)) {
        final String[] fields = pair.split(VALUE_SEPARATOR, 2);
        if (fields.length == 2 && name.equals(decode(fields[0]))) {
          result = decode(fields[1]);
        }
      }
    }
    return result;
  }

  private static String decode(String value) {
    return URLDecoder.decode(value, StandardCharsets.UTF_8);
  }
}
