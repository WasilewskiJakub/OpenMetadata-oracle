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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.openmetadata.tools.odi.explorer.model.ContextInfo;
import org.openmetadata.tools.odi.explorer.model.DatastoreIdentity;
import org.openmetadata.tools.odi.explorer.model.LoadPlanDetail;
import org.openmetadata.tools.odi.explorer.model.LoadPlanStep;
import org.openmetadata.tools.odi.explorer.model.LoadPlanStepType;
import org.openmetadata.tools.odi.explorer.model.LoadPlanSummary;
import org.openmetadata.tools.odi.explorer.model.MappingColumn;
import org.openmetadata.tools.odi.explorer.model.MappingColumnDerivation;
import org.openmetadata.tools.odi.explorer.model.MappingColumnDerivationType;
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

class LoadPlanLineageExportServiceTest {
  @Test
  void preservesPlanOccurrenceOrderAndDefinesRepeatedMappingOnlyOnce() {
    final LoadPlanLineageExportService service = new LoadPlanLineageExportService();
    final FixtureProvider provider = new FixtureProvider(mappingDetail(List.of(columnEdge())));

    final LoadPlanLineageExport result =
        service.export(provider, "load-plan", "DEV", List.of("second", "first"));

    assertThat(result.mappingOccurrences())
        .extracting(LoadPlanLineageExport.MappingOccurrence::occurrenceId)
        .containsExactly("first", "second");
    assertThat(result.mappings()).hasSize(1);
    assertThat(result.mappings().getFirst().columnDerivations().getFirst().fromColumns())
        .containsExactly(new LoadPlanLineageExport.ColumnReference("source", "source::ID"));
  }

  @Test
  void rejectsAnExportWhoseUniqueMappingsExceedTheGlobalColumnEdgeLimit() {
    final MappingColumnLineage edge = columnEdge();
    final List<MappingColumnLineage> oversized =
        Collections.nCopies(LoadPlanLineageExportService.MAX_COLUMN_EDGES + 1, edge);
    final FixtureProvider provider = new FixtureProvider(mappingDetail(oversized));

    assertThatThrownBy(
            () ->
                new LoadPlanLineageExportService()
                    .export(provider, "load-plan", "DEV", List.of("first")))
        .isInstanceOf(InvalidExportRequestException.class)
        .hasMessage("Export exceeds the 250000 column-edge limit");
  }

  @Test
  void rejectsATableEdgeWhoseRolesAreReversed() {
    final MappingDetail valid = mappingDetail(List.of(columnEdge()));
    final MappingDetail invalid =
        copyMapping(
            valid,
            valid.components(),
            List.of(new MappingEdge("target", "source")),
            valid.columnLineage(),
            valid.columnDerivations());

    assertInvalidMapping(invalid, "Table edge must connect a SOURCE endpoint to a TARGET endpoint");
  }

  @Test
  void rejectsATableEdgeWhoseEndpointDoesNotExist() {
    final MappingDetail valid = mappingDetail(List.of(columnEdge()));
    final MappingDetail invalid =
        copyMapping(
            valid,
            valid.components(),
            List.of(new MappingEdge("missing", "target")),
            valid.columnLineage(),
            valid.columnDerivations());

    assertInvalidMapping(invalid, "Mapping endpoint 'missing' was not found");
  }

  @Test
  void rejectsColumnLineageWhoseSourceColumnDoesNotExist() {
    final MappingDetail valid = mappingDetail(List.of(columnEdge()));
    final MappingColumnLineage missingSource =
        new MappingColumnLineage("source", "source::MISSING", "target", "target::ID");
    final MappingDetail invalid =
        copyMapping(
            valid,
            valid.components(),
            valid.edges(),
            List.of(missingSource),
            valid.columnDerivations());

    assertInvalidMapping(
        invalid, "Column reference 'source::MISSING' was not found on endpoint 'source'");
  }

  @Test
  void rejectsDerivationWhoseTargetColumnDoesNotExist() {
    final MappingDetail valid = mappingDetail(List.of(columnEdge()));
    final MappingColumnDerivation missingTarget =
        new MappingColumnDerivation(
            "target", "target::MISSING", MappingColumnDerivationType.SOURCE_COLUMNS, true);
    final MappingColumnLineage lineage =
        new MappingColumnLineage("source", "source::ID", "target", "target::MISSING");
    final MappingDetail invalid =
        copyMapping(
            valid, valid.components(), valid.edges(), List.of(lineage), List.of(missingTarget));

    assertInvalidMapping(
        invalid, "Column reference 'target::MISSING' was not found on endpoint 'target'");
  }

  @Test
  void rejectsDuplicateEndpointIdentifiers() {
    final MappingDetail valid = mappingDetail(List.of(columnEdge()));
    final MappingDetail invalid =
        copyMapping(
            valid,
            List.of(valid.components().getFirst(), valid.components().getFirst()),
            List.of(),
            List.of(),
            List.of());

    assertInvalidMapping(invalid, "Mapping endpoint IDs must be unique");
  }

