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

package org.openmetadata.tools.odi;

import java.security.CodeSource;
import java.util.List;
import oracle.jdbc.OracleDriver;
import oracle.odi.core.OdiInstance;
import oracle.odi.core.config.OdiInstanceConfig;
import oracle.odi.domain.mapping.MapAttribute;
import oracle.odi.domain.mapping.MapConnector;
import oracle.odi.domain.mapping.MapConnectorPoint;
import oracle.odi.domain.mapping.Mapping;
import oracle.odi.domain.mapping.component.AggregateComponent;
import oracle.odi.domain.mapping.component.DatastoreComponent;
import oracle.odi.domain.mapping.component.DistinctComponent;
import oracle.odi.domain.mapping.component.JoinComponent;
import oracle.odi.domain.mapping.component.LookupComponent;
import oracle.odi.domain.mapping.component.ReusableMappingComponent;
import oracle.odi.domain.mapping.component.SetComponent;
import oracle.odi.domain.mapping.expression.MapExpression;
import oracle.odi.domain.mapping.finder.IMappingFinder;

public final class OdiSdkProbe {

  private static final List<Class<?>> REQUIRED_TYPES =
      List.of(
          OdiInstance.class,
          OdiInstanceConfig.class,
          IMappingFinder.class,
          Mapping.class,
          MapConnector.class,
          MapConnectorPoint.class,
          MapAttribute.class,
          MapExpression.class,
          DatastoreComponent.class,
          AggregateComponent.class,
          DistinctComponent.class,
          JoinComponent.class,
          LookupComponent.class,
          SetComponent.class,
          ReusableMappingComponent.class,
          OracleDriver.class);

  private OdiSdkProbe() {}

  public static void main(String[] args) {
    System.out.printf("java.version=%s%n", System.getProperty("java.version"));
    REQUIRED_TYPES.forEach(OdiSdkProbe::printType);
  }

  private static void printType(Class<?> type) {
    System.out.printf(
        "type=%s methods=%d source=%s%n",
        type.getName(), type.getDeclaredMethods().length, sourceLocation(type));
  }

  private static String sourceLocation(Class<?> type) {
    CodeSource codeSource = type.getProtectionDomain().getCodeSource();
    return codeSource == null ? "unknown" : codeSource.getLocation().toString();
  }
}
