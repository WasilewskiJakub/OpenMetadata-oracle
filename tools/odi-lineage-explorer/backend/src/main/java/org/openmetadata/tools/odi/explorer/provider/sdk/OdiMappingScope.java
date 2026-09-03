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
import java.util.List;
import java.util.StringJoiner;
import oracle.odi.domain.adapter.relational.IColumn;
import oracle.odi.domain.mapping.IMapComponent;
import oracle.odi.domain.mapping.IMapSignatureOwnerHolder;
import oracle.odi.domain.mapping.MapComponent;
import oracle.odi.domain.mapping.MapComponentDelegator;
import oracle.odi.domain.mapping.exception.MappingException;

record OdiMappingScope(List<ReusableFrame> reusableFrames) {
  OdiMappingScope {
    reusableFrames = List.copyOf(reusableFrames);
  }

  static OdiMappingScope root() {
    return new OdiMappingScope(List.of());
  }

  OdiMappingScope enter(IMapSignatureOwnerHolder holder, IMapComponent instanceComponent) {
    final List<ReusableFrame> result = new ArrayList<>(reusableFrames);
    result.add(new ReusableFrame(holder, rawComponentId(instanceComponent)));
    return new OdiMappingScope(result);
  }

  OdiMappingScope exit() {
    final int parentSize = Math.max(0, reusableFrames.size() - 1);
    return new OdiMappingScope(reusableFrames.subList(0, parentSize));
  }

  IMapSignatureOwnerHolder currentHolder() {
    return reusableFrames.isEmpty() ? null : reusableFrames.getLast().holder();
  }

  int depth() {
    return reusableFrames.size();
  }

  String componentId(IMapComponent component) {
    final StringJoiner result = new StringJoiner("/");
    reusableFrames.stream().map(ReusableFrame::instanceComponentId).forEach(result::add);
    result.add(rawComponentId(component));
    return result.toString();
  }

  String columnId(IMapComponent component, IColumn column) {
    return "%s::%s".formatted(componentId(component), column.getName());
  }

  static IMapComponent delegate(IMapComponent component) throws MappingException {
    IMapComponent result = component;
    if (component instanceof MapComponent mapComponent && mapComponent.getDelegate() != null) {
      result = mapComponent.getDelegate();
    }
    return result;
  }

  private static String rawComponentId(IMapComponent component) {
    String result = component.getAlias();
    if (component instanceof MapComponent mapComponent) {
      result = mapComponent.getQualifiedName();
    } else if (component instanceof MapComponentDelegator delegator) {
      result = delegator.getQualifiedName();
    }
    return result;
  }

  record ReusableFrame(IMapSignatureOwnerHolder holder, String instanceComponentId) {}
}
