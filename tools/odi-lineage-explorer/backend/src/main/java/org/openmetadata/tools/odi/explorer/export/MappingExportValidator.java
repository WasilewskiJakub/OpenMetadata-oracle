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

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.openmetadata.tools.odi.explorer.export.LoadPlanLineageExport.Column;
import org.openmetadata.tools.odi.explorer.export.LoadPlanLineageExport.ColumnDerivation;
import org.openmetadata.tools.odi.explorer.export.LoadPlanLineageExport.ColumnReference;
import org.openmetadata.tools.odi.explorer.export.LoadPlanLineageExport.Endpoint;
import org.openmetadata.tools.odi.explorer.export.LoadPlanLineageExport.EndpointRole;
import org.openmetadata.tools.odi.explorer.export.LoadPlanLineageExport.MappingDefinition;
import org.openmetadata.tools.odi.explorer.export.LoadPlanLineageExport.TableEdge;

final class MappingExportValidator {
  private MappingExportValidator() {}

  static void validate(MappingDefinition mapping) {
    final Map<String, EndpointIndex> endpoints = indexEndpoints(mapping);
    validateTableEdges(mapping, endpoints);
    validateColumnDerivations(mapping, endpoints);
  }

  private static Map<String, EndpointIndex> indexEndpoints(MappingDefinition mapping) {
    final Map<String, EndpointIndex> result = new LinkedHashMap<>();
    final Set<String> endpointIds = new HashSet<>();
    final Set<String> columnIds = new HashSet<>();
    for (final Endpoint endpoint : mapping.endpoints()) {
      if (!endpointIds.add(endpoint.endpointId())) {
        throw new InvalidExportRequestException("Mapping endpoint IDs must be unique");
      }
      final EndpointIndex index = indexEndpoint(endpoint, columnIds);
      result.put(endpoint.endpointId(), index);
    }
    return result;
  }

  private static EndpointIndex indexEndpoint(Endpoint endpoint, Set<String> allColumnIds) {
    final Set<String> endpointColumnIds = new HashSet<>();
    for (final Column column : endpoint.columns()) {
      if (!endpointColumnIds.add(column.id()) || !allColumnIds.add(column.id())) {
        throw new InvalidExportRequestException("Mapping column IDs must be unique");
      }
    }
    return new EndpointIndex(endpoint.role(), Set.copyOf(endpointColumnIds));
  }

  private static void validateTableEdges(
      MappingDefinition mapping, Map<String, EndpointIndex> endpoints) {
    final Set<TableEdge> uniqueEdges = new HashSet<>();
    for (final TableEdge edge : mapping.tableEdges()) {
      if (!uniqueEdges.add(edge)) {
        throw new InvalidExportRequestException("Mapping table edges must be unique");
      }
      requireRole(endpoints, edge.fromEndpointId(), EndpointRole.SOURCE, "Table edge");
      requireRole(endpoints, edge.toEndpointId(), EndpointRole.TARGET, "Table edge");
    }
  }

  private static void validateColumnDerivations(
      MappingDefinition mapping, Map<String, EndpointIndex> endpoints) {
    final Set<ColumnReference> targets = new HashSet<>();
    for (final ColumnDerivation derivation : mapping.columnDerivations()) {
      if (!targets.add(derivation.toColumn())) {
        throw new InvalidExportRequestException("Mapping has duplicate target-column derivations");
      }
      requireColumn(endpoints, derivation.toColumn(), EndpointRole.TARGET);
      for (final ColumnReference source : derivation.fromColumns()) {
        requireColumn(endpoints, source, EndpointRole.SOURCE);
      }
    }
  }

  private static void requireColumn(
      Map<String, EndpointIndex> endpoints, ColumnReference reference, EndpointRole expectedRole) {
    final EndpointIndex endpoint =
        requireRole(endpoints, reference.endpointId(), expectedRole, "Column");
    if (!endpoint.columnIds().contains(reference.columnId())) {
      throw new InvalidExportRequestException(
          "Column reference '%s' was not found on endpoint '%s'"
              .formatted(reference.columnId(), reference.endpointId()));
    }
  }

  private static EndpointIndex requireRole(
      Map<String, EndpointIndex> endpoints,
      String endpointId,
      EndpointRole expectedRole,
      String referenceType) {
    final EndpointIndex result = endpoints.get(endpointId);
    if (result == null) {
      throw new InvalidExportRequestException(
          "Mapping endpoint '%s' was not found".formatted(endpointId));
    }
    if (result.role() != expectedRole) {
      throw new InvalidExportRequestException(
          "%s must connect a SOURCE endpoint to a TARGET endpoint".formatted(referenceType));
    }
    return result;
  }

  private record EndpointIndex(EndpointRole role, Set<String> columnIds) {}
}
