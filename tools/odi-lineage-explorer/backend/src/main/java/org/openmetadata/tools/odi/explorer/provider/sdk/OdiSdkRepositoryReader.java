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
import java.util.Comparator;
import java.util.List;
import oracle.odi.core.persistence.IOdiEntityManager;
import oracle.odi.domain.finder.IFinder;
import oracle.odi.domain.mapping.Mapping;
import oracle.odi.domain.mapping.finder.IMappingFinder;
import oracle.odi.domain.runtime.loadplan.OdiLoadPlan;
import oracle.odi.domain.runtime.loadplan.finder.IOdiLoadPlanFinder;
import oracle.odi.domain.topology.OdiContext;
import oracle.odi.domain.topology.OdiMasterRepositoryInfo;
import oracle.odi.domain.topology.OdiWorkRepositoryInfo;
import oracle.odi.domain.topology.finder.IOdiContextFinder;
import oracle.odi.domain.topology.finder.IOdiMasterRepositoryInfoFinder;
import oracle.odi.domain.topology.finder.IOdiWorkRepositoryInfoFinder;
import org.openmetadata.tools.odi.explorer.model.ContextInfo;
import org.openmetadata.tools.odi.explorer.model.LoadPlanDetail;
import org.openmetadata.tools.odi.explorer.model.LoadPlanSummary;
import org.openmetadata.tools.odi.explorer.model.MappingDetail;
import org.openmetadata.tools.odi.explorer.model.RepositoryInfo;
import org.openmetadata.tools.odi.explorer.provider.ResourceNotFoundException;

final class OdiSdkRepositoryReader {
  private final IOdiEntityManager entityManager;
  private final String workRepositoryName;

  OdiSdkRepositoryReader(IOdiEntityManager entityManager, String workRepositoryName) {
    this.entityManager = entityManager;
    this.workRepositoryName = workRepositoryName;
  }

  RepositoryInfo repository() {
    final OdiMasterRepositoryInfo master = masterRepositoryFinder().find();
    final OdiWorkRepositoryInfo work = workRepositoryFinder().findByName(workRepositoryName);
    final String masterName = master == null ? "MASTER" : master.getName();
    final String workName = work == null ? workRepositoryName : work.getName();
    return new RepositoryInfo("%s/%s".formatted(masterName, workName), masterName, workName);
  }

  List<ContextInfo> contexts() {
    final Collection<?> entities = contextFinder().findAll();
    final List<ContextInfo> result = new ArrayList<>(entities.size());
    for (final Object entity : entities) {
      if (entity instanceof OdiContext context) {
        result.add(
            new ContextInfo(context.getCode(), context.getName(), context.isDefaultContext()));
      }
    }
    result.sort(Comparator.comparing(ContextInfo::code));
    return List.copyOf(result);
  }

  List<LoadPlanSummary> loadPlans() {
    final Collection<?> entities = loadPlanFinder().findAll();
    final OdiLoadPlanMapper mapper = loadPlanMapper();
    final List<LoadPlanSummary> result = new ArrayList<>(entities.size());
    for (final Object entity : entities) {
      if (entity instanceof OdiLoadPlan loadPlan) {
        result.add(mapper.toSummary(loadPlan));
      }
    }
    result.sort(Comparator.comparing(LoadPlanSummary::name));
    return List.copyOf(result);
  }

  LoadPlanDetail loadPlan(String id, String contextCode) {
    requireContext(contextCode);
    final OdiLoadPlan loadPlan = (OdiLoadPlan) loadPlanFinder().findById(numericId(id));
    if (loadPlan == null) {
      throw new ResourceNotFoundException("Load plan", id);
    }
    return loadPlanMapper().toDetail(loadPlan, contextCode);
  }

  MappingDetail mapping(String id, String contextCode) {
    final OdiContext context = requireContext(contextCode);
    final Mapping mapping = (Mapping) mappingFinder().findById(numericId(id));
    if (mapping == null) {
      throw new ResourceNotFoundException("Mapping", id);
    }
    return new OdiMappingMapper().toDetail(mapping, context);
  }

  private OdiContext requireContext(String contextCode) {
    final OdiContext context = contextFinder().findByCode(contextCode);
    if (context == null) {
      throw new ResourceNotFoundException("Context", contextCode);
    }
    return context;
  }

  private Number numericId(String id) {
    Number result;
    try {
      result = Long.valueOf(id);
    } catch (NumberFormatException exception) {
      throw new ResourceNotFoundException("ODI entity", id);
    }
    return result;
  }

  private OdiLoadPlanMapper loadPlanMapper() {
    return new OdiLoadPlanMapper(new OdiEntityFinderAccess(entityManager));
  }

  private IOdiContextFinder contextFinder() {
    return finder(OdiContext.class, IOdiContextFinder.class);
  }

  private IOdiLoadPlanFinder loadPlanFinder() {
    return finder(OdiLoadPlan.class, IOdiLoadPlanFinder.class);
  }

  private IMappingFinder mappingFinder() {
    return finder(Mapping.class, IMappingFinder.class);
  }

  private IOdiMasterRepositoryInfoFinder masterRepositoryFinder() {
    return finder(OdiMasterRepositoryInfo.class, IOdiMasterRepositoryInfoFinder.class);
  }

  private IOdiWorkRepositoryInfoFinder workRepositoryFinder() {
    return finder(OdiWorkRepositoryInfo.class, IOdiWorkRepositoryInfoFinder.class);
  }

  private <T extends IFinder> T finder(Class<?> entityType, Class<T> finderType) {
    return finderType.cast(entityManager.getFinder(entityType));
  }
}
