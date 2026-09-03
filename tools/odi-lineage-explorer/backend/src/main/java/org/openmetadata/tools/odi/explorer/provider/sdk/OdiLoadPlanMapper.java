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

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import oracle.odi.domain.IOdiEntity;
import oracle.odi.domain.mapping.Mapping;
import oracle.odi.domain.project.OdiPackage;
import oracle.odi.domain.project.Step;
import oracle.odi.domain.project.StepMapping;
import oracle.odi.domain.runtime.loadplan.OdiCaseElse;
import oracle.odi.domain.runtime.loadplan.OdiCaseWhen;
import oracle.odi.domain.runtime.loadplan.OdiLoadPlan;
import oracle.odi.domain.runtime.loadplan.OdiLoadPlanCaseCondition;
import oracle.odi.domain.runtime.loadplan.OdiLoadPlanElement;
import oracle.odi.domain.runtime.loadplan.OdiLoadPlanStep;
import oracle.odi.domain.runtime.loadplan.OdiLoadPlanStepCase;
import oracle.odi.domain.runtime.loadplan.OdiLoadPlanStepContainer;
import oracle.odi.domain.runtime.loadplan.OdiLoadPlanStepParallel;
import oracle.odi.domain.runtime.loadplan.OdiLoadPlanStepRunScenario;
import oracle.odi.domain.runtime.loadplan.OdiLoadPlanStepSerial;
import oracle.odi.domain.runtime.scenario.OdiScenario;
import oracle.odi.domain.runtime.scenario.Tag;
import org.openmetadata.tools.odi.explorer.model.LoadPlanDetail;
import org.openmetadata.tools.odi.explorer.model.LoadPlanStep;
import org.openmetadata.tools.odi.explorer.model.LoadPlanStepType;
import org.openmetadata.tools.odi.explorer.model.LoadPlanSummary;
import org.openmetadata.tools.odi.explorer.model.MappingReference;
import org.openmetadata.tools.odi.explorer.model.ScenarioReference;
import org.openmetadata.tools.odi.explorer.model.StepResolution;
import org.openmetadata.tools.odi.explorer.provider.OdiConnectionException;

final class OdiLoadPlanMapper {
  private static final String MISSING_SCENARIO_REASON =
      "The referenced ODI scenario was not found.";
  private static final String MISSING_TAG_REASON = "The load plan step has no scenario tag.";
  private static final String MISSING_MAPPING_REASON =
      "The source mapping referenced by the scenario was not found.";
  private static final String MISSING_PACKAGE_REASON =
      "The source package referenced by the scenario was not found.";
  private static final String NO_PACKAGE_MAPPINGS_REASON =
      "The package scenario has no direct mapping steps.";
  private static final String STALE_REASON =
      "The scenario was generated from an older source revision.";
  private static final String UNSUPPORTED_SCENARIO_REASON =
      "The scenario source is outside the mapping-only scope.";
  private final OdiFinderAccess finders;

  OdiLoadPlanMapper(OdiFinderAccess finders) {
    this.finders = finders;
  }

  LoadPlanDetail toDetail(OdiLoadPlan loadPlan, String contextCode) {
    return new LoadPlanDetail(
        entityId(loadPlan), loadPlan.getName(), contextCode, resolve(loadPlan));
  }

  LoadPlanSummary toSummary(OdiLoadPlan loadPlan) {
    final List<LoadPlanStep> steps = resolve(loadPlan);
    final int scenarioCount = countType(steps, LoadPlanStepType.RUN_SCENARIO);
    final int mappingCount = Math.toIntExact(steps.stream().filter(this::hasMapping).count());
    return new LoadPlanSummary(
        entityId(loadPlan),
        loadPlan.getName(),
        loadPlan.getDescription(),
        scenarioCount,
        mappingCount);
  }

  private List<LoadPlanStep> resolve(OdiLoadPlan loadPlan) {
    final List<LoadPlanStep> result = new ArrayList<>();
    final NodeLocation location = new NodeLocation(null, 0, true, entityId(loadPlan) + ":root");
    appendStep(loadPlan.getRootStep(), location, result);
    return List.copyOf(result);
  }

