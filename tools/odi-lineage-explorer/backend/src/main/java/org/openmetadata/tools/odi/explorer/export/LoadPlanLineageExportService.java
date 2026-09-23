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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.openmetadata.tools.odi.explorer.export.LoadPlanLineageExport.Column;
import org.openmetadata.tools.odi.explorer.export.LoadPlanLineageExport.ColumnDerivation;
import org.openmetadata.tools.odi.explorer.export.LoadPlanLineageExport.ColumnReference;
import org.openmetadata.tools.odi.explorer.export.LoadPlanLineageExport.DerivationKind;
import org.openmetadata.tools.odi.explorer.export.LoadPlanLineageExport.Endpoint;
import org.openmetadata.tools.odi.explorer.export.LoadPlanLineageExport.EndpointRole;
import org.openmetadata.tools.odi.explorer.export.LoadPlanLineageExport.LoadPlan;
import org.openmetadata.tools.odi.explorer.export.LoadPlanLineageExport.MappingDefinition;
import org.openmetadata.tools.odi.explorer.export.LoadPlanLineageExport.MappingOccurrence;
import org.openmetadata.tools.odi.explorer.export.LoadPlanLineageExport.OccurrenceResolution;
import org.openmetadata.tools.odi.explorer.export.LoadPlanLineageExport.OccurrenceType;
import org.openmetadata.tools.odi.explorer.export.LoadPlanLineageExport.Producer;
import org.openmetadata.tools.odi.explorer.export.LoadPlanLineageExport.Repository;
import org.openmetadata.tools.odi.explorer.export.LoadPlanLineageExport.Source;
import org.openmetadata.tools.odi.explorer.export.LoadPlanLineageExport.TableEdge;
import org.openmetadata.tools.odi.explorer.export.LoadPlanLineageExport.TableIdentity;
import org.openmetadata.tools.odi.explorer.model.DatastoreIdentity;
import org.openmetadata.tools.odi.explorer.model.LoadPlanDetail;
import org.openmetadata.tools.odi.explorer.model.LoadPlanStep;
import org.openmetadata.tools.odi.explorer.model.MappingColumnDerivation;
import org.openmetadata.tools.odi.explorer.model.MappingColumnLineage;
import org.openmetadata.tools.odi.explorer.model.MappingComponent;
import org.openmetadata.tools.odi.explorer.model.MappingDetail;
import org.openmetadata.tools.odi.explorer.model.MappingEdge;
import org.openmetadata.tools.odi.explorer.model.MappingReference;
import org.openmetadata.tools.odi.explorer.model.PhysicalLocation;
import org.openmetadata.tools.odi.explorer.model.RepositoryInfo;
import org.openmetadata.tools.odi.explorer.model.ScenarioReference;
import org.openmetadata.tools.odi.explorer.model.StepResolution;
import org.openmetadata.tools.odi.explorer.provider.OdiReadProvider;

public final class LoadPlanLineageExportService {
  public static final int MAX_MAPPING_OCCURRENCES = 500;
  public static final int MAX_UNIQUE_MAPPINGS = 500;
  public static final int MAX_COLUMN_EDGES = 250_000;
  public static final int MAX_ENDPOINTS = 10_000;
  public static final int MAX_COLUMNS = 500_000;
  public static final int MAX_TABLE_EDGES = 250_000;
  public static final int MAX_COLUMN_DERIVATIONS = 500_000;

  private static final String SCHEMA_VERSION = "1.0";
  private static final String PRODUCER_NAME = "odi-lineage-explorer";
  private static final String PRODUCER_VERSION = "0.1.0";
  private static final String SOURCE_PRODUCT = "Oracle Data Integrator";
  private static final String SOURCE_PRODUCT_VERSION = "14.1.2.0.0";
  private static final String SOURCE_COMPONENT_TYPE = "DATASTORE_SOURCE";
  private static final String TARGET_COMPONENT_TYPE = "DATASTORE_TARGET";
  private static final String FILE_PREFIX = "odi-lineage_";
  private static final String FILE_SUFFIX = ".json";
  private static final String SAFE_FILE_PART_FALLBACK = "unknown";
  private static final int MAX_FILE_PART_LENGTH = 72;
  private static final Pattern UNSAFE_FILE_CHARACTERS = Pattern.compile("[^A-Za-z0-9._-]+");
  private static final Pattern EDGE_FILE_UNDERSCORES = Pattern.compile("^_+|_+$");

