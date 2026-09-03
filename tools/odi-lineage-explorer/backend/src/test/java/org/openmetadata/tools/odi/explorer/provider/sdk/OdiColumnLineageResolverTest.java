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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import oracle.odi.domain.adapter.AdapterException;
import oracle.odi.domain.adapter.relational.IColumn;
import oracle.odi.domain.mapping.MapAttribute;
import oracle.odi.domain.mapping.MapComponent;
import oracle.odi.domain.mapping.MapConnectorPoint;
import oracle.odi.domain.mapping.component.DatastoreComponent;
import oracle.odi.domain.mapping.component.ReusableMappingComponent;
import oracle.odi.domain.mapping.expression.MapExpression;
import oracle.odi.domain.mapping.xreference.MapExpressionXRef;
import org.junit.jupiter.api.Test;
import org.openmetadata.tools.odi.explorer.model.MappingColumnLineage;

class OdiColumnLineageResolverTest {
  @Test
  void keepsTwoInstancesOfTheSameReusableMappingCompletelySeparate() throws Exception {
    final DatastoreComponent internalSource = datastore("REUSABLE.SRC", true, false);
    final DatastoreComponent targetA = datastore("MAP.TGT_A", false, true);
    final DatastoreComponent targetB = datastore("MAP.TGT_B", false, true);
    final MapAttribute internalSourceId = boundAttribute(internalSource, "ID", false);
    final MapAttribute targetAId = boundAttribute(targetA, "ID", true);
    final MapAttribute targetBId = boundAttribute(targetB, "ID", true);
    final ReusableMappingComponent holderA = mock(ReusableMappingComponent.class);
    final ReusableMappingComponent holderB = mock(ReusableMappingComponent.class);
    final MapComponent instanceA = reusableInstance("MAP.RMC_A", holderA);
    final MapComponent instanceB = reusableInstance("MAP.RMC_B", holderB);
    final MapAttribute instanceAOutput = attribute(instanceA, true);
    final MapAttribute instanceBOutput = attribute(instanceB, true);
    final MapAttribute sharedSignatureOutput = attribute(component("REUSABLE.OUTPUT"), false);

    when(targetA.getAttributes()).thenReturn(List.of(targetAId));
    when(targetB.getAttributes()).thenReturn(List.of(targetBId));
    when(holderA.findSignatureAttributeForComponentAttribute(instanceAOutput))
        .thenReturn(sharedSignatureOutput);
    when(holderB.findSignatureAttributeForComponentAttribute(instanceBOutput))
        .thenReturn(sharedSignatureOutput);
    referenceAttributes(targetAId, instanceAOutput);
    referenceAttributes(targetBId, instanceBOutput);
    referenceAttributes(sharedSignatureOutput, internalSourceId);

    final OdiMappingScope root = OdiMappingScope.root();
    final List<OdiEndpointScope> endpoints =
        List.of(
            new OdiEndpointScope(internalSource, root.enter(holderA, instanceA)),
            new OdiEndpointScope(internalSource, root.enter(holderB, instanceB)),
            new OdiEndpointScope(targetA, root),
            new OdiEndpointScope(targetB, root));

    final OdiColumnLineageResolver.Resolution result =
        new OdiColumnLineageResolver().resolve(endpoints);

    assertThat(result.edges())
        .containsExactly(
            new MappingColumnLineage(
                "MAP.RMC_A/REUSABLE.SRC",
                "MAP.RMC_A/REUSABLE.SRC::ID",
                "MAP.TGT_A",
                "MAP.TGT_A::ID"),
            new MappingColumnLineage(
                "MAP.RMC_B/REUSABLE.SRC",
                "MAP.RMC_B/REUSABLE.SRC::ID",
                "MAP.TGT_B",
                "MAP.TGT_B::ID"));
    assertThat(result.warnings()).isEmpty();
  }

