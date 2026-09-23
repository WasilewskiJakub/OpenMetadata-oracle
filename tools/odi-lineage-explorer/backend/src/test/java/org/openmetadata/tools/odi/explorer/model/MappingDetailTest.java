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

package org.openmetadata.tools.odi.explorer.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class MappingDetailTest {
  @Test
  void preservesBothPreDerivationConstructors() {
    final MappingDetail withoutWarnings =
        new MappingDetail("id", "name", "DEV", List.of(), List.of(), List.of());
    final MappingDetail withWarnings =
        new MappingDetail("id", "name", "DEV", List.of(), List.of(), List.of(), List.of("warning"));

    assertThat(withoutWarnings.columnDerivations()).isEmpty();
    assertThat(withoutWarnings.warnings()).isEmpty();
    assertThat(withWarnings.columnDerivations()).isEmpty();
    assertThat(withWarnings.warnings()).containsExactly("warning");
  }
}
