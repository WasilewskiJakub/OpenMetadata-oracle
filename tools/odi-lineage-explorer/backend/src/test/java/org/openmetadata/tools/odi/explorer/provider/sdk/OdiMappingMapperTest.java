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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.List;
import oracle.odi.domain.adapter.relational.IColumn;
import oracle.odi.domain.mapping.MapAttribute;
import oracle.odi.domain.mapping.MapComponent;
import oracle.odi.domain.mapping.MapConnector;
import oracle.odi.domain.mapping.MapConnectorPoint;
import oracle.odi.domain.mapping.Mapping;
import oracle.odi.domain.mapping.ReusableMapping;
import oracle.odi.domain.mapping.component.DatastoreComponent;
import oracle.odi.domain.mapping.component.FileComponent;
import oracle.odi.domain.mapping.component.InputSignature;
import oracle.odi.domain.mapping.component.OutputSignature;
import oracle.odi.domain.mapping.component.ReusableMappingComponent;
import oracle.odi.domain.mapping.expression.MapExpression;
import oracle.odi.domain.mapping.xreference.MapExpressionXRef;
import oracle.odi.domain.model.OdiDataStore;
import oracle.odi.domain.model.OdiModel;
import oracle.odi.domain.topology.OdiContext;
import oracle.odi.domain.topology.OdiDataServer;
import oracle.odi.domain.topology.OdiLogicalSchema;
import oracle.odi.domain.topology.OdiPhysicalSchema;
import oracle.odi.domain.topology.OdiTechnology;
import org.junit.jupiter.api.Test;
import org.openmetadata.tools.odi.explorer.model.MappingColumn;
import org.openmetadata.tools.odi.explorer.model.MappingColumnLineage;
import org.openmetadata.tools.odi.explorer.model.MappingComponent;
import org.openmetadata.tools.odi.explorer.model.MappingDetail;
import org.openmetadata.tools.odi.explorer.model.MappingEdge;
import org.openmetadata.tools.odi.explorer.provider.OdiConnectionException;

class OdiMappingMapperTest {
  @Test
  void exposesOnlyDatastoreEndpointsAndCollapsesTransformationPaths() throws Exception {
    final Mapping mapping = mock(Mapping.class);
    final OdiContext context = mock(OdiContext.class);
    final DatastoreComponent source = datastoreComponent("MAP.SRC", "SRC_ALIAS", false, true);
    final DatastoreComponent target = datastoreComponent("MAP.TGT", "TGT_ALIAS", true, false);
    final MapComponent filter = component("MAP.FILTER", "FILTER_ACTIVE", "Filter");
    final MapComponent sourceOwner = component("MAP.SRC", "SRC_ALIAS", "Datastore");
    final MapComponent targetOwner = component("MAP.TGT", "TGT_ALIAS", "Datastore");
    final MapConnectorPoint sourceOutput = source.getOutputConnectorPoints().getFirst();
    final MapConnectorPoint filterInput = mock(MapConnectorPoint.class);
    final MapConnectorPoint filterOutput = mock(MapConnectorPoint.class);
    final MapConnectorPoint targetInput = mock(MapConnectorPoint.class);

    when(mapping.getInternalId()).thenReturn(200L);
    when(mapping.getName()).thenReturn("FILTERED_LOAD");
    when(mapping.getAllComponents()).thenReturn(List.of(source, filter, target));
    when(filter.getOutputConnectorPoints()).thenReturn(List.of(filterOutput));
    final MapConnector sourceToFilter = connector(sourceOutput, filterInput, sourceOwner, filter);
    final MapConnector filterToTarget = connector(filterOutput, targetInput, filter, targetOwner);
    when(sourceOutput.getFromConnectors()).thenReturn(List.of(sourceToFilter));
    when(filterOutput.getFromConnectors()).thenReturn(List.of(filterToTarget));
    bindDatastore(source, context, "Source", "SOURCE_TABLE", "Model", "SOURCE_LS");
    bindDatastore(target, context, "Target", "TARGET_TABLE", "Model", "TARGET_LS");

    final MappingDetail result = new OdiMappingMapper().toDetail(mapping, context);

    assertThat(result.components())
        .extracting(MappingComponent::id)
        .containsExactly("MAP.SRC", "MAP.TGT");
    assertThat(result.edges()).containsExactly(new MappingEdge("MAP.SRC", "MAP.TGT"));
  }

