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

import java.util.List;

public record MappingDetail(
    String id,
    String name,
    String contextCode,
    List<MappingComponent> components,
    List<MappingEdge> edges,
    List<MappingColumnLineage> columnLineage,
    List<String> warnings) {
  public MappingDetail(
      String id,
      String name,
      String contextCode,
      List<MappingComponent> components,
      List<MappingEdge> edges,
      List<MappingColumnLineage> columnLineage) {
    this(id, name, contextCode, components, edges, columnLineage, List.of());
  }

  public MappingDetail {
    components = List.copyOf(components);
    edges = List.copyOf(edges);
    columnLineage = List.copyOf(columnLineage);
    warnings = List.copyOf(warnings);
  }
}
