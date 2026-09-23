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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonResponderTest {
  @Test
  void rejectsAnOversizedAttachmentBeforeSendingHeaders() throws Exception {
    final JsonResponder responder = new JsonResponder(32);
    final HttpExchange exchange = mock(HttpExchange.class);
    final Headers headers = new Headers();
    when(exchange.getResponseHeaders()).thenReturn(headers);

    assertThatThrownBy(
            () ->
                responder.attachmentJson(
                    exchange, 200, Map.of("payload", "value-too-large-for-limit"), "export.json"))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("Export exceeds the 32-byte response limit");

    assertThat(headers).isEmpty();
    verify(exchange, never()).sendResponseHeaders(anyInt(), anyLong());
  }
}
