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

package org.openmetadata.tools.odi.explorer.provider.sdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import oracle.odi.domain.mapping.Mapping;
import oracle.odi.domain.project.OdiPackage;
import oracle.odi.domain.project.StepMapping;
import oracle.odi.domain.runtime.loadplan.OdiCaseElse;
import oracle.odi.domain.runtime.loadplan.OdiCaseWhen;
import oracle.odi.domain.runtime.loadplan.OdiLoadPlan;
import oracle.odi.domain.runtime.loadplan.OdiLoadPlanCaseCondition;
import oracle.odi.domain.runtime.loadplan.OdiLoadPlanStepCase;
import oracle.odi.domain.runtime.loadplan.OdiLoadPlanStepParallel;
import oracle.odi.domain.runtime.loadplan.OdiLoadPlanStepRunScenario;
import oracle.odi.domain.runtime.loadplan.OdiLoadPlanStepSerial;
import oracle.odi.domain.runtime.scenario.OdiScenario;
import oracle.odi.domain.runtime.scenario.Tag;
import org.junit.jupiter.api.Test;
import org.openmetadata.tools.odi.explorer.model.LoadPlanDetail;
import org.openmetadata.tools.odi.explorer.model.LoadPlanStep;
import org.openmetadata.tools.odi.explorer.model.LoadPlanStepType;
import org.openmetadata.tools.odi.explorer.model.LoadPlanSummary;
import org.openmetadata.tools.odi.explorer.model.StepResolution;

class OdiLoadPlanMapperTest {
  @Test
  void resolvesScenarioTagToItsCurrentMapping() {
    final OdiFinderAccess finders = mock(OdiFinderAccess.class);
    final OdiLoadPlan loadPlan = loadPlan("DAILY_LOAD");
    final OdiLoadPlanStepRunScenario step = scenarioStep(12L, "SCEN_ORDERS", "004");
    final OdiScenario scenario = mock(OdiScenario.class);
    final Mapping mapping = mapping(41L, "LOAD_ORDERS");
    final Tag tag = step.getScenarioTag();
    when(loadPlan.getRootStep().getChildrenSteps()).thenReturn(List.of(step));
    when(finders.findScenario(tag)).thenReturn(scenario);
    when(scenario.wasGeneratedFromMapping()).thenReturn(true);
    when(scenario.getSourceMappingId()).thenReturn(41L);
    when(finders.findMapping(41L)).thenReturn(mapping);
    when(scenario.isScenarioStale(mapping)).thenReturn(false);

    final LoadPlanDetail result = new OdiLoadPlanMapper(finders).toDetail(loadPlan, "DEV");

    assertThat(runScenarioNodes(result))
        .singleElement()
        .satisfies(
            resolved -> {
              assertThat(resolved.id()).isEqualTo("12");
              assertThat(resolved.scenario().scenarioName()).isEqualTo("SCEN_ORDERS");
              assertThat(resolved.scenario().scenarioVersion()).isEqualTo("004");
              assertThat(resolved.mapping().mappingId()).isEqualTo("41");
              assertThat(resolved.mapping().mappingName()).isEqualTo("LOAD_ORDERS");
              assertThat(resolved.resolution()).isEqualTo(StepResolution.RESOLVED);
              assertThat(resolved.enabled()).isTrue();
            });
  }

  @Test
  void marksChangedMappingAsStale() {
    final OdiFinderAccess finders = mock(OdiFinderAccess.class);
    final OdiLoadPlan loadPlan = loadPlan("DAILY_LOAD");
    final OdiLoadPlanStepRunScenario step = scenarioStep(12L, "SCEN_ORDERS", "004");
    final OdiScenario scenario = mock(OdiScenario.class);
    final Mapping mapping = mapping(41L, "LOAD_ORDERS");
    when(loadPlan.getRootStep().getChildrenSteps()).thenReturn(List.of(step));
    when(finders.findScenario(step.getScenarioTag())).thenReturn(scenario);
    when(scenario.wasGeneratedFromMapping()).thenReturn(true);
    when(scenario.getSourceMappingId()).thenReturn(41L);
    when(finders.findMapping(41L)).thenReturn(mapping);
    when(scenario.isScenarioStale(mapping)).thenReturn(true);

    final LoadPlanDetail result = new OdiLoadPlanMapper(finders).toDetail(loadPlan, "DEV");

    assertThat(runScenarioNodes(result).getFirst().resolution()).isEqualTo(StepResolution.STALE);
    assertThat(runScenarioNodes(result).getFirst().resolutionReason())
        .isEqualTo("The scenario was generated from an older source revision.");
  }

