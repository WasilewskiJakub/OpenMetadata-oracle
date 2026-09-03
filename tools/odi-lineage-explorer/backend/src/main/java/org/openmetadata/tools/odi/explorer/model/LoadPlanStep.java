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

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import java.util.List;

public record LoadPlanStep(
    String id,
    String parentStepId,
    String name,
    LoadPlanStepType stepType,
    List<String> path,
    String declaredContextCode,
    @JsonUnwrapped ScenarioReference scenario,
    @JsonUnwrapped MappingReference mapping,
    StepResolution resolution,
    String resolutionReason,
    boolean enabled) {
  public LoadPlanStep {
    path = List.copyOf(path);
  }

  public LoadPlanStep(
      String id,
      ScenarioReference scenario,
      MappingReference mapping,
      StepResolution resolution,
      boolean enabled) {
    this(
        id,
        null,
        id,
        LoadPlanStepType.RUN_SCENARIO,
        List.of(id),
        null,
        scenario,
        mapping,
        resolution,
        null,
        enabled);
  }
}