  private void appendStep(OdiLoadPlanStep step, NodeLocation location, List<LoadPlanStep> result) {
    final LoadPlanStepType type = stepType(step, location.isRoot());
    final TreeNode node = treeNode(step, type, location);
    if (step instanceof OdiLoadPlanStepRunScenario scenarioStep) {
      result.addAll(resolveScenarioStep(scenarioStep, node));
    } else {
      result.add(structuralNode(node, type));
      appendChildren(step, node, result);
    }
  }

  private void appendChildren(OdiLoadPlanStep step, TreeNode parent, List<LoadPlanStep> result) {
    if (step instanceof OdiLoadPlanStepContainer container) {
      appendContainerChildren(container, parent, result);
    } else if (step instanceof OdiLoadPlanStepCase caseStep) {
      appendCaseBranches(caseStep, parent, result);
    }
  }

  private void appendContainerChildren(
      OdiLoadPlanStepContainer container, TreeNode parent, List<LoadPlanStep> result) {
    int childIndex = 0;
    for (final OdiLoadPlanStep child : container.getChildrenSteps()) {
      appendStep(child, new NodeLocation(parent, childIndex, false, null), result);
      childIndex++;
    }
  }

  private void appendCaseBranches(
      OdiLoadPlanStepCase caseStep, TreeNode parent, List<LoadPlanStep> result) {
    int branchIndex = 0;
    for (final OdiCaseWhen caseWhen : caseStep.getCaseWhenList()) {
      appendCondition(caseWhen, LoadPlanStepType.WHEN, branchIndex, parent, result);
      branchIndex++;
    }
    final OdiCaseElse caseElse = caseStep.getCaseElse();
    if (caseElse != null) {
      appendCondition(caseElse, LoadPlanStepType.ELSE, branchIndex, parent, result);
    }
  }

  private void appendCondition(
      OdiLoadPlanCaseCondition condition,
      LoadPlanStepType type,
      int index,
      TreeNode parent,
      List<LoadPlanStep> result) {
    final TreeNode node = treeNode(condition, type, new NodeLocation(parent, index, false, null));
    result.add(structuralNode(node, type));
    final OdiLoadPlanStepSerial branchRoot = condition.getRootStep();
    if (branchRoot != null) {
      appendStep(branchRoot, new NodeLocation(node, 0, false, null), result);
    }
  }

  private List<LoadPlanStep> resolveScenarioStep(OdiLoadPlanStepRunScenario step, TreeNode node) {
    final Tag tag = step.getScenarioTag();
    final ScenarioNodeContext context = new ScenarioNodeContext(step, node, tag);
    final OdiScenario scenario = tag == null ? null : finders.findScenario(tag);
    List<LoadPlanStep> result;
    if (tag == null) {
      result = List.of(scenarioNode(context, null, StepResolution.UNRESOLVED, MISSING_TAG_REASON));
    } else if (scenario == null) {
      result =
          List.of(scenarioNode(context, null, StepResolution.UNRESOLVED, MISSING_SCENARIO_REASON));
    } else if (scenario.wasGeneratedFromMapping()) {
      result = List.of(resolveMappingScenario(context, scenario));
    } else if (scenario.wasGeneratedFromPackage()) {
      result = resolvePackageScenario(context, scenario);
    } else {
      result =
          List.of(
              scenarioNode(
                  context, null, StepResolution.OUT_OF_SCOPE, UNSUPPORTED_SCENARIO_REASON));
    }
    return result;
  }

  private LoadPlanStep resolveMappingScenario(ScenarioNodeContext context, OdiScenario scenario) {
    final Mapping mapping = finders.findMapping(scenario.getSourceMappingId());
    final MappingReference reference = mapping == null ? null : mappingReference(mapping);
    final StepResolution resolution = mappingResolution(scenario, mapping);
    final String reason = mapping == null ? MISSING_MAPPING_REASON : resolutionReason(resolution);
    return scenarioNode(context, reference, resolution, reason);
  }

  private List<LoadPlanStep> resolvePackageScenario(
      ScenarioNodeContext context, OdiScenario scenario) {
    final OdiPackage odiPackage = finders.findPackage(scenario.getSourcePackageId());
    List<LoadPlanStep> result;
    if (odiPackage == null) {
      result =
          List.of(scenarioNode(context, null, StepResolution.UNRESOLVED, MISSING_PACKAGE_REASON));
    } else {
      result = resolvedPackageScenario(context, scenario, odiPackage);
    }
    return result;
  }