  @Test
  void extractsEveryDirectMappingStepFromPackageScenario() {
    final OdiFinderAccess finders = mock(OdiFinderAccess.class);
    final OdiLoadPlan loadPlan = loadPlan("PACKAGE_LOAD");
    final OdiLoadPlanStepRunScenario step = scenarioStep(20L, "SCEN_PACKAGE", "001");
    final OdiScenario scenario = mock(OdiScenario.class);
    final OdiPackage odiPackage = mock(OdiPackage.class);
    final StepMapping firstStep = mock(StepMapping.class);
    final StepMapping secondStep = mock(StepMapping.class);
    final Mapping customerMapping = mapping(51L, "LOAD_CUSTOMERS");
    final Mapping productMapping = mapping(52L, "LOAD_PRODUCTS");
    when(loadPlan.getRootStep().getChildrenSteps()).thenReturn(List.of(step));
    when(finders.findScenario(step.getScenarioTag())).thenReturn(scenario);
    when(scenario.wasGeneratedFromPackage()).thenReturn(true);
    when(scenario.getSourcePackageId()).thenReturn(8L);
    when(finders.findPackage(8L)).thenReturn(odiPackage);
    when(odiPackage.getSteps()).thenReturn(List.of(firstStep, secondStep));
    when(firstStep.getStepId()).thenReturn(501L);
    when(firstStep.getName()).thenReturn("Run customer mapping");
    when(firstStep.getMapping()).thenReturn(customerMapping);
    when(secondStep.getStepId()).thenReturn(502L);
    when(secondStep.getName()).thenReturn("Run product mapping");
    when(secondStep.getMapping()).thenReturn(productMapping);
    when(scenario.isScenarioStale(odiPackage)).thenReturn(false);

    final LoadPlanDetail result = new OdiLoadPlanMapper(finders).toDetail(loadPlan, "DEV");

    assertThat(result.steps().stream().filter(item -> item.mapping() != null))
        .extracting(item -> item.mapping().mappingName())
        .containsExactly("LOAD_CUSTOMERS", "LOAD_PRODUCTS");
    assertThat(result.steps().stream().filter(item -> item.mapping() != null))
        .extracting(item -> item.resolution())
        .containsOnly(StepResolution.RESOLVED);
    assertThat(result.steps().stream().filter(item -> item.mapping() != null))
        .extracting(LoadPlanStep::id)
        .containsExactly("20/package-mapping:501", "20/package-mapping:502");
    assertThat(result.steps().stream().filter(item -> item.mapping() != null))
        .extracting(LoadPlanStep::name)
        .containsExactly("Run customer mapping", "Run product mapping");
  }

  @Test
  void preservesBrokenPackageMappingOccurrenceWithoutHidingStalePackage() {
    final OdiFinderAccess finders = mock(OdiFinderAccess.class);
    final OdiLoadPlan loadPlan = loadPlan("STALE_PACKAGE_LOAD");
    final OdiLoadPlanStepRunScenario step = scenarioStep(21L, "STALE_PACKAGE", "003");
    final OdiScenario scenario = mock(OdiScenario.class);
    final OdiPackage odiPackage = mock(OdiPackage.class);
    final StepMapping brokenStep = mock(StepMapping.class);
    when(loadPlan.getRootStep().getChildrenSteps()).thenReturn(List.of(step));
    when(finders.findScenario(step.getScenarioTag())).thenReturn(scenario);
    when(scenario.wasGeneratedFromPackage()).thenReturn(true);
    when(scenario.getSourcePackageId()).thenReturn(9L);
    when(finders.findPackage(9L)).thenReturn(odiPackage);
    when(odiPackage.getSteps()).thenReturn(List.of(brokenStep));
    when(brokenStep.getStepId()).thenReturn(601L);
    when(brokenStep.getName()).thenReturn("Deleted mapping reference");
    when(brokenStep.getMapping()).thenReturn(null);
    when(brokenStep.getMappingShortcut()).thenReturn(null);
    when(scenario.isScenarioStale(odiPackage)).thenReturn(true);

    final LoadPlanDetail result = new OdiLoadPlanMapper(finders).toDetail(loadPlan, "DEV");

    assertThat(runScenarioNodes(result))
        .singleElement()
        .satisfies(
            packageNode -> {
              assertThat(packageNode.resolution()).isEqualTo(StepResolution.STALE);
              assertThat(packageNode.resolutionReason())
                  .isEqualTo("The scenario was generated from an older source revision.");
            });
    assertThat(packageMappingNodes(result))
        .singleElement()
        .satisfies(
            mappingNode -> {
              assertThat(mappingNode.id()).isEqualTo("21/package-mapping:601");
              assertThat(mappingNode.parentStepId()).isEqualTo("21");
              assertThat(mappingNode.name()).isEqualTo("Deleted mapping reference");
              assertThat(mappingNode.mapping()).isNull();
              assertThat(mappingNode.resolution()).isEqualTo(StepResolution.UNRESOLVED);
              assertThat(mappingNode.resolutionReason())
                  .isEqualTo("The source mapping referenced by the scenario was not found.");
            });
  }