  @Test
  void mapsEveryReferencedSourceColumnToTheTargetColumn() throws Exception {
    final Mapping mapping = mock(Mapping.class);
    final OdiContext context = mock(OdiContext.class);
    final DatastoreComponent orders = datastoreComponent("MAP.ORDERS", "O", false, true);
    final DatastoreComponent customers = datastoreComponent("MAP.CUSTOMERS", "C", false, true);
    final DatastoreComponent target = datastoreComponent("MAP.FACT", "F", true, false);
    final MapAttribute orderCustomerId =
        boundAttribute(orders, "MAP.ORDERS.OUT.CUSTOMER_ID", "CUSTOMER_ID");
    final MapAttribute customerId =
        boundAttribute(customers, "MAP.CUSTOMERS.OUT.CUSTOMER_ID", "CUSTOMER_ID");
    final MapAttribute customerKey =
        boundAttribute(target, "MAP.FACT.IN.CUSTOMER_KEY", "CUSTOMER_KEY");

    when(mapping.getInternalId()).thenReturn(201L);
    when(mapping.getName()).thenReturn("CUSTOMER_FACT_LOAD");
    when(mapping.getAllComponents()).thenReturn(List.of(orders, customers, target));
    when(orders.getAttributes()).thenReturn(List.of(orderCustomerId));
    when(customers.getAttributes()).thenReturn(List.of(customerId));
    when(target.getAttributes()).thenReturn(List.of(customerKey));
    referenceAttributes(customerKey, orderCustomerId, customerId);
    bindDatastore(orders, context, "Orders", "ORDERS", "Model", "SOURCE_LS");
    bindDatastore(customers, context, "Customers", "CUSTOMERS", "Model", "SOURCE_LS");
    bindDatastore(target, context, "Fact", "CUSTOMER_FACT", "Model", "TARGET_LS");

    final MappingDetail result = new OdiMappingMapper().toDetail(mapping, context);

    assertThat(result.components().getFirst().columns())
        .containsExactly(new MappingColumn("MAP.ORDERS::CUSTOMER_ID", "CUSTOMER_ID"));
    assertThat(result.columnLineage())
        .containsExactly(
            new MappingColumnLineage(
                "MAP.CUSTOMERS",
                "MAP.CUSTOMERS::CUSTOMER_ID",
                "MAP.FACT",
                "MAP.FACT::CUSTOMER_KEY"),
            new MappingColumnLineage(
                "MAP.ORDERS", "MAP.ORDERS::CUSTOMER_ID", "MAP.FACT", "MAP.FACT::CUSTOMER_KEY"));
  }

  @Test
  void discoversDatastoreTargetsInsideReusableMappingsWithTheirParentSignatureStack()
      throws Exception {
    final Mapping mapping = mock(Mapping.class);
    final OdiContext context = mock(OdiContext.class);
    final DatastoreComponent source = datastoreComponent("MAP.SRC", "SRC", false, true);
    final ReusableMappingComponent reusable = mock(ReusableMappingComponent.class);
    final ReusableMapping reusableMapping = mock(ReusableMapping.class);
    final DatastoreComponent internalTarget =
        datastoreComponent("REUSABLE.TGT", "INNER_TGT", true, false);
    final MapAttribute sourceId = boundAttribute(source, "MAP.SRC.OUT.ID", "ID");
    final MapAttribute targetId = boundAttribute(internalTarget, "REUSABLE.TGT.IN.ID", "ID");

    when(mapping.getInternalId()).thenReturn(202L);
    when(mapping.getName()).thenReturn("REUSABLE_TARGET_LOAD");
    when(mapping.getAllComponents()).thenReturn(List.of(source, reusable));
    when(reusable.getReusableMapping()).thenReturn(reusableMapping);
    when(reusableMapping.isShortcut()).thenReturn(false);
    when(reusableMapping.getAllComponents()).thenReturn(List.of(internalTarget));
    when(reusable.getQualifiedName()).thenReturn("MAP.REUSABLE");
    when(source.getAttributes()).thenReturn(List.of(sourceId));
    when(internalTarget.getAttributes()).thenReturn(List.of(targetId));
    final MapComponent inputSignatureOwner = component("REUSABLE.INPUT", "INPUT", "InputSignature");
    final MapComponent reusableOwner = component("MAP.REUSABLE", "REUSABLE", "ReusableMapping");
    final MapAttribute inputSignatureId = unboundAttribute(inputSignatureOwner, false);
    final MapAttribute reusableInputId = unboundAttribute(reusableOwner, false);
    when(inputSignatureOwner.isOfType(InputSignature.COMPONENT_TYPE_NAME)).thenReturn(true);
    when(reusableOwner.isSignatureOwnerHolder()).thenReturn(true);
    when(reusableOwner.getDelegate()).thenReturn(reusable);
    when(reusable.findComponentAttributeForSignatureAttribute(inputSignatureId))
        .thenReturn(reusableInputId);
    referenceAttributes(targetId, inputSignatureId);
    referenceAttributes(reusableInputId, sourceId);
    bindDatastore(source, context, "Source", "SOURCE_TABLE", "Model", "SOURCE_LS");
    bindDatastore(internalTarget, context, "Target", "TARGET_TABLE", "Model", "TARGET_LS");

    final MappingDetail result = new OdiMappingMapper().toDetail(mapping, context);

    assertThat(result.components())
        .extracting(MappingComponent::id)
        .containsExactly("MAP.SRC", "MAP.REUSABLE/REUSABLE.TGT");
    assertThat(result.columnLineage())
        .containsExactly(
            new MappingColumnLineage(
                "MAP.SRC",
                "MAP.SRC::ID",
                "MAP.REUSABLE/REUSABLE.TGT",
                "MAP.REUSABLE/REUSABLE.TGT::ID"));
  }