  @Test
  void preservesEveryScopeLevelAcrossNestedReusableMappings() throws Exception {
    final DatastoreComponent internalSource = datastore("INNER.SRC", true, false);
    final DatastoreComponent target = datastore("MAP.TGT", false, true);
    final MapAttribute sourceId = boundAttribute(internalSource, "ID", false);
    final MapAttribute targetId = boundAttribute(target, "ID", true);
    final ReusableMappingComponent outerHolder = mock(ReusableMappingComponent.class);
    final ReusableMappingComponent innerHolder = mock(ReusableMappingComponent.class);
    final MapComponent outerInstance = reusableInstance("MAP.OUTER", outerHolder);
    final MapComponent innerInstance = reusableInstance("REUSABLE.INNER", innerHolder);
    final MapAttribute outerOutput = attribute(outerInstance, true);
    final MapAttribute innerOutput = attribute(innerInstance, true);
    final MapAttribute outerSignature = attribute(component("OUTER.OUTPUT"), false);
    final MapAttribute innerSignature = attribute(component("INNER.OUTPUT"), false);
    when(target.getAttributes()).thenReturn(List.of(targetId));
    when(outerHolder.findSignatureAttributeForComponentAttribute(outerOutput))
        .thenReturn(outerSignature);
    when(innerHolder.findSignatureAttributeForComponentAttribute(innerOutput))
        .thenReturn(innerSignature);
    referenceAttributes(targetId, outerOutput);
    referenceAttributes(outerSignature, innerOutput);
    referenceAttributes(innerSignature, sourceId);

    final OdiMappingScope root = OdiMappingScope.root();
    final OdiMappingScope sourceScope =
        root.enter(outerHolder, outerInstance).enter(innerHolder, innerInstance);
    final OdiColumnLineageResolver.Resolution result =
        new OdiColumnLineageResolver()
            .resolve(
                List.of(
                    new OdiEndpointScope(internalSource, sourceScope),
                    new OdiEndpointScope(target, root)));

    assertThat(result.edges())
        .containsExactly(
            new MappingColumnLineage(
                "MAP.OUTER/REUSABLE.INNER/INNER.SRC",
                "MAP.OUTER/REUSABLE.INNER/INNER.SRC::ID",
                "MAP.TGT",
                "MAP.TGT::ID"));
  }

  @Test
  void combinesReferencesFromEveryTargetExpression() throws Exception {
    final DatastoreComponent sourceA = datastore("MAP.SRC_A", true, false);
    final DatastoreComponent sourceB = datastore("MAP.SRC_B", true, false);
    final DatastoreComponent target = datastore("MAP.TGT", false, true);
    final MapAttribute sourceAValue = boundAttribute(sourceA, "VALUE_A", false);
    final MapAttribute sourceBValue = boundAttribute(sourceB, "VALUE_B", false);
    final MapAttribute targetValue = boundAttribute(target, "TOTAL_VALUE", true);
    final MapExpression sourceAExpression = expression(sourceAValue);
    final MapExpression sourceBExpression = expression(sourceBValue);
    when(target.getAttributes()).thenReturn(List.of(targetValue));
    when(targetValue.getExpressions()).thenReturn(List.of(sourceAExpression, sourceBExpression));

    final OdiMappingScope root = OdiMappingScope.root();
    final OdiColumnLineageResolver.Resolution result =
        new OdiColumnLineageResolver()
            .resolve(
                List.of(
                    new OdiEndpointScope(sourceA, root),
                    new OdiEndpointScope(sourceB, root),
                    new OdiEndpointScope(target, root)));

    assertThat(result.edges())
        .extracting(MappingColumnLineage::fromColumnId)
        .containsExactly("MAP.SRC_A::VALUE_A", "MAP.SRC_B::VALUE_B");
    assertThat(result.warnings()).isEmpty();
  }

  @Test
  void followsCompositeChildAttributesUsedByDatasetComponents() throws Exception {
    final DatastoreComponent source = datastore("MAP.SRC", true, false);
    final DatastoreComponent target = datastore("MAP.TGT", false, true);
    final MapAttribute sourceValue = boundAttribute(source, "VALUE", false);
    final MapAttribute targetValue = boundAttribute(target, "VALUE", true);
    when(target.getAttributes()).thenReturn(List.of(targetValue));
    when(targetValue.getCompositeChildAttribute()).thenReturn(sourceValue);

    final OdiMappingScope root = OdiMappingScope.root();
    final OdiColumnLineageResolver.Resolution result =
        new OdiColumnLineageResolver()
            .resolve(
                List.of(new OdiEndpointScope(source, root), new OdiEndpointScope(target, root)));

    assertThat(result.edges())
        .containsExactly(
            new MappingColumnLineage("MAP.SRC", "MAP.SRC::VALUE", "MAP.TGT", "MAP.TGT::VALUE"));
  }

  @Test
  void skipsInactiveTargetColumns() throws Exception {
    final DatastoreComponent source = datastore("MAP.SRC", true, false);
    final DatastoreComponent target = datastore("MAP.TGT", false, true);
    final MapAttribute sourceValue = boundAttribute(source, "VALUE", false);
    final MapAttribute inactiveTarget = boundAttribute(target, "VALUE", false);
    when(target.getAttributes()).thenReturn(List.of(inactiveTarget));
    referenceAttributes(inactiveTarget, sourceValue);

    final OdiMappingScope root = OdiMappingScope.root();
    final OdiColumnLineageResolver.Resolution result =
        new OdiColumnLineageResolver()
            .resolve(
                List.of(new OdiEndpointScope(source, root), new OdiEndpointScope(target, root)));

    assertThat(result.edges()).isEmpty();
    assertThat(result.warnings()).isEmpty();
  }

