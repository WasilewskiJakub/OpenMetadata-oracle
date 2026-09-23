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
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.io.ByteArrayOutputStream;
import org.junit.jupiter.api.Test;

class BoundedByteArrayOutputStreamTest {
  @Test
  void growsItsBufferWithoutExceedingTheConfiguredLimit() throws Exception {
    final byte[] payload = new byte[9_000];
    final BoundedByteArrayOutputStream bounded = new BoundedByteArrayOutputStream(10_000);
    final ByteArrayOutputStream destination = new ByteArrayOutputStream();

    bounded.write(payload);
    bounded.writeTo(destination);

    assertThat(bounded.size()).isEqualTo(payload.length);
    assertThat(destination.size()).isEqualTo(payload.length);
  }

  @Test
  void rejectsANonPositiveLimit() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new BoundedByteArrayOutputStream(0))
        .withMessage("Output limit must be positive");
  }
}