  @Test
  void collapsesReusableSignaturePathsWithoutDependingOnColumnLineage() throws Exception {
    final Mapping mapping = mock(Mapping.class);
    final OdiContext context = mock(OdiContext.class);
    final ReusableMappingComponent reusable = mock(ReusableMappingComponent.class);
    final ReusableMapping reusableMapping = mock(ReusableMapping.class);
    final DatastoreComponent internalSource =
        datastoreComponent("REUSABLE.SRC", "INNER_SRC", false, true);
    final DatastoreComponent target = datastoreComponent("MAP.TGT", "TGT", true, false);
    final OutputSignature outputSignature = mock(OutputSignature.class);
    final MapComponent internalSourceOwner = component("REUSABLE.SRC", "INNER_SRC", "Datastore");
    final MapComponent outputSignatureOwner =
        component("REUSABLE.OUTPUT", "OUTPUT", "OutputSignature");
    final MapComponent reusableOwner = component("MAP.REUSABLE", "RMC", "ReusableMapping");
    final MapComponent targetOwner = component("MAP.TGT", "TGT", "Datastore");
    final MapConnectorPoint internalOutput = internalSource.getOutputConnectorPoints().getFirst();
    final MapConnectorPoint signatureInput = mock(MapConnectorPoint.class);
    final MapConnectorPoint reusableOutput = mock(MapConnectorPoint.class);
    final MapConnectorPoint targetInput = mock(MapConnectorPoint.class);
    final MapConnector internalFlow =
        connector(internalOutput, signatureInput, internalSourceOwner, outputSignatureOwner);
    final MapConnector outerFlow =
        connector(reusableOutput, targetInput, reusableOwner, targetOwner);

    when(mapping.getInternalId()).thenReturn(203L);
    when(mapping.getName()).thenReturn("REUSABLE_SOURCE_LOAD");
    when(mapping.getAllComponents()).thenReturn(List.of(reusable, target));
    when(reusable.getQualifiedName()).thenReturn("MAP.REUSABLE");
    when(reusable.getReusableMapping()).thenReturn(reusableMapping);
    when(reusable.getOutputConnectorPoints()).thenReturn(List.of(reusableOutput));
    when(reusable.findSignatureComponent(reusableOutput)).thenReturn(outputSignature);
    when(reusableOwner.getDelegate()).thenReturn(reusable);
    when(reusableMapping.isShortcut()).thenReturn(false);
    when(reusableMapping.getAllComponents()).thenReturn(List.of(internalSource, outputSignature));
    when(outputSignature.getQualifiedName()).thenReturn("REUSABLE.OUTPUT");
    when(outputSignature.getAlias()).thenReturn("OUTPUT");
    when(outputSignature.getOutputConnectorPoints()).thenReturn(List.of());
    when(internalOutput.getFromConnectors()).thenReturn(List.of(internalFlow));
    when(reusableOutput.getFromConnectors()).thenReturn(List.of(outerFlow));
    bindDatastore(internalSource, context, "Source", "SOURCE_TABLE", "Model", "SOURCE_LS");
    bindDatastore(target, context, "Target", "TARGET_TABLE", "Model", "TARGET_LS");

    final MappingDetail result = new OdiMappingMapper().toDetail(mapping, context);

    assertThat(result.columnLineage()).isEmpty();
    assertThat(result.edges())
        .containsExactly(new MappingEdge("MAP.REUSABLE/REUSABLE.SRC", "MAP.TGT"));
  }

