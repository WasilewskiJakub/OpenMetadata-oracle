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

import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import oracle.odi.domain.adapter.AdapterException;
import oracle.odi.domain.adapter.relational.IColumn;
import oracle.odi.domain.mapping.IMapComponent;
import oracle.odi.domain.mapping.IMapSignatureOwnerHolder;
import oracle.odi.domain.mapping.MapAttribute;
import oracle.odi.domain.mapping.MapConnectorPoint;
import oracle.odi.domain.mapping.component.DatastoreComponent;
import oracle.odi.domain.mapping.component.InputSignature;
import oracle.odi.domain.mapping.exception.MappingException;
import oracle.odi.domain.mapping.expression.MapExpression;
import oracle.odi.domain.mapping.xreference.MapExpressionXRef;
import org.openmetadata.tools.odi.explorer.model.MappingColumnLineage;

final class OdiColumnLineageResolver {
  private static final int MAX_EDGES = 100_000;
  private static final int MAX_RECURSION_DEPTH = 128;
  private static final int MAX_TRAVERSAL_STATES = 100_000;
  private static final int MAX_WARNINGS = 100;

  Resolution resolve(List<OdiEndpointScope> endpointScopes) throws MappingException {
    final Set<String> sourceIds = endpointIds(endpointScopes, false);
    final Set<MappingColumnLineage> edges = new LinkedHashSet<>();
    final WarningCollector warnings = new WarningCollector();
    for (final OdiEndpointScope endpointScope : endpointScopes) {
      resolveTarget(endpointScope, sourceIds, edges, warnings);
    }
    return new Resolution(sortedEdges(edges), warnings.values());
  }

  private void resolveTarget(
      OdiEndpointScope endpointScope,
      Set<String> sourceIds,
      Set<MappingColumnLineage> edges,
      WarningCollector warnings)
      throws MappingException {
    if (endpointScope.component().isTarget()) {
      try {
        for (final MapAttribute targetAttribute : endpointScope.component().getAttributes()) {
          resolveTargetAttribute(endpointScope, targetAttribute, sourceIds, edges, warnings);
        }
      } catch (AdapterException exception) {
        warnings.add(unresolvedTargetWarning(endpointScope.componentId()));
      }
    }
  }

  private void resolveTargetAttribute(
      OdiEndpointScope endpointScope,
      MapAttribute targetAttribute,
      Set<String> sourceIds,
      Set<MappingColumnLineage> edges,
      WarningCollector warnings) {
    try {
      final IColumn targetColumn = targetAttribute.getBoundColumn();
      if (targetColumn != null && targetAttribute.isActive()) {
        final TargetColumn target = new TargetColumn(endpointScope, targetColumn);
        trace(
            targetAttribute,
            endpointScope.scope(),
            new TraceContext(target, sourceIds, edges, warnings),
            0);
      }
    } catch (MappingException | AdapterException exception) {
      warnings.add(unresolvedTargetWarning(endpointScope.componentId()));
    }
  }

  private void trace(MapAttribute attribute, OdiMappingScope scope, TraceContext context, int depth)
      throws MappingException, AdapterException {
    if (context.canVisit(attribute, scope, depth)) {
      final IMapComponent owner = attribute.getOwningComponent();
      if (!traceOutsideReusable(attribute, owner, scope, context, depth)
          && !traceInsideReusable(attribute, owner, scope, context, depth)
          && !emitSource(attribute, owner, scope, context)) {
        traceExpressionReferences(attribute, scope, context, depth);
        traceCompositeChild(attribute, scope, context, depth);
      }
    }
  }

  private boolean traceOutsideReusable(
      MapAttribute attribute,
      IMapComponent owner,
      OdiMappingScope scope,
      TraceContext context,
      int depth)
      throws MappingException, AdapterException {
    final IMapSignatureOwnerHolder holder = scope.currentHolder();
    final boolean result = owner != null && owner.isOfType(InputSignature.COMPONENT_TYPE_NAME);
    if (result && holder != null) {
      final MapAttribute outerAttribute =
          holder.findComponentAttributeForSignatureAttribute(attribute);
      traceBridgeAttribute(outerAttribute, scope.exit(), context, depth);
    } else if (result) {
      context.warnIncomplete();
    }
    return result;
  }

