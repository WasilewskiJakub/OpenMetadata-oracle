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

package org.openmetadata.tools.odi.explorer.export;

import java.util.List;

public record LoadPlanLineageExport(
    String schemaVersion,
    Producer producer,
    Source source,
    LoadPlan loadPlan,
    List<MappingOccurrence> mappingOccurrences,
    List<MappingDefinition> mappings) {
  public LoadPlanLineageExport {
    mappingOccurrences = List.copyOf(mappingOccurrences);
    mappings = List.copyOf(mappings);
  }

  public record Producer(String name, String version) {}

  public record Source(String product, String productVersion, Repository repository) {}

  public record Repository(String name, String masterRepository, String workRepository) {}

  public record LoadPlan(String id, String name, String contextCode) {}

  public record MappingOccurrence(
      String occurrenceId,
      String parentOccurrenceId,
      OccurrenceType stepType,
      List<String> path,
      boolean enabled,
      String declaredContextCode,
      String scenarioName,
      String scenarioVersion,
      String mappingId,
      String mappingName,
      OccurrenceResolution resolution,
      String resolutionReason) {
    public MappingOccurrence {
      path = List.copyOf(path);
    }
  }

  public enum OccurrenceType {
    RUN_SCENARIO,
    PACKAGE_MAPPING
  }

  public enum OccurrenceResolution {
    RESOLVED,
    STALE,
    UNRESOLVED,
    OUT_OF_SCOPE
  }

  public record MappingDefinition(
      String id,
      String name,
      String contextCode,
      List<Endpoint> endpoints,
      List<TableEdge> tableEdges,
      List<ColumnDerivation> columnDerivations,
      List<String> warnings) {
    public MappingDefinition {
      endpoints = List.copyOf(endpoints);
      tableEdges = List.copyOf(tableEdges);
      columnDerivations = List.copyOf(columnDerivations);
      warnings = List.copyOf(warnings);
    }
  }

  public record Endpoint(
      String endpointId,
      EndpointRole role,
      String alias,
      String datastoreName,
      TableIdentity identity,
      List<Column> columns) {
    public Endpoint {
      columns = List.copyOf(columns);
    }
  }

  public enum EndpointRole {
    SOURCE,
    TARGET
  }

  public record TableIdentity(
      String technology,
      String dataServer,
      String catalog,
      String schema,
      String resourceName,
      String logicalSchema,
      String modelName,
      String physicalSchema) {}

  public record TableEdge(String fromEndpointId, String toEndpointId) {}

  public record Column(String id, String name) {}

  public record ColumnReference(String endpointId, String columnId) {}

  public record ColumnDerivation(
      ColumnReference toColumn,
      List<ColumnReference> fromColumns,
      DerivationKind kind,
      boolean complete) {
    public ColumnDerivation {
      fromColumns = List.copyOf(fromColumns);
    }
  }

  public enum DerivationKind {
    SOURCE_COLUMNS,
    NULL_LITERAL,
    SOURCELESS_EXPRESSION,
    UNMAPPED,
    INACTIVE,
    UNKNOWN
  }
}