  public LoadPlanLineageExport export(
      OdiReadProvider provider,
      String loadPlanId,
      String contextCode,
      List<String> mappingOccurrenceIds) {
    final List<String> requestedOccurrences = validateRequest(mappingOccurrenceIds);
    final LoadPlanDetail loadPlan = provider.loadPlan(loadPlanId, contextCode);
    final List<LoadPlanStep> occurrences = selectOccurrences(loadPlan, requestedOccurrences);
    final List<MappingDefinition> mappings = readMappings(provider, contextCode, occurrences);
    return createExport(provider.repository(), loadPlan, occurrences, mappings);
  }

  public String fileName(LoadPlanLineageExport export) {
    return FILE_PREFIX
        + safeFilePart(export.source().repository().workRepository())
        + "_"
        + safeFilePart(export.loadPlan().name())
        + "_"
        + safeFilePart(export.loadPlan().contextCode())
        + FILE_SUFFIX;
  }

  private List<String> validateRequest(List<String> occurrenceIds) {
    if (occurrenceIds == null || occurrenceIds.isEmpty()) {
      throw new InvalidExportRequestException("At least one mapping occurrence is required");
    }
    if (occurrenceIds.size() > MAX_MAPPING_OCCURRENCES) {
      throw new InvalidExportRequestException("At most 500 mapping occurrences can be exported");
    }
    validateOccurrenceIds(occurrenceIds);
    return List.copyOf(occurrenceIds);
  }

  private void validateOccurrenceIds(List<String> occurrenceIds) {
    final Set<String> uniqueIds = new LinkedHashSet<>();
    for (final String occurrenceId : occurrenceIds) {
      if (occurrenceId == null || occurrenceId.isBlank()) {
        throw new InvalidExportRequestException("Mapping occurrence IDs must not be blank");
      }
      if (!uniqueIds.add(occurrenceId)) {
        throw new InvalidExportRequestException("Mapping occurrence IDs must be unique");
      }
    }
  }

  private List<LoadPlanStep> selectOccurrences(
      LoadPlanDetail loadPlan, List<String> requestedOccurrenceIds) {
    for (final String occurrenceId : requestedOccurrenceIds) {
      requireExportableOccurrence(loadPlan, occurrenceId);
    }
    final Set<String> selectedIds = Set.copyOf(requestedOccurrenceIds);
    final List<LoadPlanStep> result =
        loadPlan.steps().stream().filter(step -> selectedIds.contains(step.id())).toList();
    if (result.size() != selectedIds.size()) {
      throw new InvalidExportRequestException("Selected mapping occurrence IDs are ambiguous");
    }
    return result;
  }

  private void requireExportableOccurrence(LoadPlanDetail loadPlan, String occurrenceId) {
    final LoadPlanStep step =
        loadPlan.steps().stream()
            .filter(candidate -> occurrenceId.equals(candidate.id()))
            .findFirst()
            .orElseThrow(
                () ->
                    new InvalidExportRequestException(
                        "Mapping occurrence '%s' was not found".formatted(occurrenceId)));
    if (!isExportable(step)) {
      throw new InvalidExportRequestException(
          "Load plan step '%s' is not a resolved mapping occurrence".formatted(occurrenceId));
    }
  }

  private boolean isExportable(LoadPlanStep step) {
    return step.mapping() != null
        && (step.resolution() == StepResolution.RESOLVED
            || step.resolution() == StepResolution.STALE);
  }

  private List<MappingDefinition> readMappings(
      OdiReadProvider provider, String contextCode, List<LoadPlanStep> occurrences) {
    final Map<String, MappingDefinition> result = new LinkedHashMap<>();
    final ExportBudget budget = new ExportBudget();
    for (final LoadPlanStep occurrence : occurrences) {
      final String mappingId = occurrence.mapping().mappingId();
      if (!result.containsKey(mappingId)) {
        final MappingDetail mapping = provider.mapping(mappingId, contextCode);
        requireMatchingMapping(mappingId, contextCode, mapping);
        budget.include(mapping);
        result.put(mappingId, mappingDefinition(mapping));
      }
    }
    return List.copyOf(result.values());
  }