  @Test
  void ignoresNonColumnCrossReferencesWithoutInventingLineageOrWarnings() throws Exception {
    final DatastoreComponent target = datastore("MAP.TGT", false, true);
    final MapAttribute targetValue = boundAttribute(target, "VALUE", true);
    final MapExpression expression = mock(MapExpression.class);
    final MapExpressionXRef invalidReference = mock(MapExpressionXRef.class);
    when(target.getAttributes()).thenReturn(List.of(targetValue));
    when(targetValue.getExpressions()).thenReturn(List.of(expression));
    when(expression.getCrossReferences()).thenReturn(List.of(invalidReference));

    final OdiColumnLineageResolver.Resolution result =
        new OdiColumnLineageResolver()
            .resolve(List.of(new OdiEndpointScope(target, OdiMappingScope.root())));

    assertThat(result.edges()).isEmpty();
    assertThat(result.warnings()).isEmpty();
  }

  @Test
  void followsAnAttributeResolvedThroughAnOdiMapReferenceWrapper() throws Exception {
    final DatastoreComponent source = datastore("MAP.SRC", true, false);
    final DatastoreComponent target = datastore("MAP.TGT", false, true);
    final MapAttribute sourceValue = boundAttribute(source, "VALUE", false);
    final MapAttribute targetValue = boundAttribute(target, "VALUE", true);
    final MapExpression expression = mock(MapExpression.class);
    final MapExpressionXRef wrappedReference = mock(MapExpressionXRef.class);
    when(target.getAttributes()).thenReturn(List.of(targetValue));
    when(targetValue.getExpressions()).thenReturn(List.of(expression));
    when(expression.getCrossReferences()).thenReturn(List.of(wrappedReference));
    when(wrappedReference.isValid()).thenReturn(true);
    when(wrappedReference.isAttributeReference()).thenReturn(false);
    when(wrappedReference.getReferencedAttribute()).thenReturn(sourceValue);

    final OdiMappingScope root = OdiMappingScope.root();
    final OdiColumnLineageResolver.Resolution result =
        new OdiColumnLineageResolver()
            .resolve(
                List.of(new OdiEndpointScope(source, root), new OdiEndpointScope(target, root)));

    assertThat(result.edges())
        .containsExactly(
            new MappingColumnLineage("MAP.SRC", "MAP.SRC::VALUE", "MAP.TGT", "MAP.TGT::VALUE"));
  }

  @Test
  void followsAResolvedAttributeEvenWhenThePersistedXrefValidityFlagIsFalse() throws Exception {
    final DatastoreComponent source = datastore("MAP.SRC", true, false);
    final DatastoreComponent target = datastore("MAP.TGT", false, true);
    final MapAttribute sourceValue = boundAttribute(source, "VALUE", false);
    final MapAttribute targetValue = boundAttribute(target, "VALUE", true);
    final MapExpression expression = mock(MapExpression.class);
    final MapExpressionXRef persistedReference = mock(MapExpressionXRef.class);
    when(target.getAttributes()).thenReturn(List.of(targetValue));
    when(targetValue.getExpressions()).thenReturn(List.of(expression));
    when(expression.getCrossReferences()).thenReturn(List.of(persistedReference));
    when(persistedReference.isValid()).thenReturn(false);
    when(persistedReference.getReferencedAttribute()).thenReturn(sourceValue);

    final OdiMappingScope root = OdiMappingScope.root();
    final OdiColumnLineageResolver.Resolution result =
        new OdiColumnLineageResolver()
            .resolve(
                List.of(new OdiEndpointScope(source, root), new OdiEndpointScope(target, root)));

    assertThat(result.edges())
        .containsExactly(
            new MappingColumnLineage("MAP.SRC", "MAP.SRC::VALUE", "MAP.TGT", "MAP.TGT::VALUE"));
    assertThat(result.warnings()).isEmpty();
  }

  @Test
  void reportsAnSdkAttributeReadFailureWithoutExposingItsCause() throws Exception {
    final DatastoreComponent target = datastore("MAP.TGT", false, true);
    when(target.getAttributes()).thenThrow(new AdapterException("sensitive repository detail"));

    final OdiColumnLineageResolver.Resolution result =
        new OdiColumnLineageResolver()
            .resolve(List.of(new OdiEndpointScope(target, OdiMappingScope.root())));

    assertThat(result.edges()).isEmpty();
    assertThat(result.warnings())
        .containsExactly("Column lineage is incomplete for target 'MAP.TGT'.");
    assertThat(result.warnings().getFirst()).doesNotContain("sensitive repository detail");
  }