  @Test
  void distinguishesMissingScenarioFromUnsupportedScenarioSource() {
    final OdiFinderAccess finders = mock(OdiFinderAccess.class);
    final OdiLoadPlan loadPlan = loadPlan("MIXED_LOAD");
    final OdiLoadPlanStepRunScenario missing = scenarioStep(30L, "MISSING", "001");
    final OdiLoadPlanStepRunScenario procedure = scenarioStep(31L, "PROCEDURE", "001");
    final OdiScenario procedureScenario = mock(OdiScenario.class);
    when(loadPlan.getRootStep().getChildrenSteps()).thenReturn(List.of(missing, procedure));
    when(finders.findScenario(missing.getScenarioTag())).thenReturn(null);
    when(finders.findScenario(procedure.getScenarioTag())).thenReturn(procedureScenario);

    final LoadPlanDetail result = new OdiLoadPlanMapper(finders).toDetail(loadPlan, "DEV");

    assertThat(runScenarioNodes(result))
        .extracting(item -> item.resolution())
        .containsExactly(StepResolution.UNRESOLVED, StepResolution.OUT_OF_SCOPE);
    assertThat(runScenarioNodes(result))
        .extracting(item -> item.resolutionReason())
        .containsExactly(
            "The referenced ODI scenario was not found.",
            "The scenario source is outside the mapping-only scope.");
    assertThat(runScenarioNodes(result)).allSatisfy(item -> assertThat(item.mapping()).isNull());
  }

  @Test
  void countsScenarioLeavesAndResolvedMappingOccurrences() {
    final OdiFinderAccess finders = mock(OdiFinderAccess.class);
    final OdiLoadPlan loadPlan = loadPlan("DAILY_LOAD");
    final OdiLoadPlanStepRunScenario resolvedStep = scenarioStep(40L, "MAPPING", "001");
    final OdiLoadPlanStepRunScenario missingStep = scenarioStep(41L, "MISSING", "001");
    final OdiScenario scenario = mock(OdiScenario.class);
    final Mapping mapping = mapping(61L, "LOAD_FACTS");
    when(loadPlan.getRootStep().getChildrenSteps()).thenReturn(List.of(resolvedStep, missingStep));
    when(finders.findScenario(resolvedStep.getScenarioTag())).thenReturn(scenario);
    when(scenario.wasGeneratedFromMapping()).thenReturn(true);
    when(scenario.getSourceMappingId()).thenReturn(61L);
    when(finders.findMapping(61L)).thenReturn(mapping);
    when(scenario.isScenarioStale(mapping)).thenReturn(false);

    final LoadPlanSummary result = new OdiLoadPlanMapper(finders).toSummary(loadPlan);

    assertThat(result.scenarioCount()).isEqualTo(2);
    assertThat(result.mappingCount()).isEqualTo(1);
  }

  @Test
  void traversesEveryWhenAndElseBranchOfCaseSteps() {
    final OdiFinderAccess finders = mock(OdiFinderAccess.class);
    final OdiLoadPlan loadPlan = loadPlan("CASE_LOAD");
    final OdiLoadPlanStepCase caseStep = mock(OdiLoadPlanStepCase.class);
    final OdiCaseWhen firstWhen = mock(OdiCaseWhen.class);
    final OdiCaseWhen secondWhen = mock(OdiCaseWhen.class);
    final OdiCaseElse caseElse = mock(OdiCaseElse.class);
    final OdiLoadPlanStepSerial firstRoot = branch(scenarioStep(71L, "FIRST", "001"));
    final OdiLoadPlanStepSerial secondRoot = branch(scenarioStep(72L, "SECOND", "001"));
    final OdiLoadPlanStepSerial elseRoot = branch(scenarioStep(73L, "ELSE", "001"));
    when(loadPlan.getRootStep().getChildrenSteps()).thenReturn(List.of(caseStep));
    when(caseStep.getCaseWhenList()).thenReturn(List.of(firstWhen, secondWhen));
    when(caseStep.getCaseElse()).thenReturn(caseElse);
    when(firstWhen.getRootStep()).thenReturn(firstRoot);
    when(secondWhen.getRootStep()).thenReturn(secondRoot);
    when(caseElse.getRootStep()).thenReturn(elseRoot);

    final LoadPlanDetail result = new OdiLoadPlanMapper(finders).toDetail(loadPlan, "DEV");

    assertThat(runScenarioNodes(result))
        .extracting(item -> item.scenario().scenarioName())
        .containsExactly("FIRST", "SECOND", "ELSE");
  }