  private void requireMatchingMapping(
      String expectedId, String expectedContextCode, MappingDetail mapping) {
    if (!expectedId.equals(mapping.id()) || !expectedContextCode.equals(mapping.contextCode())) {
      throw new InvalidExportRequestException(
          "Resolved mapping does not match the selected occurrence and context");
    }
  }

  private LoadPlanLineageExport createExport(
      RepositoryInfo repository,
      LoadPlanDetail loadPlan,
      List<LoadPlanStep> occurrences,
      List<MappingDefinition> mappings) {
    return new LoadPlanLineageExport(
        SCHEMA_VERSION,
        new Producer(PRODUCER_NAME, PRODUCER_VERSION),
        new Source(
            SOURCE_PRODUCT,
            SOURCE_PRODUCT_VERSION,
            new Repository(
                repository.name(), repository.masterRepository(), repository.workRepository())),
        new LoadPlan(loadPlan.id(), loadPlan.name(), loadPlan.contextCode()),
        occurrences.stream().map(this::mappingOccurrence).toList(),
        mappings);
  }

  private MappingOccurrence mappingOccurrence(LoadPlanStep step) {
    final ScenarioReference scenario = step.scenario();
    final MappingReference mapping = step.mapping();
    return new MappingOccurrence(
        step.id(),
        step.parentStepId(),
        OccurrenceType.valueOf(step.stepType().name()),
        step.path(),
        step.enabled(),
        step.declaredContextCode(),
        scenario.scenarioName(),
        scenario.scenarioVersion(),
        mapping.mappingId(),
        mapping.mappingName(),
        OccurrenceResolution.valueOf(step.resolution().name()),
        step.resolutionReason());
  }

  private MappingDefinition mappingDefinition(MappingDetail mapping) {
    final MappingDefinition result =
        new MappingDefinition(
            mapping.id(),
            mapping.name(),
            mapping.contextCode(),
            mapping.components().stream().map(this::endpoint).toList(),
            mapping.edges().stream().map(this::tableEdge).toList(),
            columnDerivations(mapping),
            mapping.warnings());
    MappingExportValidator.validate(result);
    return result;
  }

  private Endpoint endpoint(MappingComponent component) {
    final DatastoreIdentity datastore = component.datastore();
    if (datastore == null) {
      throw new InvalidExportRequestException(
          "Mapping endpoint '%s' has no datastore identity".formatted(component.id()));
    }
    return new Endpoint(
        component.id(),
        endpointRole(component),
        component.componentAlias(),
        datastore.datastoreName(),
        tableIdentity(datastore),
        component.columns().stream()
            .map(column -> new Column(column.id(), column.name()))
            .toList());
  }

  private EndpointRole endpointRole(MappingComponent component) {
    final EndpointRole result;
    if (SOURCE_COMPONENT_TYPE.equals(component.componentType())) {
      result = EndpointRole.SOURCE;
    } else if (TARGET_COMPONENT_TYPE.equals(component.componentType())) {
      result = EndpointRole.TARGET;
    } else {
      throw new InvalidExportRequestException(
          "Mapping component '%s' is not a datastore endpoint".formatted(component.id()));
    }
    return result;
  }

  private TableIdentity tableIdentity(DatastoreIdentity datastore) {
    final PhysicalLocation location = datastore.physicalLocation();
    return new TableIdentity(
        location == null ? null : location.technology(),
        location == null ? null : location.dataServer(),
        location == null ? null : location.catalog(),
        location == null ? null : location.schema(),
        datastore.resourceName(),
        datastore.logicalSchema(),
        datastore.modelName(),
        location == null ? null : location.physicalSchema());
  }

  private TableEdge tableEdge(MappingEdge edge) {
    return new TableEdge(edge.fromComponentId(), edge.toComponentId());
  }