  @Test
  void terminatesAnExpressionReferenceCycle() throws Exception {
    final DatastoreComponent target = datastore("MAP.TGT", false, true);
    final MapAttribute targetValue = boundAttribute(target, "VALUE", true);
    final MapAttribute cyclicExpression = attribute(component("MAP.EXPRESSION"), false);
    when(target.getAttributes()).thenReturn(List.of(targetValue));
    referenceAttributes(targetValue, cyclicExpression);
    referenceAttributes(cyclicExpression, cyclicExpression);

    final OdiColumnLineageResolver.Resolution result =
        new OdiColumnLineageResolver()
            .resolve(List.of(new OdiEndpointScope(target, OdiMappingScope.root())));

    assertThat(result.edges()).isEmpty();
  }

  @Test
  void stopsAnExcessivelyDeepAttributeGraphWithAnExplicitWarning() throws Exception {
    final DatastoreComponent target = datastore("MAP.TGT", false, true);
    final MapAttribute targetValue = boundAttribute(target, "VALUE", true);
    final MapComponent expressionOwner = component("MAP.EXPRESSION");
    final List<MapAttribute> chain = new ArrayList<>();
    for (int index = 0; index < 130; index++) {
      chain.add(attribute(expressionOwner, false));
    }
    when(target.getAttributes()).thenReturn(List.of(targetValue));
    referenceAttributes(targetValue, chain.getFirst());
    for (int index = 0; index < chain.size() - 1; index++) {
      referenceAttributes(chain.get(index), chain.get(index + 1));
    }

    final OdiColumnLineageResolver.Resolution result =
        new OdiColumnLineageResolver()
            .resolve(List.of(new OdiEndpointScope(target, OdiMappingScope.root())));

    assertThat(result.edges()).isEmpty();
    assertThat(result.warnings())
        .containsExactly("Column lineage is incomplete for target 'MAP.TGT'.");
  }

  private DatastoreComponent datastore(String id, boolean source, boolean target) throws Exception {
    final DatastoreComponent component = mock(DatastoreComponent.class);
    when(component.getQualifiedName()).thenReturn(id);
    when(component.getAlias()).thenReturn(id);
    when(component.isSource()).thenReturn(source);
    when(component.isTarget()).thenReturn(target);
    return component;
  }

  private MapComponent component(String id) throws Exception {
    final MapComponent component = mock(MapComponent.class);
    when(component.getQualifiedName()).thenReturn(id);
    when(component.getAlias()).thenReturn(id);
    return component;
  }

  private MapComponent reusableInstance(String id, ReusableMappingComponent delegate)
      throws Exception {
    final MapComponent result = component(id);
    when(result.isSignatureOwnerHolder()).thenReturn(true);
    when(result.getDelegate()).thenReturn(delegate);
    return result;
  }

  private MapAttribute boundAttribute(
      DatastoreComponent delegate, String columnName, boolean active) throws Exception {
    final MapComponent owner = component(delegate.getQualifiedName());
    final MapAttribute attribute = attribute(owner, false);
    final IColumn column = mock(IColumn.class);
    when(owner.getDelegate()).thenReturn(delegate);
    when(attribute.getBoundColumn()).thenReturn(column);
    when(attribute.isActive()).thenReturn(active);
    when(column.getName()).thenReturn(columnName);
    return attribute;
  }

  private MapAttribute attribute(MapComponent owner, boolean output) throws Exception {
    final MapAttribute result = mock(MapAttribute.class);
    final MapConnectorPoint connectorPoint = mock(MapConnectorPoint.class);
    when(result.getOwningComponent()).thenReturn(owner);
    when(result.getOwningConnectorPoint()).thenReturn(connectorPoint);
    when(result.getExpressions()).thenReturn(List.of());
    when(connectorPoint.getOwningComponent()).thenReturn(owner);
    when(connectorPoint.isOutputPoint()).thenReturn(output);
    return result;
  }

  private void referenceAttributes(MapAttribute target, MapAttribute... sources) throws Exception {
    final MapExpression expression = expression(sources);
    when(target.getExpressions()).thenReturn(List.of(expression));
  }

  private MapExpression expression(MapAttribute... sources) throws Exception {
    final MapExpression result = mock(MapExpression.class);
    final List<MapExpressionXRef> references =
        Arrays.stream(sources).map(this::crossReference).toList();
    when(result.getCrossReferences()).thenReturn(references);
    return result;
  }

  private MapExpressionXRef crossReference(MapAttribute source) {
    final MapExpressionXRef result = mock(MapExpressionXRef.class);
    when(result.isValid()).thenReturn(true);
    when(result.isAttributeReference()).thenReturn(true);
    when(result.getReferencedAttribute()).thenReturn(source);
    return result;
  }
}
