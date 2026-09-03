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

import oracle.odi.core.persistence.IOdiEntityManager;
import oracle.odi.domain.finder.IFinder;
import oracle.odi.domain.mapping.Mapping;
import oracle.odi.domain.mapping.finder.IMappingFinder;
import oracle.odi.domain.project.OdiPackage;
import oracle.odi.domain.project.finder.IOdiPackageFinder;
import oracle.odi.domain.runtime.scenario.OdiScenario;
import oracle.odi.domain.runtime.scenario.Tag;
import oracle.odi.domain.runtime.scenario.finder.IOdiScenarioFinder;

final class OdiEntityFinderAccess implements OdiFinderAccess {
  private final IOdiEntityManager entityManager;

  OdiEntityFinderAccess(IOdiEntityManager entityManager) {
    this.entityManager = entityManager;
  }

  @Override
  public OdiScenario findScenario(Tag tag) {
    return finder(OdiScenario.class, IOdiScenarioFinder.class).findByTag(tag);
  }

  @Override
  public Mapping findMapping(Number id) {
    final IMappingFinder finder = finder(Mapping.class, IMappingFinder.class);
    return id == null ? null : (Mapping) finder.findById(id);
  }

  @Override
  public OdiPackage findPackage(Number id) {
    final IOdiPackageFinder finder = finder(OdiPackage.class, IOdiPackageFinder.class);
    return id == null ? null : (OdiPackage) finder.findById(id);
  }

  private <T extends IFinder> T finder(Class<?> entityType, Class<T> finderType) {
    return finderType.cast(entityManager.getFinder(entityType));
  }
}
