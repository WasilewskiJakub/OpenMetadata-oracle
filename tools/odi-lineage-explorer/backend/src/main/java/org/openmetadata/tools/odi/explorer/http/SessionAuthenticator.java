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
import java.util.List;
import java.util.Optional;
import org.openmetadata.tools.odi.explorer.session.BoundedSessionStore;
import org.openmetadata.tools.odi.explorer.session.Session;

final class SessionAuthenticator {
  private static final String AUTHORIZATION = "Authorization";
  private static final String BEARER = "Bearer ";

  private final BoundedSessionStore sessions;

  SessionAuthenticator(BoundedSessionStore sessions) {
    this.sessions = sessions;
  }

  Optional<Session> authenticate(HttpExchange exchange) {
    final List<String> values = exchange.getRequestHeaders().get(AUTHORIZATION);
    final String token = bearerToken(values);
    return token == null ? Optional.empty() : sessions.find(token);
  }

  private String bearerToken(List<String> values) {
    String result = null;
    if (values != null && values.size() == 1 && values.getFirst().startsWith(BEARER)) {
      final String token = values.getFirst().substring(BEARER.length());
      result = token.isBlank() ? null : token;
    }
    return result;
  }
}