  @Test
  void collapsesAReusableInputSignatureIntoItsInternalTarget() throws Exception {
    final Mapping mapping = mock(Mapping.class);
    final OdiContext context = mock(OdiContext.class);
    final DatastoreComponent source = datastoreComponent("MAP.SRC", "SRC", false, true);
    final ReusableMappingComponent reusable = mock(ReusableMappingComponent.class);
    final ReusableMapping reusableMapping = mock(ReusableMapping.class);
    final InputSignature inputSignature = mock(InputSignature.class);
    final DatastoreComponent internalTarget =
        datastoreComponent("REUSABLE.TGT", "INNER_TGT", true, false);
    final MapComponent sourceOwner = component("MAP.SRC", "SRC", "Datastore");
    final MapComponent reusableOwner = component("MAP.REUSABLE", "RMC", "ReusableMapping");
    final MapComponent inputSignatureOwner = component("REUSABLE.INPUT", "INPUT", "InputSignature");
    final MapComponent internalTargetOwner = component("REUSABLE.TGT", "INNER_TGT", "Datastore");
    final MapConnectorPoint sourceOutput = source.getOutputConnectorPoints().getFirst();
    final MapConnectorPoint reusableInput = mock(MapConnectorPoint.class);
    final MapConnectorPoint signatureOutput = mock(MapConnectorPoint.class);
    final MapConnectorPoint targetInput = mock(MapConnectorPoint.class);
    final MapConnector outerFlow =
        connector(sourceOutput, reusableInput, sourceOwner, reusableOwner);
    final MapConnector internalFlow =
        connector(signatureOutput, targetInput, inputSignatureOwner, internalTargetOwner);

    when(mapping.getInternalId()).thenReturn(204L);
    when(mapping.getName()).thenReturn("REUSABLE_TARGET_LOAD");
    when(mapping.getAllComponents()).thenReturn(List.of(source, reusable));
    when(reusable.getQualifiedName()).thenReturn("MAP.REUSABLE");
    when(reusable.getReusableMapping()).thenReturn(reusableMapping);
    when(reusable.findSignatureComponent(reusableInput)).thenReturn(inputSignature);
    when(reusableOwner.getDelegate()).thenReturn(reusable);
    when(reusableMapping.isShortcut()).thenReturn(false);
    when(reusableMapping.getAllComponents()).thenReturn(List.of(inputSignature, internalTarget));
    when(inputSignature.getQualifiedName()).thenReturn("REUSABLE.INPUT");
    when(inputSignature.getAlias()).thenReturn("INPUT");
    when(inputSignature.getOutputConnectorPoints()).thenReturn(List.of(signatureOutput));
    when(sourceOutput.getFromConnectors()).thenReturn(List.of(outerFlow));
    when(signatureOutput.getFromConnectors()).thenReturn(List.of(internalFlow));
    bindDatastore(source, context, "Source", "SOURCE_TABLE", "Model", "SOURCE_LS");
    bindDatastore(internalTarget, context, "Target", "TARGET_TABLE", "Model", "TARGET_LS");

    final MappingDetail result = new OdiMappingMapper().toDetail(mapping, context);

    assertThat(result.columnLineage()).isEmpty();
    assertThat(result.edges())
        .containsExactly(new MappingEdge("MAP.SRC", "MAP.REUSABLE/REUSABLE.TGT"));
  }

  @Test
  void preservesAliasButUsesTheDatastoreResourceNameAndSelectedContext() throws Exception {
    final Mapping mapping = mock(Mapping.class);
    final OdiContext context = mock(OdiContext.class);
    final DatastoreComponent source = datastoreComponent("MAP.SRC", "ORDERS_ALIAS", false, true);
    final DatastoreComponent target = datastoreComponent("MAP.TGT", "ORDERS_TARGET", true, false);
    final MapComponent aggregate = component("MAP.AGG", "AGG_ORDERS", "Aggregate");
    final MapComponent sourceOwner = component("MAP.SRC", "ORDERS_ALIAS", "Datastore");
    final MapConnectorPoint sourceOutput = source.getConnectorPoints().getFirst();
    final MapConnectorPoint aggregateInput = mock(MapConnectorPoint.class);
    final MapConnector connector = connector(sourceOutput, aggregateInput, sourceOwner, aggregate);

    when(mapping.getInternalId()).thenReturn(77L);
    when(mapping.getName()).thenReturn("LOAD_ORDERS");
    when(mapping.getAllComponents()).thenReturn(List.of(source, aggregate, target));
    when(sourceOutput.getFromConnectors()).thenReturn(List.of(connector));

    bindDatastore(source, context, "Orders", "ORDERS", "Sales Model", "SALES_LS");
    bindDatastore(target, context, "Fact Orders", "FCT_ORDERS", "DWH Model", "DWH_LS");

    final MappingDetail result = new OdiMappingMapper().toDetail(mapping, context);

    assertThat(result.id()).isEqualTo("77");
    assertThat(result.name()).isEqualTo("LOAD_ORDERS");
    assertThat(result.contextCode()).isEqualTo("DEV");
    assertThat(result.components()).hasSize(2);
    assertThat(result.components().getFirst().id()).isEqualTo("MAP.SRC");
    assertThat(result.components().getFirst().componentAlias()).isEqualTo("ORDERS_ALIAS");
    assertThat(result.components().getFirst().datastore().resourceName()).isEqualTo("ORDERS");
    assertThat(result.components().getFirst().datastore().logicalSchema()).isEqualTo("SALES_LS");
    assertThat(result.components().getFirst().datastore().physicalLocation().physicalSchema())
        .isEqualTo("SALES_DEV_PS");
    assertThat(result.components().getFirst().datastore().physicalLocation().dataServer())
        .isEqualTo("ORACLE_DEV");
    assertThat(result.components().getFirst().datastore().physicalLocation().catalog())
        .isEqualTo("ODIPDB");
    assertThat(result.components().getFirst().datastore().physicalLocation().schema())
        .isEqualTo("SALES_DEV");
    assertThat(result.components().getFirst().datastore().resolutionReason()).isNull();
    assertThat(result.edges()).isEmpty();
  }