  private List<ColumnDerivation> columnDerivations(MappingDetail mapping) {
    final Map<TargetColumn, Set<ColumnReference>> sources =
        sourcesByTarget(mapping.columnLineage());
    final List<ColumnDerivation> result = new ArrayList<>(mapping.columnDerivations().size());
    final Set<TargetColumn> resolvedTargets = new LinkedHashSet<>();
    for (final MappingColumnDerivation derivation : mapping.columnDerivations()) {
      final TargetColumn target =
          new TargetColumn(derivation.targetComponentId(), derivation.targetColumnId());
      requireUniqueTarget(resolvedTargets, target);
      final List<ColumnReference> fromColumns = List.copyOf(sources.getOrDefault(target, Set.of()));
      final DerivationKind kind = DerivationKind.valueOf(derivation.derivationType().name());
      requireValidDerivation(kind, derivation.complete(), fromColumns);
      result.add(
          new ColumnDerivation(target.reference(), fromColumns, kind, derivation.complete()));
    }
    requireEveryLineageTarget(resolvedTargets, sources.keySet());
    return List.copyOf(result);
  }

  private void requireUniqueTarget(Set<TargetColumn> targets, TargetColumn target) {
    if (!targets.add(target)) {
      throw new InvalidExportRequestException("Mapping has duplicate target-column derivations");
    }
  }

  private void requireValidDerivation(
      DerivationKind kind, boolean complete, List<ColumnReference> fromColumns) {
    final boolean valid =
        switch (kind) {
          case SOURCE_COLUMNS -> !fromColumns.isEmpty();
          case UNKNOWN -> fromColumns.isEmpty() && !complete;
          default -> fromColumns.isEmpty() && complete;
        };
    if (!valid) {
      throw new InvalidExportRequestException("Mapping has an invalid column derivation");
    }
  }

  private void requireEveryLineageTarget(
      Set<TargetColumn> resolvedTargets, Set<TargetColumn> lineageTargets) {
    if (!resolvedTargets.containsAll(lineageTargets)) {
      throw new InvalidExportRequestException("Mapping lineage has no target-column derivation");
    }
  }

  private Map<TargetColumn, Set<ColumnReference>> sourcesByTarget(
      List<MappingColumnLineage> lineage) {
    final Map<TargetColumn, Set<ColumnReference>> result = new LinkedHashMap<>();
    for (final MappingColumnLineage edge : lineage) {
      final TargetColumn target = new TargetColumn(edge.toComponentId(), edge.toColumnId());
      result
          .computeIfAbsent(target, ignored -> new LinkedHashSet<>())
          .add(new ColumnReference(edge.fromComponentId(), edge.fromColumnId()));
    }
    return result;
  }

  private String safeFilePart(String value) {
    final String candidate =
        value == null ? "" : UNSAFE_FILE_CHARACTERS.matcher(value).replaceAll("_");
    final String withoutEdgeUnderscores = EDGE_FILE_UNDERSCORES.matcher(candidate).replaceAll("");
    final String nonBlank =
        withoutEdgeUnderscores.isBlank() ? SAFE_FILE_PART_FALLBACK : withoutEdgeUnderscores;
    return nonBlank.substring(0, Math.min(nonBlank.length(), MAX_FILE_PART_LENGTH));
  }

  private record TargetColumn(String endpointId, String columnId) {
    private ColumnReference reference() {
      return new ColumnReference(endpointId, columnId);
    }
  }

  private static final class ExportBudget {
    private long columnDerivations;
    private long columnEdges;
    private long columns;
    private long endpoints;
    private long mappings;
    private long tableEdges;

    private void include(MappingDetail mapping) {
      mappings++;
      endpoints += mapping.components().size();
      columns +=
          mapping.components().stream().mapToLong(component -> component.columns().size()).sum();
      tableEdges += mapping.edges().size();
      columnEdges += mapping.columnLineage().size();
      columnDerivations += mapping.columnDerivations().size();
      requireWithinLimits();
    }

    private void requireWithinLimits() {
      requireLimit(mappings, MAX_UNIQUE_MAPPINGS, "unique-mapping");
      requireLimit(endpoints, MAX_ENDPOINTS, "endpoint");
      requireLimit(columns, MAX_COLUMNS, "column");
      requireLimit(tableEdges, MAX_TABLE_EDGES, "table-edge");
      requireLimit(columnEdges, MAX_COLUMN_EDGES, "column-edge");
      requireLimit(columnDerivations, MAX_COLUMN_DERIVATIONS, "column-derivation");
    }

    private void requireLimit(long count, int limit, String resource) {
      if (count > limit) {
        throw new InvalidExportRequestException(
            "Export exceeds the %d %s limit".formatted(limit, resource));
      }
    }
  }
}
