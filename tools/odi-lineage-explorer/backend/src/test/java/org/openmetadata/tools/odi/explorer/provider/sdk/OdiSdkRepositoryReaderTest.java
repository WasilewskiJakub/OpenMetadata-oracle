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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import oracle.odi.core.persistence.IOdiEntityManager;
import oracle.odi.domain.finder.IFinder;
import oracle.odi.domain.mapping.Mapping;
import oracle.odi.domain.mapping.finder.IMappingFinder;
import oracle.odi.domain.project.OdiPackage;
import oracle.odi.domain.project.finder.IOdiPackageFinder;
import oracle.odi.domain.runtime.loadplan.OdiLoadPlan;
import oracle.odi.domain.runtime.loadplan.OdiLoadPlanStepSerial;
import oracle.odi.domain.runtime.loadplan.finder.IOdiLoadPlanFinder;
import oracle.odi.domain.runtime.scenario.OdiScenario;
import oracle.odi.domain.runtime.scenario.Tag;
import oracle.odi.domain.runtime.scenario.finder.IOdiScenarioFinder;
import oracle.odi.domain.topology.OdiContext;
import oracle.odi.domain.topology.OdiMasterRepositoryInfo;
import oracle.odi.domain.topology.OdiWorkRepositoryInfo;
import oracle.odi.domain.topology.finder.IOdiContextFinder;
import oracle.odi.domain.topology.finder.IOdiMasterRepositoryInfoFinder;
import oracle.odi.domain.topology.finder.IOdiWorkRepositoryInfoFinder;
import org.junit.jupiter.api.Test;
import org.openmetadata.tools.odi.explorer.model.LoadPlanStepType;
import org.openmetadata.tools.odi.explorer.provider.ResourceNotFoundException;

class OdiSdkRepositoryReaderTest {
  @Test
  void readsRepositoryContextsAndSortedLoadPlansThroughFinders() {
    final IOdiEntityManager entityManager = mock(IOdiEntityManager.class);
    final IOdiMasterRepositoryInfoFinder masterFinder = mock(IOdiMasterRepositoryInfoFinder.class);
    final IOdiWorkRepositoryInfoFinder workFinder = mock(IOdiWorkRepositoryInfoFinder.class);
    final IOdiContextFinder contextFinder = mock(IOdiContextFinder.class);
    final IOdiLoadPlanFinder loadPlanFinder = mock(IOdiLoadPlanFinder.class);
    final OdiMasterRepositoryInfo master = mock(OdiMasterRepositoryInfo.class);
    final OdiWorkRepositoryInfo work = mock(OdiWorkRepositoryInfo.class);
    final OdiContext prod = context("PROD", false);
    final OdiContext dev = context("DEV", true);
    final OdiLoadPlan second = emptyLoadPlan(2L, "Z_LOAD");
    final OdiLoadPlan first = emptyLoadPlan(1L, "A_LOAD");
    bindFinder(entityManager, OdiMasterRepositoryInfo.class, masterFinder);
    bindFinder(entityManager, OdiWorkRepositoryInfo.class, workFinder);
    bindFinder(entityManager, OdiContext.class, contextFinder);
    bindFinder(entityManager, OdiLoadPlan.class, loadPlanFinder);
    when(masterFinder.find()).thenReturn(master);
    when(workFinder.findByName("WORKREP")).thenReturn(work);
    when(master.getName()).thenReturn("MASTER_REPO");
    when(work.getName()).thenReturn("WORKREP");
    when(contextFinder.findAll()).thenReturn(List.of(prod, dev));
    when(loadPlanFinder.findAll()).thenReturn(List.of(second, first));
    final OdiSdkRepositoryReader reader = new OdiSdkRepositoryReader(entityManager, "WORKREP");

    assertThat(reader.repository().name()).isEqualTo("MASTER_REPO/WORKREP");
    assertThat(reader.contexts()).extracting(item -> item.code()).containsExactly("DEV", "PROD");
    assertThat(reader.contexts().getFirst().isDefault()).isTrue();
    assertThat(reader.loadPlans())
        .extracting(item -> item.name())
        .containsExactly("A_LOAD", "Z_LOAD");
  }

  @Test
  void readsMappingByNumericIdInTheSelectedContext() throws Exception {
    final IOdiEntityManager entityManager = mock(IOdiEntityManager.class);
    final IOdiContextFinder contextFinder = mock(IOdiContextFinder.class);
    final IMappingFinder mappingFinder = mock(IMappingFinder.class);
    final OdiContext context = context("DEV", true);
    final Mapping mapping = mock(Mapping.class);
    bindFinder(entityManager, OdiContext.class, contextFinder);
    bindFinder(entityManager, Mapping.class, mappingFinder);
    when(contextFinder.findByCode("DEV")).thenReturn(context);
    when(mappingFinder.findById(42L)).thenReturn(mapping);
    when(mapping.getInternalId()).thenReturn(42L);
    when(mapping.getName()).thenReturn("LOAD_ORDERS");
    when(mapping.getAllComponents()).thenReturn(List.of());

    final var result = new OdiSdkRepositoryReader(entityManager, "WORKREP").mapping("42", "DEV");

    assertThat(result.id()).isEqualTo("42");
    assertThat(result.name()).isEqualTo("LOAD_ORDERS");
    assertThat(result.contextCode()).isEqualTo("DEV");
  }