  @Test
  void preservesNestedTreeEmptyContainersCaseBranchesAndDeclaredStepContext() {
    final OdiFinderAccess finders = mock(OdiFinderAccess.class);
    final OdiLoadPlan loadPlan = loadPlan("NESTED_LOAD");
    final OdiLoadPlanStepSerial serial = serialStep(2L, "SERIAL");
    final OdiLoadPlanStepParallel parallel = parallelStep(3L, "PARALLEL");
    final OdiLoadPlanStepCase caseStep = caseStep(4L, "CASE");
    final OdiCaseWhen caseWhen = branchCondition(OdiCaseWhen.class);
    final OdiCaseElse caseElse = branchCondition(OdiCaseElse.class);
    final OdiLoadPlanStepRunScenario runScenario = scenarioStep(6L, "MAPPING_SCEN", "001");
    final OdiLoadPlanStepSerial whenRoot = serialStep(5L, "WHEN_ROOT");
    final OdiLoadPlanStepSerial elseRoot = serialStep(7L, "EMPTY_ELSE_ROOT");
    final OdiScenario scenario = mock(OdiScenario.class);
    final Mapping mapping = mapping(60L, "LOAD_NESTED");
    when(loadPlan.getRootStep().getChildrenSteps()).thenReturn(List.of(serial));
    when(serial.getChildrenSteps()).thenReturn(List.of(parallel));
    when(parallel.getChildrenSteps()).thenReturn(List.of(caseStep));
    when(caseStep.getCaseWhenList()).thenReturn(List.of(caseWhen));
    when(caseStep.getCaseElse()).thenReturn(caseElse);
    when(caseWhen.getRootStep()).thenReturn(whenRoot);
    when(caseElse.getRootStep()).thenReturn(elseRoot);
    when(whenRoot.getChildrenSteps()).thenReturn(List.of(runScenario));
    when(elseRoot.getChildrenSteps()).thenReturn(List.of());
    when(runScenario.getContextCode()).thenReturn("STEP_CTX");
    when(finders.findScenario(runScenario.getScenarioTag())).thenReturn(scenario);
    when(scenario.wasGeneratedFromMapping()).thenReturn(true);
    when(scenario.getSourceMappingId()).thenReturn(60L);
    when(finders.findMapping(60L)).thenReturn(mapping);
    when(scenario.isScenarioStale(mapping)).thenReturn(false);

    final LoadPlanDetail result = new OdiLoadPlanMapper(finders).toDetail(loadPlan, "VIEW_CTX");

    assertThat(result.steps())
        .extracting(item -> item.stepType())
        .containsExactly(
            LoadPlanStepType.ROOT_SERIAL,
            LoadPlanStepType.SERIAL,
            LoadPlanStepType.PARALLEL,
            LoadPlanStepType.CASE,
            LoadPlanStepType.WHEN,
            LoadPlanStepType.SERIAL,
            LoadPlanStepType.RUN_SCENARIO,
            LoadPlanStepType.ELSE,
            LoadPlanStepType.SERIAL);
    final var runNode =
        result.steps().stream()
            .filter(item -> item.stepType() == LoadPlanStepType.RUN_SCENARIO)
            .findFirst()
            .orElseThrow();
    assertThat(runNode.parentStepId()).isEqualTo("5");
    assertThat(runNode.declaredContextCode()).isEqualTo("STEP_CTX");
    assertThat(runNode.path())
        .containsExactly(
            "ROOT", "SERIAL", "PARALLEL", "CASE", "WHEN[0]", "WHEN_ROOT", "Run MAPPING_SCEN");
    assertThat(result.steps())
        .anySatisfy(
            node -> {
              assertThat(node.id()).isEqualTo("4/when[0]");
              assertThat(node.parentStepId()).isEqualTo("4");
              assertThat(node.stepType()).isEqualTo(LoadPlanStepType.WHEN);
            });
    assertThat(result.steps())
        .anySatisfy(
            node -> {
              assertThat(node.id()).isEqualTo("4/else[1]");
              assertThat(node.parentStepId()).isEqualTo("4");
              assertThat(node.stepType()).isEqualTo(LoadPlanStepType.ELSE);
            });
    assertThat(result.steps())
        .anySatisfy(
            node -> {
              assertThat(node.stepType()).isEqualTo(LoadPlanStepType.SERIAL);
              assertThat(node.name()).isEqualTo("EMPTY_ELSE_ROOT");
            });
  }