  @Test
  void rejectsDuplicateColumnIdentifiers() {
    final MappingDetail valid = mappingDetail(List.of(columnEdge()));
    final MappingComponent source = valid.components().getFirst();
    final MappingComponent duplicateColumns =
        new MappingComponent(
            source.id(),
            source.componentType(),
            source.componentAlias(),
            source.datastore(),
            List.of(source.columns().getFirst(), source.columns().getFirst()));
    final MappingDetail invalid =
        copyMapping(
            valid,
            List.of(duplicateColumns, valid.components().getLast()),
            valid.edges(),
            valid.columnLineage(),
            valid.columnDerivations());

    assertInvalidMapping(invalid, "Mapping column IDs must be unique");
  }

  @Test
  void rejectsDuplicateTargetColumnDerivations() {
    final MappingDetail valid = mappingDetail(List.of(columnEdge()));
    final MappingColumnDerivation derivation = valid.columnDerivations().getFirst();
    final MappingDetail invalid =
        copyMapping(
            valid,
            valid.components(),
            valid.edges(),
            valid.columnLineage(),
            List.of(derivation, derivation));

    assertInvalidMapping(invalid, "Mapping has duplicate target-column derivations");
  }

  @Test
  void rejectsTheGlobalEndpointBudgetBeforeBuildingTheDocument() {
    final MappingDetail valid = mappingDetail(List.of(columnEdge()));
    final List<MappingComponent> oversized =
        Collections.nCopies(
            LoadPlanLineageExportService.MAX_ENDPOINTS + 1, valid.components().getFirst());
    final MappingDetail invalid = copyMapping(valid, oversized, List.of(), List.of(), List.of());

    assertInvalidMapping(invalid, "Export exceeds the 10000 endpoint limit");
  }

  private void assertInvalidMapping(MappingDetail mapping, String message) {
    assertThatThrownBy(
            () ->
                new LoadPlanLineageExportService()
                    .export(new FixtureProvider(mapping), "load-plan", "DEV", List.of("first")))
        .isInstanceOf(InvalidExportRequestException.class)
        .hasMessage(message);
  }

  private static MappingColumnLineage columnEdge() {
    return new MappingColumnLineage("source", "source::ID", "target", "target::ID");
  }

  private static MappingDetail mappingDetail(List<MappingColumnLineage> lineage) {
    final PhysicalLocation location =
        new PhysicalLocation("PS", "SERVER", "CATALOG", "SCHEMA", "ORACLE");
    final MappingComponent source = endpoint("source", "DATASTORE_SOURCE", "SOURCE", location);
    final MappingComponent target = endpoint("target", "DATASTORE_TARGET", "TARGET", location);
    final MappingColumnDerivation derivation =
        new MappingColumnDerivation(
            "target", "target::ID", MappingColumnDerivationType.SOURCE_COLUMNS, true);
    return new MappingDetail(
        "mapping",
        "Mapping",
        "DEV",
        List.of(source, target),
        List.of(new MappingEdge("source", "target")),
        lineage,
        List.of(derivation),
        List.of());
  }

  private static MappingDetail copyMapping(
      MappingDetail original,
      List<MappingComponent> components,
      List<MappingEdge> edges,
      List<MappingColumnLineage> lineage,
      List<MappingColumnDerivation> derivations) {
    return new MappingDetail(
        original.id(),
        original.name(),
        original.contextCode(),
        components,
        edges,
        lineage,
        derivations,
        original.warnings());
  }

  private static MappingComponent endpoint(
      String id, String type, String resourceName, PhysicalLocation location) {
    final DatastoreIdentity datastore =
        new DatastoreIdentity(resourceName, resourceName, "MODEL", "LOGICAL", location, null);
    return new MappingComponent(
        id, type, id + "_ALIAS", datastore, List.of(new MappingColumn(id + "::ID", "ID")));
  }

  private static final class FixtureProvider implements OdiReadProvider {
    private final MappingDetail mapping;

    private FixtureProvider(MappingDetail mapping) {
      this.mapping = mapping;
    }

    @Override
    public RepositoryInfo repository() {
      return new RepositoryInfo("MASTER/WORK", "MASTER", "WORK");
    }

    @Override
    public List<ContextInfo> contexts() {
      return List.of(new ContextInfo("DEV", "Development", true));
    }

    @Override
    public List<LoadPlanSummary> loadPlans() {
      return List.of();
    }

    @Override
    public LoadPlanDetail loadPlan(String id, String contextCode) {
      return new LoadPlanDetail(
          id, "Load Plan", contextCode, List.of(occurrence("first"), occurrence("second")));
    }

    @Override
    public MappingDetail mapping(String id, String contextCode) {
      return mapping;
    }

    private LoadPlanStep occurrence(String id) {
      return new LoadPlanStep(
          id,
          null,
          id,
          LoadPlanStepType.RUN_SCENARIO,
          List.of("root", id),
          null,
          new ScenarioReference("SCENARIO", "001"),
          new MappingReference("mapping", "Mapping"),
          StepResolution.RESOLVED,
          null,
          true);
    }
  }
}