  private List<LoadPlanStep> resolvedPackageScenario(
      ScenarioNodeContext context, OdiScenario scenario, OdiPackage odiPackage) {
    final List<PackageMappingOccurrence> mappingOccurrences =
        directMappingOccurrences(odiPackage.getSteps());
    final StepResolution resolution = staleResolution(scenario, odiPackage);
    final List<LoadPlanStep> result = new ArrayList<>();
    if (mappingOccurrences.isEmpty()) {
      result.add(
          scenarioNode(context, null, StepResolution.OUT_OF_SCOPE, NO_PACKAGE_MAPPINGS_REASON));
    } else {
      result.add(scenarioNode(context, null, resolution, resolutionReason(resolution)));
      appendPackageMappings(context, mappingOccurrences, resolution, result);
    }
    return List.copyOf(result);
  }

  private void appendPackageMappings(
      ScenarioNodeContext context,
      List<PackageMappingOccurrence> mappingOccurrences,
      StepResolution resolution,
      List<LoadPlanStep> result) {
    for (final PackageMappingOccurrence occurrence : mappingOccurrences) {
      result.add(packageMappingNode(context, occurrence, resolution));
    }
  }

  private LoadPlanStep packageMappingNode(
      ScenarioNodeContext context,
      PackageMappingOccurrence occurrence,
      StepResolution packageResolution) {
    final TreeNode parent = context.node();
    final Mapping mapping = occurrence.mapping();
    final String name = packageMappingName(occurrence);
    final StepResolution resolution =
        mapping == null ? StepResolution.UNRESOLVED : packageResolution;
    return new LoadPlanStep(
        packageMappingId(parent.id(), occurrence),
        parent.id(),
        name,
        LoadPlanStepType.PACKAGE_MAPPING,
        appendPath(parent.path(), name),
        context.step().getContextCode(),
        scenarioReference(context.tag()),
        mapping == null ? null : mappingReference(mapping),
        resolution,
        mapping == null ? MISSING_MAPPING_REASON : resolutionReason(resolution),
        parent.isEnabled());
  }

  private String packageMappingId(String parentId, PackageMappingOccurrence occurrence) {
    final Number stepId = occurrence.step().getStepId();
    final String suffix =
        stepId == null
            ? "package-mapping[%d]".formatted(occurrence.index())
            : "package-mapping:%s".formatted(stepId);
    return "%s/%s".formatted(parentId, suffix);
  }

  private String packageMappingName(PackageMappingOccurrence occurrence) {
    String result = occurrence.step().getName();
    if (result == null || result.isBlank()) {
      final Mapping mapping = occurrence.mapping();
      result =
          mapping == null ? "PACKAGE_MAPPING[%d]".formatted(occurrence.index()) : mapping.getName();
    }
    return result;
  }

  private LoadPlanStep scenarioNode(
      ScenarioNodeContext context,
      MappingReference mapping,
      StepResolution resolution,
      String reason) {
    final TreeNode node = context.node();
    return new LoadPlanStep(
        node.id(),
        node.parentId(),
        node.name(),
        LoadPlanStepType.RUN_SCENARIO,
        node.path(),
        context.step().getContextCode(),
        scenarioReference(context.tag()),
        mapping,
        resolution,
        reason,
        node.isEnabled());
  }

  private LoadPlanStep structuralNode(TreeNode node, LoadPlanStepType type) {
    return new LoadPlanStep(
        node.id(),
        node.parentId(),
        node.name(),
        type,
        node.path(),
        null,
        null,
        null,
        null,
        null,
        node.isEnabled());
  }

  private TreeNode treeNode(
      OdiLoadPlanElement element, LoadPlanStepType type, NodeLocation location) {
    final String id = elementId(element, type, location);
    final String name = elementName(element, type, location.index());
    final List<String> parentPath =
        location.parent() == null ? List.of() : location.parent().path();
    final String parentId = location.parent() == null ? null : location.parent().id();
    return new TreeNode(id, parentId, name, appendPath(parentPath, name), isEnabled(element));
  }

  private String elementId(
      OdiLoadPlanElement element, LoadPlanStepType type, NodeLocation location) {
    final Number stepId = element.getStepId();
    String result = stepId == null ? location.fallbackId() : stepId.toString();
    if (result == null) {
      result =
          "%s/%s[%d]"
              .formatted(
                  location.parent().id(), type.name().toLowerCase(Locale.ROOT), location.index());
    }
    return result;
  }