  private OdiLoadPlan loadPlan(String name) {
    final OdiLoadPlan loadPlan = mock(OdiLoadPlan.class);
    final OdiLoadPlanStepSerial root = mock(OdiLoadPlanStepSerial.class);
    when(loadPlan.getInternalId()).thenReturn(5L);
    when(loadPlan.getName()).thenReturn(name);
    when(loadPlan.getDescription()).thenReturn("Description");
    when(loadPlan.getRootStep()).thenReturn(root);
    when(root.getStepId()).thenReturn(1L);
    when(root.getName()).thenReturn("ROOT");
    when(root.isEnabled()).thenReturn(true);
    when(root.hasDisabledParent()).thenReturn(false);
    return loadPlan;
  }

  private OdiLoadPlanStepRunScenario scenarioStep(long id, String name, String version) {
    final OdiLoadPlanStepRunScenario step = mock(OdiLoadPlanStepRunScenario.class);
    final Tag tag = new Tag(name, version);
    when(step.getStepId()).thenReturn(id);
    when(step.getScenarioTag()).thenReturn(tag);
    when(step.getName()).thenReturn("Run " + name);
    when(step.getContextCode()).thenReturn("DEV");
    when(step.isEnabled()).thenReturn(true);
    when(step.hasDisabledParent()).thenReturn(false);
    return step;
  }

  private Mapping mapping(long id, String name) {
    final Mapping mapping = mock(Mapping.class);
    when(mapping.getInternalId()).thenReturn(id);
    when(mapping.getName()).thenReturn(name);
    return mapping;
  }

  private OdiLoadPlanStepSerial branch(OdiLoadPlanStepRunScenario scenarioStep) {
    final OdiLoadPlanStepSerial root = mock(OdiLoadPlanStepSerial.class);
    when(root.getChildrenSteps()).thenReturn(List.of(scenarioStep));
    return root;
  }

  private OdiLoadPlanStepSerial serialStep(long id, String name) {
    final OdiLoadPlanStepSerial step = mock(OdiLoadPlanStepSerial.class);
    when(step.getStepId()).thenReturn(id);
    when(step.getName()).thenReturn(name);
    when(step.isEnabled()).thenReturn(true);
    when(step.hasDisabledParent()).thenReturn(false);
    return step;
  }

  private OdiLoadPlanStepParallel parallelStep(long id, String name) {
    final OdiLoadPlanStepParallel step = mock(OdiLoadPlanStepParallel.class);
    when(step.getStepId()).thenReturn(id);
    when(step.getName()).thenReturn(name);
    when(step.isEnabled()).thenReturn(true);
    when(step.hasDisabledParent()).thenReturn(false);
    return step;
  }

  private OdiLoadPlanStepCase caseStep(long id, String name) {
    final OdiLoadPlanStepCase step = mock(OdiLoadPlanStepCase.class);
    when(step.getStepId()).thenReturn(id);
    when(step.getName()).thenReturn(name);
    when(step.isEnabled()).thenReturn(true);
    when(step.hasDisabledParent()).thenReturn(false);
    return step;
  }

  private List<LoadPlanStep> runScenarioNodes(LoadPlanDetail detail) {
    return detail.steps().stream()
        .filter(item -> item.stepType() == LoadPlanStepType.RUN_SCENARIO)
        .toList();
  }

  private List<LoadPlanStep> packageMappingNodes(LoadPlanDetail detail) {
    return detail.steps().stream()
        .filter(item -> item.stepType() == LoadPlanStepType.PACKAGE_MAPPING)
        .toList();
  }

  private <T extends OdiLoadPlanCaseCondition> T branchCondition(Class<T> type) {
    final T condition = mock(type);
    when(condition.getStepId()).thenReturn(null);
    when(condition.isEnabled()).thenReturn(true);
    when(condition.hasDisabledParent()).thenReturn(false);
    return condition;
  }
}