  @Test
  void keepsLogicalIdentityWhenContextHasNoPhysicalSchema() throws Exception {
    final Mapping mapping = mock(Mapping.class);
    final OdiContext context = mock(OdiContext.class);
    final DatastoreComponent source = datastoreComponent("MAP.SRC", "CUSTOMER_ALIAS", false, true);
    final OdiDataStore datastore = mock(OdiDataStore.class);
    final OdiModel model = mock(OdiModel.class);
    final OdiLogicalSchema logicalSchema = mock(OdiLogicalSchema.class);

    when(mapping.getInternalId()).thenReturn(91L);
    when(mapping.getName()).thenReturn("LOAD_CUSTOMERS");
    when(mapping.getAllComponents()).thenReturn(List.of(source));
    when(context.getCode()).thenReturn("QA");
    when(source.getBoundDataStore()).thenReturn(datastore);
    when(datastore.getName()).thenReturn("Customers");
    when(datastore.getResourceName()).thenReturn("CUSTOMERS");
    when(datastore.getModel()).thenReturn(model);
    when(model.getName()).thenReturn("CRM Model");
    when(model.getLogicalSchema()).thenReturn(logicalSchema);
    when(logicalSchema.getName()).thenReturn("CRM_LS");
    when(logicalSchema.getPhysicalSchema(context)).thenReturn(null);

    final MappingDetail result = new OdiMappingMapper().toDetail(mapping, context);

    assertThat(result.components().getFirst().datastore().logicalSchema()).isEqualTo("CRM_LS");
    assertThat(result.components().getFirst().datastore().physicalLocation()).isNull();
    assertThat(result.components().getFirst().datastore().resolutionReason())
        .isEqualTo("Logical schema 'CRM_LS' is not mapped in context 'QA'.");
  }

  @Test
  void readsOnlyPhysicalNamesSupportedByTheResolvedTechnology() throws Exception {
    final Mapping mapping = mock(Mapping.class);
    final OdiContext context = mock(OdiContext.class);
    final DatastoreComponent source = datastoreComponent("MAP.SRC", "COUNTRY_ALIAS", false, true);
    final OdiPhysicalSchema physicalSchema =
        bindDatastore(source, context, "Country", "COUNTRY", "CRM Model", "CRM_LS");
    final OdiTechnology technology = mock(OdiTechnology.class);
    when(mapping.getInternalId()).thenReturn(92L);
    when(mapping.getName()).thenReturn("GOSIA_COUNTRY_SRC_MAP");
    when(mapping.getAllComponents()).thenReturn(List.of(source));
    when(physicalSchema.getTechnology()).thenReturn(technology);
    when(technology.isCatalogSupported()).thenReturn(false);
    when(technology.isSchemaSupported()).thenReturn(true);

    final MappingDetail result = new OdiMappingMapper().toDetail(mapping, context);

    assertThat(result.components().getFirst().datastore().physicalLocation().catalog()).isNull();
    assertThat(result.components().getFirst().datastore().physicalLocation().schema())
        .isEqualTo("SALES_DEV");
    verify(physicalSchema, never()).getCatalogName();
  }

  @Test
  void classifiesMultipleDatastoresBySdkGraphRolesInsteadOfTheirDefaultPortShape()
      throws Exception {
    final Mapping mapping = mock(Mapping.class);
    final OdiContext context = mock(OdiContext.class);
    final DatastoreComponent sourceOne = datastoreComponent("MAP.SRC1", "SRC_ONE", true, true);
    final DatastoreComponent sourceTwo = datastoreComponent("MAP.SRC2", "SRC_TWO", true, true);
    final DatastoreComponent targetOne = datastoreComponent("MAP.TGT1", "TGT_ONE", true, true);
    final DatastoreComponent targetTwo = datastoreComponent("MAP.TGT2", "TGT_TWO", true, true);
    when(mapping.getInternalId()).thenReturn(93L);
    when(mapping.getName()).thenReturn("D_PP_AU_SRC_MAP");
    when(mapping.getAllComponents())
        .thenReturn(List.of(sourceOne, sourceTwo, targetOne, targetTwo));
    when(sourceOne.isSource()).thenReturn(true);
    when(sourceTwo.isSource()).thenReturn(true);
    when(targetOne.isTarget()).thenReturn(true);
    when(targetTwo.isTarget()).thenReturn(true);
    bindDatastore(sourceOne, context, "Source One", "SRC_ONE", "Model", "LS");
    bindDatastore(sourceTwo, context, "Source Two", "SRC_TWO", "Model", "LS");
    bindDatastore(targetOne, context, "Target One", "TGT_ONE", "Model", "LS");
    bindDatastore(targetTwo, context, "Target Two", "TGT_TWO", "Model", "LS");

    final MappingDetail result = new OdiMappingMapper().toDetail(mapping, context);

    assertThat(result.components())
        .extracting(MappingComponent::componentType)
        .containsExactly(
            "DATASTORE_SOURCE", "DATASTORE_SOURCE", "DATASTORE_TARGET", "DATASTORE_TARGET");
  }