  private String elementName(OdiLoadPlanElement element, LoadPlanStepType type, int index) {
    final String name = element.getName();
    String result = name;
    if (name == null || name.isBlank()) {
      result = type == LoadPlanStepType.ELSE ? "ELSE" : "%s[%d]".formatted(type.name(), index);
    }
    return result;
  }

  private boolean isEnabled(OdiLoadPlanElement element) {
    return element.isEnabled() && !element.hasDisabledParent();
  }

  private LoadPlanStepType stepType(OdiLoadPlanStep step, boolean isRoot) {
    LoadPlanStepType result;
    if (isRoot) {
      result = LoadPlanStepType.ROOT_SERIAL;
    } else if (step instanceof OdiLoadPlanStepSerial) {
      result = LoadPlanStepType.SERIAL;
    } else if (step instanceof OdiLoadPlanStepParallel) {
      result = LoadPlanStepType.PARALLEL;
    } else if (step instanceof OdiLoadPlanStepCase) {
      result = LoadPlanStepType.CASE;
    } else if (step instanceof OdiLoadPlanStepRunScenario) {
      result = LoadPlanStepType.RUN_SCENARIO;
    } else {
      throw new OdiConnectionException(
          "Unsupported ODI load plan step type: " + step.getClass().getSimpleName());
    }
    return result;
  }

  private List<PackageMappingOccurrence> directMappingOccurrences(Collection<Step> packageSteps) {
    final List<PackageMappingOccurrence> result = new ArrayList<>();
    int mappingIndex = 0;
    for (final Step packageStep : packageSteps) {
      if (packageStep instanceof StepMapping mappingStep) {
        result.add(
            new PackageMappingOccurrence(
                mappingStep, packageStepMapping(mappingStep), mappingIndex));
        mappingIndex++;
      }
    }
    return List.copyOf(result);
  }

  private Mapping packageStepMapping(StepMapping mappingStep) {
    final Mapping mapping = mappingStep.getMapping();
    return mapping == null ? mappingStep.getMappingShortcut() : mapping;
  }

  private ScenarioReference scenarioReference(Tag tag) {
    ScenarioReference result = null;
    if (tag != null) {
      result = new ScenarioReference(tag.getName(), tag.getVersion());
    }
    return result;
  }

  private MappingReference mappingReference(Mapping mapping) {
    return new MappingReference(entityId(mapping), mapping.getName());
  }

  private StepResolution mappingResolution(OdiScenario scenario, Mapping mapping) {
    final StepResolution result =
        mapping == null ? StepResolution.UNRESOLVED : staleResolution(scenario, mapping);
    return result;
  }

  private StepResolution staleResolution(OdiScenario scenario, IOdiEntity source) {
    final Boolean isStale = scenario.isScenarioStale(source);
    return Boolean.TRUE.equals(isStale) ? StepResolution.STALE : StepResolution.RESOLVED;
  }

  private String resolutionReason(StepResolution resolution) {
    return resolution == StepResolution.STALE ? STALE_REASON : null;
  }

  private boolean hasMapping(LoadPlanStep step) {
    return step.mapping() != null;
  }

  private int countType(List<LoadPlanStep> steps, LoadPlanStepType type) {
    return Math.toIntExact(steps.stream().filter(step -> step.stepType() == type).count());
  }

  private List<String> appendPath(List<String> parentPath, String name) {
    final List<String> result = new ArrayList<>(parentPath.size() + 1);
    result.addAll(parentPath);
    result.add(name);
    return List.copyOf(result);
  }

  private String entityId(OdiLoadPlan loadPlan) {
    return String.valueOf(loadPlan.getInternalId());
  }

  private String entityId(Mapping mapping) {
    return String.valueOf(mapping.getInternalId());
  }

  private record NodeLocation(TreeNode parent, int index, boolean isRoot, String fallbackId) {}

  private record PackageMappingOccurrence(StepMapping step, Mapping mapping, int index) {}

  private record ScenarioNodeContext(OdiLoadPlanStepRunScenario step, TreeNode node, Tag tag) {}

  private record TreeNode(
      String id, String parentId, String name, List<String> path, boolean isEnabled) {
    private TreeNode {
      path = List.copyOf(path);
    }
  }
}
