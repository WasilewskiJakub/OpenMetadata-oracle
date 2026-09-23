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
import org.openmetadata.tools.odi.explorer.model.MappingColumnDerivation;
import org.openmetadata.tools.odi.explorer.model.MappingColumnDerivationType;
import org.openmetadata.tools.odi.explorer.model.MappingColumnLineage;

final class OdiColumnLineageResolver {
  private static final int MAX_EDGES = 100_000;
  private static final int MAX_RECURSION_DEPTH = 128;
  private static final int MAX_TRAVERSAL_STATES = 100_000;
  private static final int MAX_WARNINGS = 100;
  private static final String NULL_LITERAL_TEXT = "NULL";

  Resolution resolve(List<OdiEndpointScope> endpointScopes) throws MappingException {
    final ResolutionState state =
        new ResolutionState(
            endpointIds(endpointScopes, false),
            new LinkedHashSet<>(),
            new ArrayList<>(),
            new WarningCollector());
    for (final OdiEndpointScope endpointScope : endpointScopes) {
      resolveTarget(endpointScope, state);
    }
    return new Resolution(
        sortedEdges(state.edges()),
        sortedDerivations(state.derivations()),
        state.warnings().values());
  }

  private void resolveTarget(OdiEndpointScope endpointScope, ResolutionState state)
      throws MappingException {
    if (endpointScope.component().isTarget()) {
      try {
        for (final MapAttribute targetAttribute : endpointScope.component().getAttributes()) {
          resolveTargetAttribute(endpointScope, targetAttribute, state);
        }
      } catch (AdapterException exception) {
        state.warnings().add(unresolvedTargetWarning(endpointScope.componentId()));
      }
    }
  }

  private void resolveTargetAttribute(
      OdiEndpointScope endpointScope, MapAttribute targetAttribute, ResolutionState state) {
    TargetColumn target = null;
    try {
      final IColumn targetColumn = targetAttribute.getBoundColumn();
      if (targetColumn != null) {
        target = new TargetColumn(endpointScope, targetColumn);
        resolveBoundTargetAttribute(targetAttribute, target, state);
      }
    } catch (MappingException | AdapterException exception) {
      state.warnings().add(unresolvedTargetWarning(endpointScope.componentId()));
      if (target != null) {
        state.derivations().add(target.derivation(MappingColumnDerivationType.UNKNOWN, false));
      }
    }
  }

  private void resolveBoundTargetAttribute(
      MapAttribute targetAttribute, TargetColumn target, ResolutionState state)
      throws MappingException, AdapterException {
    if (targetAttribute.isActive()) {
      state.derivations().add(resolveActiveTarget(targetAttribute, target, state));
    } else {
      state.derivations().add(target.derivation(MappingColumnDerivationType.INACTIVE, true));
    }
  }

  private MappingColumnDerivation resolveActiveTarget(
      MapAttribute targetAttribute, TargetColumn target, ResolutionState state) {
    final TraceContext context =
        new TraceContext(target, state.sourceIds(), state.edges(), state.warnings());
    try {
      trace(targetAttribute, target.endpoint().scope(), context, 0);
    } catch (MappingException | AdapterException exception) {
      context.warnIncomplete();
    }
    return targetDerivation(targetAttribute, context);
  }

  private MappingColumnDerivation targetDerivation(
      MapAttribute targetAttribute, TraceContext context) {
    MappingColumnDerivationType type;
    try {
      type = derivationType(targetAttribute, context);
    } catch (MappingException | AdapterException exception) {
      context.warnIncomplete();
      type =
          context.hasSource()
              ? MappingColumnDerivationType.SOURCE_COLUMNS
              : MappingColumnDerivationType.UNKNOWN;
    }
    return context.target().derivation(type, context.isComplete());
  }

  private MappingColumnDerivationType derivationType(
      MapAttribute targetAttribute, TraceContext context)
      throws MappingException, AdapterException {
    final List<MapExpression> expressions = targetAttribute.getExpressions();
    final MappingColumnDerivationType result;
    if (context.hasSource()) {
      result = MappingColumnDerivationType.SOURCE_COLUMNS;
    } else if (!context.isComplete()) {
      result = MappingColumnDerivationType.UNKNOWN;
    } else if (expressions.isEmpty()) {
      result = MappingColumnDerivationType.UNMAPPED;
    } else if (!hasExpressionText(expressions)) {
      context.markIncomplete();
      result = MappingColumnDerivationType.UNKNOWN;
    } else if (containsOnlyNullLiterals(expressions)) {
      result = MappingColumnDerivationType.NULL_LITERAL;
    } else {
      result = MappingColumnDerivationType.SOURCELESS_EXPRESSION;
    }
    return result;
  }

  private boolean containsOnlyNullLiterals(List<MapExpression> expressions) {
    boolean containsOnlyNull = true;
    for (final MapExpression expression : expressions) {
      final String text = expression.getText();
      if (text != null && !text.isBlank()) {
        containsOnlyNull = containsOnlyNull && NULL_LITERAL_TEXT.equalsIgnoreCase(text.trim());
      }
    }
    return containsOnlyNull;
  }

  private boolean hasExpressionText(List<MapExpression> expressions) {
    return expressions.stream()
        .map(MapExpression::getText)
        .anyMatch(text -> text != null && !text.isBlank());
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
    } else if (crossReference.getReferencedObject() == null) {
      context.markIncomplete();
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

  private List<MappingColumnDerivation> sortedDerivations(
      List<MappingColumnDerivation> derivations) {
    return derivations.stream()
        .sorted(
            Comparator.comparing(MappingColumnDerivation::targetComponentId)
                .thenComparing(MappingColumnDerivation::targetColumnId))
        .toList();
  }

  private String unresolvedTargetWarning(String targetId) {
    return "Column lineage is incomplete for target '%s'.".formatted(targetId);
  }

  private record ResolutionState(
      Set<String> sourceIds,
      Set<MappingColumnLineage> edges,
      List<MappingColumnDerivation> derivations,
      WarningCollector warnings) {}

  record Resolution(
      List<MappingColumnLineage> edges,
      List<MappingColumnDerivation> derivations,
      List<String> warnings) {
    Resolution {
      edges = List.copyOf(edges);
      derivations = List.copyOf(derivations);
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

    private MappingColumnDerivation derivation(MappingColumnDerivationType type, boolean complete) {
      return new MappingColumnDerivation(componentId(), columnId(), type, complete);
    }
  }

  private static final class TraceContext {
    private final Set<MappingColumnLineage> edges;
    private final Set<String> sourceIds;
    private final TargetColumn target;
    private final Set<VisitKey> visited = new HashSet<>();
    private final WarningCollector warnings;
    private boolean complete = true;
    private boolean hasSource;

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
      hasSource = true;
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

    private TargetColumn target() {
      return target;
    }

    private boolean hasSource() {
      return hasSource;
    }

    private boolean isComplete() {
      return complete;
    }

    private void warnIncomplete() {
      markIncomplete();
      warnings.add("Column lineage is incomplete for target '%s'.".formatted(target.componentId()));
    }

    private void markIncomplete() {
      complete = false;
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