  private boolean traceInsideReusable(
      MapAttribute attribute,
      IMapComponent owner,
      OdiMappingScope scope,
      TraceContext context,
      int depth)
      throws MappingException, AdapterException {
    final MapConnectorPoint connectorPoint = attribute.getOwningConnectorPoint();
    final boolean result =
        owner != null
            && owner.isSignatureOwnerHolder()
            && connectorPoint != null
            && connectorPoint.isOutputPoint();
    if (result) {
      final IMapComponent delegate = OdiMappingScope.delegate(owner);
      if (delegate instanceof IMapSignatureOwnerHolder holder) {
        final MapAttribute signatureAttribute =
            holder.findSignatureAttributeForComponentAttribute(attribute);
        traceBridgeAttribute(signatureAttribute, scope.enter(holder, owner), context, depth);
      } else {
        context.warnIncomplete();
      }
    }
    return result;
  }

  private void traceBridgeAttribute(
      MapAttribute attribute, OdiMappingScope scope, TraceContext context, int depth)
      throws MappingException, AdapterException {
    if (attribute == null) {
      context.warnIncomplete();
    } else {
      trace(attribute, scope, context, depth + 1);
    }
  }

  private boolean emitSource(
      MapAttribute attribute, IMapComponent owner, OdiMappingScope scope, TraceContext context)
      throws MappingException, AdapterException {
    final IMapComponent delegate = owner == null ? null : OdiMappingScope.delegate(owner);
    boolean result = false;
    if (delegate instanceof DatastoreComponent datastore && datastore.isSource()) {
      final String sourceId = scope.componentId(owner);
      final IColumn sourceColumn = attribute.getBoundColumn();
      if (sourceColumn != null && context.sourceIds().contains(sourceId)) {
        context.addSource(sourceId, scope.columnId(owner, sourceColumn));
      } else {
        context.warnIncomplete();
      }
      result = true;
    }
    return result;
  }

  private void traceExpressionReferences(
      MapAttribute attribute, OdiMappingScope scope, TraceContext context, int depth)
      throws MappingException, AdapterException {
    for (final MapExpression expression : attribute.getExpressions()) {
      for (final MapExpressionXRef crossReference : expression.getCrossReferences()) {
        traceCrossReference(crossReference, scope, context, depth);
      }
    }
  }

  private void traceCrossReference(
      MapExpressionXRef crossReference, OdiMappingScope scope, TraceContext context, int depth)
      throws MappingException, AdapterException {
    final MapAttribute referencedAttribute = crossReference.getReferencedAttribute();
    if (referencedAttribute != null) {
      traceBridgeAttribute(referencedAttribute, scope, context, depth);
    }
  }

  private void traceCompositeChild(
      MapAttribute attribute, OdiMappingScope scope, TraceContext context, int depth)
      throws MappingException, AdapterException {
    final MapAttribute compositeChild = attribute.getCompositeChildAttribute();
    if (compositeChild != null) {
      trace(compositeChild, scope, context, depth + 1);
    }
  }

  private Set<String> endpointIds(List<OdiEndpointScope> endpointScopes, boolean targets)
      throws MappingException {
    final Set<String> result = new LinkedHashSet<>();
    for (final OdiEndpointScope endpointScope : endpointScopes) {
      final boolean hasRequestedRole =
          targets ? endpointScope.component().isTarget() : endpointScope.component().isSource();
      if (hasRequestedRole) {
        result.add(endpointScope.componentId());
      }
    }
    return result;
  }