  @Test
  void omitsADisconnectedDatastoreWithoutASourceOrTargetRole() throws Exception {
    final Mapping mapping = mock(Mapping.class);
    final OdiContext context = mock(OdiContext.class);
    final DatastoreComponent disconnected =
        datastoreComponent("MAP.UNCONNECTED", "UNCONNECTED", true, true);
    when(mapping.getInternalId()).thenReturn(94L);
    when(mapping.getName()).thenReturn("UNCONNECTED_DATASTORE_MAP");
    when(mapping.getAllComponents()).thenReturn(List.of(disconnected));
    bindDatastore(disconnected, context, "Unused", "UNUSED", "Model", "LS");

    final MappingDetail result = new OdiMappingMapper().toDetail(mapping, context);

    assertThat(result.components()).isEmpty();
  }

  @Test
  void omitsEdgesWhoseEndpointsAreOnlyTransformationComponents() throws Exception {
    final Mapping mapping = mock(Mapping.class);
    final OdiContext context = mock(OdiContext.class);
    final MapComponent source = component("MAP.SRC", "SOURCE", "Expression");
    final MapComponent target = component("MAP.TGT", "TARGET", "Expression");
    final MapConnectorPoint sourceOutput = mock(MapConnectorPoint.class);
    final MapConnectorPoint targetInput = mock(MapConnectorPoint.class);
    final MapConnector connector = connector(sourceOutput, targetInput, source, target);
    when(mapping.getInternalId()).thenReturn(95L);
    when(mapping.getName()).thenReturn("FAN_OUT_MAP");
    when(mapping.getAllComponents()).thenReturn(List.of(source, target));
    when(source.getOutputConnectorPoints()).thenReturn(List.of(sourceOutput));
    when(target.getOutputConnectorPoints()).thenReturn(List.of());
    when(sourceOutput.getFromConnectors()).thenReturn(List.of(connector));

    final MappingDetail result = new OdiMappingMapper().toDetail(mapping, context);

    assertThat(result.edges()).isEmpty();
  }

  @Test
  void omitsUnknownSdkComponentsInsteadOfPublishingSyntheticEntities() throws Exception {
    final Mapping mapping = mock(Mapping.class);
    final OdiContext context = mock(OdiContext.class);
    final MapComponent component = component("MAP.LOOKUP", "CUSTOMER_LOOKUP", "LookupComponent");
    when(mapping.getInternalId()).thenReturn(99L);
    when(mapping.getName()).thenReturn("LOOKUP_TEST");
    when(mapping.getAllComponents()).thenReturn(List.of(component));
    when(context.getCode()).thenReturn("DEV");

    final MappingDetail result = new OdiMappingMapper().toDetail(mapping, context);

    assertThat(result.components()).isEmpty();
  }

  @Test
  void omitsUnboundDatastoreComponentWithoutGuessingPhysicalMetadata() throws Exception {
    final Mapping mapping = mock(Mapping.class);
    final OdiContext context = mock(OdiContext.class);
    final DatastoreComponent component =
        datastoreComponent("MAP.DELETED", "DELETED_SOURCE", false, true);
    when(mapping.getInternalId()).thenReturn(100L);
    when(mapping.getName()).thenReturn("DELETED_SOURCE_TEST");
    when(mapping.getAllComponents()).thenReturn(List.of(component));
    when(context.getCode()).thenReturn("DEV");
    when(component.getBoundDataStore()).thenReturn(null);

    final MappingDetail result = new OdiMappingMapper().toDetail(mapping, context);

    assertThat(result.components()).isEmpty();
  }