  @Test
  void readsLoadPlanByNumericIdAfterValidatingContext() {
    final IOdiEntityManager entityManager = mock(IOdiEntityManager.class);
    final IOdiContextFinder contextFinder = mock(IOdiContextFinder.class);
    final IOdiLoadPlanFinder loadPlanFinder = mock(IOdiLoadPlanFinder.class);
    final OdiLoadPlan loadPlan = emptyLoadPlan(7L, "DAILY_LOAD");
    final OdiContext context = context("DEV", true);
    bindFinder(entityManager, OdiContext.class, contextFinder);
    bindFinder(entityManager, OdiLoadPlan.class, loadPlanFinder);
    when(contextFinder.findByCode("DEV")).thenReturn(context);
    when(loadPlanFinder.findById(7L)).thenReturn(loadPlan);

    final var result = new OdiSdkRepositoryReader(entityManager, "WORKREP").loadPlan("7", "DEV");

    assertThat(result.id()).isEqualTo("7");
    assertThat(result.steps())
        .singleElement()
        .satisfies(
            step -> {
              assertThat(step.stepType()).isEqualTo(LoadPlanStepType.ROOT_SERIAL);
            });
  }

  @Test
  void reportsUnknownContextAndInvalidEntityIdWithoutQueryingRawTables() {
    final IOdiEntityManager entityManager = mock(IOdiEntityManager.class);
    final IOdiContextFinder contextFinder = mock(IOdiContextFinder.class);
    bindFinder(entityManager, OdiContext.class, contextFinder);
    when(contextFinder.findByCode("MISSING")).thenReturn(null);
    final OdiSdkRepositoryReader reader = new OdiSdkRepositoryReader(entityManager, "WORKREP");
    final OdiContext context = context("DEV", true);

    assertThatThrownBy(() -> reader.mapping("not-a-number", "MISSING"))
        .isInstanceOf(ResourceNotFoundException.class);
    when(contextFinder.findByCode("DEV")).thenReturn(context);
    assertThatThrownBy(() -> reader.mapping("not-a-number", "DEV"))
        .isInstanceOf(ResourceNotFoundException.class);
  }

  @Test
  void finderAccessResolvesOnlyScenarioMappingAndPackageEntities() {
    final IOdiEntityManager entityManager = mock(IOdiEntityManager.class);
    final IOdiScenarioFinder scenarioFinder = mock(IOdiScenarioFinder.class);
    final IMappingFinder mappingFinder = mock(IMappingFinder.class);
    final IOdiPackageFinder packageFinder = mock(IOdiPackageFinder.class);
    final OdiScenario scenario = mock(OdiScenario.class);
    final Mapping mapping = mock(Mapping.class);
    final OdiPackage odiPackage = mock(OdiPackage.class);
    final Tag tag = new Tag("SCEN", "001");
    bindFinder(entityManager, OdiScenario.class, scenarioFinder);
    bindFinder(entityManager, Mapping.class, mappingFinder);
    bindFinder(entityManager, OdiPackage.class, packageFinder);
    when(scenarioFinder.findByTag(tag)).thenReturn(scenario);
    when(mappingFinder.findById(5L)).thenReturn(mapping);
    when(packageFinder.findById(6L)).thenReturn(odiPackage);
    final OdiEntityFinderAccess finders = new OdiEntityFinderAccess(entityManager);

    assertThat(finders.findScenario(tag)).isSameAs(scenario);
    assertThat(finders.findMapping(5L)).isSameAs(mapping);
    assertThat(finders.findMapping(null)).isNull();
    assertThat(finders.findPackage(6L)).isSameAs(odiPackage);
    assertThat(finders.findPackage(null)).isNull();
  }

  private OdiContext context(String code, boolean isDefault) {
    final OdiContext context = mock(OdiContext.class);
    when(context.getCode()).thenReturn(code);
    when(context.getName()).thenReturn(code + " context");
    when(context.isDefaultContext()).thenReturn(isDefault);
    return context;
  }

  private OdiLoadPlan emptyLoadPlan(long id, String name) {
    final OdiLoadPlan loadPlan = mock(OdiLoadPlan.class);
    final OdiLoadPlanStepSerial root = mock(OdiLoadPlanStepSerial.class);
    when(loadPlan.getInternalId()).thenReturn(id);
    when(loadPlan.getName()).thenReturn(name);
    when(loadPlan.getDescription()).thenReturn("Description");
    when(loadPlan.getRootStep()).thenReturn(root);
    when(root.getChildrenSteps()).thenReturn(List.of());
    return loadPlan;
  }

  private void bindFinder(IOdiEntityManager entityManager, Class<?> entityType, IFinder finder) {
    when(entityManager.getFinder(entityType)).thenReturn(finder);
  }
}