  private List<MappingColumnLineage> sortedEdges(Set<MappingColumnLineage> edges) {
    return edges.stream()
        .sorted(
            Comparator.comparing(MappingColumnLineage::fromComponentId)
                .thenComparing(MappingColumnLineage::fromColumnId)
                .thenComparing(MappingColumnLineage::toComponentId)
                .thenComparing(MappingColumnLineage::toColumnId))
        .toList();
  }

  private String unresolvedTargetWarning(String targetId) {
    return "Column lineage is incomplete for target '%s'.".formatted(targetId);
  }

  record Resolution(List<MappingColumnLineage> edges, List<String> warnings) {
    Resolution {
      edges = List.copyOf(edges);
      warnings = List.copyOf(warnings);
    }
  }

  private record TargetColumn(OdiEndpointScope endpoint, IColumn column) {
    private String componentId() {
      return endpoint.componentId();
    }

    private String columnId() {
      return endpoint.scope().columnId(endpoint.component(), column);
    }
  }

  private static final class TraceContext {
    private final Set<MappingColumnLineage> edges;
    private final Set<String> sourceIds;
    private final TargetColumn target;
    private final Set<VisitKey> visited = new HashSet<>();
    private final WarningCollector warnings;

    private TraceContext(
        TargetColumn target,
        Set<String> sourceIds,
        Set<MappingColumnLineage> edges,
        WarningCollector warnings) {
      this.target = target;
      this.sourceIds = sourceIds;
      this.edges = edges;
      this.warnings = warnings;
    }

    private boolean canVisit(MapAttribute attribute, OdiMappingScope scope, int depth) {
      final VisitKey visitKey = new VisitKey(attribute, scope);
      boolean result = false;
      if (depth > MAX_RECURSION_DEPTH || edges.size() >= MAX_EDGES) {
        warnIncomplete();
      } else if (visited.contains(visitKey)) {
        result = false;
      } else if (visited.size() >= MAX_TRAVERSAL_STATES) {
        warnIncomplete();
      } else {
        result = visited.add(visitKey);
      }
      return result;
    }

    private void addSource(String sourceComponentId, String sourceColumnId) {
      if (edges.size() < MAX_EDGES) {
        edges.add(
            new MappingColumnLineage(
                sourceComponentId, sourceColumnId, target.componentId(), target.columnId()));
      } else {
        warnIncomplete();
      }
    }

    private Set<String> sourceIds() {
      return sourceIds;
    }

    private void warnIncomplete() {
      warnings.add("Column lineage is incomplete for target '%s'.".formatted(target.componentId()));
    }
  }

  private static final class VisitKey {
    private final MapAttribute attribute;
    private final List<IMapSignatureOwnerHolder> holders;

    private VisitKey(MapAttribute attribute, OdiMappingScope scope) {
      this.attribute = attribute;
      this.holders =
          scope.reusableFrames().stream().map(OdiMappingScope.ReusableFrame::holder).toList();
    }

    @Override
    public boolean equals(Object other) {
      boolean result = this == other;
      if (!result && other instanceof VisitKey visitKey) {
        result = attribute == visitKey.attribute && sameHolderIdentities(visitKey.holders);
      }
      return result;
    }

    private boolean sameHolderIdentities(List<IMapSignatureOwnerHolder> otherHolders) {
      boolean result = holders.size() == otherHolders.size();
      for (int index = 0; result && index < holders.size(); index++) {
        result = holders.get(index) == otherHolders.get(index);
      }
      return result;
    }

    @Override
    public int hashCode() {
      int result = System.identityHashCode(attribute);
      for (final IMapSignatureOwnerHolder holder : holders) {
        result = 31 * result + System.identityHashCode(holder);
      }
      return result;
    }
  }

  private static final class WarningCollector {
    private final Set<String> warnings = new LinkedHashSet<>();

    private void add(String warning) {
      if (warnings.size() < MAX_WARNINGS) {
        warnings.add(Objects.requireNonNull(warning));
      }
    }

    private List<String> values() {
      return List.copyOf(warnings);
    }
  }
}