  @Test
  void dereferencesShortcutBeforeReadingCanonicalDatastoreMetadata() throws Exception {
    final Mapping mapping = mock(Mapping.class);
    final OdiContext context = mock(OdiContext.class);
    final DatastoreComponent source = datastoreComponent("MAP.SRC", "ORDERS_ALIAS", false, true);
    final OdiDataStore shortcut = mock(OdiDataStore.class);
    final OdiDataStore realDatastore = mock(OdiDataStore.class);
    when(mapping.getInternalId()).thenReturn(101L);
    when(mapping.getName()).thenReturn("SHORTCUT_TEST");
    when(mapping.getAllComponents()).thenReturn(List.of(source));
    when(source.getBoundDataStore()).thenReturn(shortcut);
    when(shortcut.isShortcut()).thenReturn(true);
    when(shortcut.getRealObject()).thenReturn(realDatastore);
    bindDatastore(realDatastore, context, "Orders", "ORDERS", "Sales Model", "SALES_LS");

    final MappingDetail result = new OdiMappingMapper().toDetail(mapping, context);

    assertThat(result.components().getFirst().datastore().resourceName()).isEqualTo("ORDERS");
    verify(shortcut).getRealObject();
    verify(shortcut, never()).getResourceName();
    verify(shortcut, never()).getModel();
  }

  @Test
  void rejectsShortcutWhoseRealDatastoreCannotBeResolved() throws Exception {
    final Mapping mapping = mock(Mapping.class);
    final OdiContext context = mock(OdiContext.class);
    final DatastoreComponent source = datastoreComponent("MAP.SRC", "BROKEN_ALIAS", false, true);
    final OdiDataStore shortcut = mock(OdiDataStore.class);
    when(mapping.getName()).thenReturn("BROKEN_SHORTCUT_TEST");
    when(mapping.getAllComponents()).thenReturn(List.of(source));
    when(source.getBoundDataStore()).thenReturn(shortcut);
    when(shortcut.isShortcut()).thenReturn(true);
    when(shortcut.getRealObject()).thenReturn(null);

    assertThatThrownBy(() -> new OdiMappingMapper().toDetail(mapping, context))
        .isInstanceOf(OdiConnectionException.class)
        .hasMessage("Unable to resolve ODI datastore shortcut for component 'BROKEN_ALIAS'.");

    verify(shortcut, never()).getResourceName();
    verify(shortcut, never()).getModel();
  }

  @Test
  void omitsFileComponentWithoutCanonicalDatastoreMetadata() throws Exception {
    final Mapping mapping = mock(Mapping.class);
    final OdiContext context = mock(OdiContext.class);
    final FileComponent file = mock(FileComponent.class);
    when(mapping.getInternalId()).thenReturn(102L);
    when(mapping.getName()).thenReturn("FILE_TEST");
    when(mapping.getAllComponents()).thenReturn(List.of(file));
    when(context.getCode()).thenReturn("DEV");
    when(file.getQualifiedName()).thenReturn("MAP.FILE");
    when(file.getAlias()).thenReturn("FILE_SOURCE");
    when(file.getComponentTypeName()).thenReturn("FILTERED_FILE");
    when(file.getConnectorPoints()).thenReturn(List.of());

    final MappingDetail result = new OdiMappingMapper().toDetail(mapping, context);

    assertThat(result.components()).isEmpty();
    verify(file, never()).getBoundDataStore();
  }

  private DatastoreComponent datastoreComponent(
      String qualifiedName, String alias, boolean isInput, boolean isOutput) throws Exception {
    final DatastoreComponent component = mock(DatastoreComponent.class);
    final MapConnectorPoint connectorPoint = mock(MapConnectorPoint.class);
    when(component.getQualifiedName()).thenReturn(qualifiedName);
    when(component.getAlias()).thenReturn(alias);
    when(component.getConnectorPoints()).thenReturn(List.of(connectorPoint));
    when(component.getOutputConnectorPoints())
        .thenReturn(isOutput ? List.of(connectorPoint) : List.of());
    when(component.isSource()).thenReturn(isOutput && !isInput);
    when(component.isTarget()).thenReturn(isInput && !isOutput);
    when(connectorPoint.isInputPoint()).thenReturn(isInput);
    when(connectorPoint.isOutputPoint()).thenReturn(isOutput);
    when(connectorPoint.getOwningComponent()).thenReturn(mock(MapComponent.class));
    return component;
  }

  private MapComponent component(String qualifiedName, String alias, String typeName)
      throws Exception {
    final MapComponent component = mock(MapComponent.class);
    when(component.getQualifiedName()).thenReturn(qualifiedName);
    when(component.getAlias()).thenReturn(alias);
    when(component.getComponentTypeName()).thenReturn(typeName);
    when(component.getConnectorPoints()).thenReturn(List.of());
    when(component.getOutputConnectorPoints()).thenReturn(List.of());
    return component;
  }

  private MapConnector connector(
      MapConnectorPoint start, MapConnectorPoint end, MapComponent source, MapComponent target) {
    final MapConnector connector = mock(MapConnector.class);
    when(start.getOwningComponent()).thenReturn(source);
    when(end.getOwningComponent()).thenReturn(target);
    when(connector.getStartPoint()).thenReturn(start);
    when(connector.getEndPoint()).thenReturn(end);
    return connector;
  }

  private MapAttribute boundAttribute(
      DatastoreComponent component, String qualifiedName, String columnName) throws Exception {
    final MapComponent owner =
        component(component.getQualifiedName(), component.getAlias(), "Datastore");
    when(owner.getDelegate()).thenReturn(component);
    final MapAttribute attribute = mock(MapAttribute.class);
    final IColumn column = mock(IColumn.class);
    final MapConnectorPoint connectorPoint = connectorPoint(owner, false);
    when(attribute.getQualifiedName()).thenReturn(qualifiedName);
    when(attribute.getOwningComponent()).thenReturn(owner);
    when(attribute.getOwningConnectorPoint()).thenReturn(connectorPoint);
    when(attribute.getBoundColumn()).thenReturn(column);
    when(attribute.isActive()).thenReturn(true);
    when(attribute.getExpressions()).thenReturn(List.of());
    when(column.getName()).thenReturn(columnName);
    return attribute;
  }

  private MapAttribute unboundAttribute(MapComponent owner, boolean output) throws Exception {
    final MapAttribute attribute = mock(MapAttribute.class);
    final MapConnectorPoint connectorPoint = connectorPoint(owner, output);
    when(attribute.getOwningComponent()).thenReturn(owner);
    when(attribute.getOwningConnectorPoint()).thenReturn(connectorPoint);
    when(attribute.getExpressions()).thenReturn(List.of());
    return attribute;
  }

  private MapConnectorPoint connectorPoint(MapComponent owner, boolean output) {
    final MapConnectorPoint connectorPoint = mock(MapConnectorPoint.class);
    when(connectorPoint.getOwningComponent()).thenReturn(owner);
    when(connectorPoint.isOutputPoint()).thenReturn(output);
    return connectorPoint;
  }

  private void referenceAttributes(MapAttribute target, MapAttribute... sources) throws Exception {
    final MapExpression expression = mock(MapExpression.class);
    final List<MapExpressionXRef> crossReferences =
        Arrays.stream(sources).map(this::crossReference).toList();
    when(expression.getCrossReferences()).thenReturn(crossReferences);
    when(target.getExpressions()).thenReturn(List.of(expression));
  }

  private MapExpressionXRef crossReference(MapAttribute source) {
    final MapExpressionXRef crossReference = mock(MapExpressionXRef.class);
    when(crossReference.isValid()).thenReturn(true);
    when(crossReference.isAttributeReference()).thenReturn(true);
    when(crossReference.getReferencedAttribute()).thenReturn(source);
    return crossReference;
  }

  private OdiPhysicalSchema bindDatastore(
      DatastoreComponent component,
      OdiContext context,
      String datastoreName,
      String resourceName,
      String modelName,
      String logicalSchemaName)
      throws Exception {
    final OdiDataStore datastore = mock(OdiDataStore.class);
    final OdiPhysicalSchema physicalSchema =
        bindDatastore(
            datastore, context, datastoreName, resourceName, modelName, logicalSchemaName);
    when(component.getBoundDataStore()).thenReturn(datastore);
    return physicalSchema;
  }

  private OdiPhysicalSchema bindDatastore(
      OdiDataStore datastore,
      OdiContext context,
      String datastoreName,
      String resourceName,
      String modelName,
      String logicalSchemaName) {
    final OdiModel model = mock(OdiModel.class);
    final OdiLogicalSchema logicalSchema = mock(OdiLogicalSchema.class);
    final OdiPhysicalSchema physicalSchema = mock(OdiPhysicalSchema.class);
    final OdiDataServer dataServer = mock(OdiDataServer.class);
    final OdiTechnology technology = mock(OdiTechnology.class);
    when(context.getCode()).thenReturn("DEV");
    when(datastore.isShortcut()).thenReturn(false);
    when(datastore.getName()).thenReturn(datastoreName);
    when(datastore.getResourceName()).thenReturn(resourceName);
    when(datastore.getModel()).thenReturn(model);
    when(model.getName()).thenReturn(modelName);
    when(model.getLogicalSchema()).thenReturn(logicalSchema);
    when(logicalSchema.getName()).thenReturn(logicalSchemaName);
    when(logicalSchema.getPhysicalSchema(context)).thenReturn(physicalSchema);
    when(physicalSchema.getName()).thenReturn("SALES_DEV_PS");
    when(physicalSchema.getDataServer()).thenReturn(dataServer);
    when(physicalSchema.getTechnology()).thenReturn(technology);
    when(technology.isCatalogSupported()).thenReturn(true);
    when(technology.isSchemaSupported()).thenReturn(true);
    when(dataServer.getName()).thenReturn("ORACLE_DEV");
    when(physicalSchema.getCatalogName()).thenReturn("ODIPDB");
    when(physicalSchema.getSchemaName()).thenReturn("SALES_DEV");
    return physicalSchema;
  }
}
