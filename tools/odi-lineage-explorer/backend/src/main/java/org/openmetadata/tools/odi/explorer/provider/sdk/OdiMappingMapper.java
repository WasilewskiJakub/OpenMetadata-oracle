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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import oracle.odi.domain.adapter.AdapterException;
import oracle.odi.domain.adapter.relational.IColumn;
import oracle.odi.domain.adapter.relational.IDataStore;
import oracle.odi.domain.mapping.IMapComponent;
import oracle.odi.domain.mapping.MapAttribute;
import oracle.odi.domain.mapping.MapComponent;
import oracle.odi.domain.mapping.MapConnector;
import oracle.odi.domain.mapping.MapConnectorPoint;
import oracle.odi.domain.mapping.Mapping;
import oracle.odi.domain.mapping.ReusableMapping;
import oracle.odi.domain.mapping.component.DatastoreComponent;
import oracle.odi.domain.mapping.component.ReusableMappingComponent;
import oracle.odi.domain.mapping.component.SignatureComponentDelegate;
import oracle.odi.domain.mapping.exception.MappingException;
import oracle.odi.domain.model.OdiDataStore;
import oracle.odi.domain.model.OdiModel;
import oracle.odi.domain.topology.OdiContext;
import oracle.odi.domain.topology.OdiDataServer;
import oracle.odi.domain.topology.OdiLogicalSchema;
import oracle.odi.domain.topology.OdiPhysicalSchema;
import oracle.odi.domain.topology.OdiTechnology;
import org.openmetadata.tools.odi.explorer.model.DatastoreIdentity;
import org.openmetadata.tools.odi.explorer.model.MappingColumn;
import org.openmetadata.tools.odi.explorer.model.MappingColumnLineage;
import org.openmetadata.tools.odi.explorer.model.MappingComponent;
import org.openmetadata.tools.odi.explorer.model.MappingDetail;
import org.openmetadata.tools.odi.explorer.model.MappingEdge;
import org.openmetadata.tools.odi.explorer.model.PhysicalLocation;
import org.openmetadata.tools.odi.explorer.provider.OdiConnectionException;

final class OdiMappingMapper {
  private static final int MAX_REUSABLE_DEPTH = 64;
  private static final String DATASTORE_SOURCE_TYPE = "DATASTORE_SOURCE";
  private static final String DATASTORE_TARGET_TYPE = "DATASTORE_TARGET";

  MappingDetail toDetail(Mapping mapping, OdiContext context) {
    MappingDetail result;
    try {
      final List<IMapComponent> odiComponents = mapping.getAllComponents();
      final OdiMappingGraph mappingGraph = discoverMappingGraph(odiComponents);
      final List<OdiEndpointScope> endpointScopes = mappingGraph.endpointScopes();
      final List<MappingComponent> components = mapComponents(endpointScopes, context);
      final OdiColumnLineageResolver.Resolution columnResolution =
          new OdiColumnLineageResolver().resolve(endpointScopes);
      final List<MappingColumnLineage> columnLineage = columnResolution.edges();
      final List<MappingEdge> edges =
          mapEdges(mappingGraph.componentScopes(), endpointScopes, columnLineage);
      result =
          new MappingDetail(
              entityId(mapping),
              mapping.getName(),
              context.getCode(),
              components,
              edges,
              columnLineage,
              columnResolution.warnings());
    } catch (MappingException | AdapterException exception) {
      throw new OdiConnectionException("Unable to read the ODI mapping graph.", exception);
    }
    return result;
  }

  private List<MappingComponent> mapComponents(
      List<OdiEndpointScope> endpointScopes, OdiContext context)
      throws MappingException, AdapterException {
    final Map<String, MappingComponent> result = new LinkedHashMap<>();
    for (final OdiEndpointScope endpointScope : endpointScopes) {
      result.putIfAbsent(endpointScope.componentId(), mapComponent(endpointScope, context));
    }
    return List.copyOf(result.values());
  }

  private MappingComponent mapComponent(OdiEndpointScope endpointScope, OdiContext context)
      throws MappingException, AdapterException {
    final IMapComponent component = endpointScope.component();
    final DatastoreIdentity datastore = mapDatastore(component, context);
    return new MappingComponent(
        endpointScope.componentId(),
        datastoreType(component),
        component.getAlias(),
        datastore,
        mapColumns(endpointScope));
  }

  private List<MappingColumn> mapColumns(OdiEndpointScope endpointScope)
      throws MappingException, AdapterException {
    final Map<String, MappingColumn> result = new LinkedHashMap<>();
    for (final MapAttribute attribute : endpointScope.component().getAttributes()) {
      addBoundColumn(endpointScope, attribute, result);
    }
    return List.copyOf(result.values());
  }

  private void addBoundColumn(
      OdiEndpointScope endpointScope, MapAttribute attribute, Map<String, MappingColumn> columns)
      throws AdapterException {
    final IColumn column = attribute.getBoundColumn();
    if (column != null) {
      final String id = endpointScope.scope().columnId(endpointScope.component(), column);
      columns.putIfAbsent(id, new MappingColumn(id, column.getName()));
    }
  }

  private DatastoreIdentity mapDatastore(IMapComponent component, OdiContext context)
      throws MappingException {
    DatastoreIdentity result = null;
    if (component instanceof DatastoreComponent datastoreComponent) {
      final IDataStore boundDatastore = datastoreComponent.getBoundDataStore();
      if (boundDatastore != null) {
        result = mapDatastore(odiDatastore(boundDatastore, component), context);
      }
    }
    return result;
  }

  private DatastoreIdentity mapDatastore(OdiDataStore datastore, OdiContext context) {
    final OdiModel model = datastore.getModel();
    final OdiLogicalSchema logicalSchema = model.getLogicalSchema();
    final PhysicalLocation location = physicalLocation(logicalSchema, context);
    return new DatastoreIdentity(
        datastore.getName(),
        datastore.getResourceName(),
        model.getName(),
        logicalSchema.getName(),
        location,
        resolutionReason(logicalSchema, context, location));
  }

  private OdiDataStore odiDatastore(IDataStore datastore, IMapComponent component) {
    OdiDataStore result = null;
    if (datastore instanceof OdiDataStore odiDataStore) {
      result = dereferenceShortcut(odiDataStore, component);
    } else if (datastore != null
        && datastore.getRealObject() instanceof OdiDataStore realDatastore) {
      result = dereferenceShortcut(realDatastore, component);
    }
    if (result == null) {
      throw new OdiConnectionException(
          "The mapping contains a datastore type unsupported by the ODI 14.1.2 reader.");
    }
    return result;
  }

  private OdiDataStore dereferenceShortcut(OdiDataStore datastore, IMapComponent component) {
    final OdiDataStore result = datastore.isShortcut() ? datastore.getRealObject() : datastore;
    if (result == null) {
      throw new OdiConnectionException(
          "Unable to resolve ODI datastore shortcut for component '%s'."
              .formatted(component.getAlias()));
    }
    return result;
  }

  private PhysicalLocation physicalLocation(OdiLogicalSchema logicalSchema, OdiContext context) {
    final OdiPhysicalSchema physicalSchema = logicalSchema.getPhysicalSchema(context);
    PhysicalLocation result = null;
    if (physicalSchema != null) {
      final OdiDataServer dataServer = physicalSchema.getDataServer();
      final OdiTechnology technology = physicalSchema.getTechnology();
      final String dataServerName = dataServer == null ? null : dataServer.getName();
      result =
          new PhysicalLocation(
              physicalSchema.getName(),
              dataServerName,
              catalogName(physicalSchema, technology),
              schemaName(physicalSchema, technology));
    }
    return result;
  }

  private String catalogName(OdiPhysicalSchema physicalSchema, OdiTechnology technology) {
    return technology != null && technology.isCatalogSupported()
        ? physicalSchema.getCatalogName()
        : null;
  }

  private String schemaName(OdiPhysicalSchema physicalSchema, OdiTechnology technology) {
    return technology != null && technology.isSchemaSupported()
        ? physicalSchema.getSchemaName()
        : null;
  }

  private String resolutionReason(
      OdiLogicalSchema logicalSchema, OdiContext context, PhysicalLocation location) {
    final String result =
        location == null
            ? "Logical schema '%s' is not mapped in context '%s'."
                .formatted(logicalSchema.getName(), context.getCode())
            : null;
    return result;
  }

  private OdiMappingGraph discoverMappingGraph(List<IMapComponent> components)
      throws MappingException, AdapterException {
    final MappingGraphBuilder result = new MappingGraphBuilder();
    final Set<ReusableMapping> activeReusableMappings =
        Collections.newSetFromMap(new IdentityHashMap<>());
    collectMappingGraph(components, OdiMappingScope.root(), activeReusableMappings, result);
    return result.build();
  }

  private void collectMappingGraph(
      List<IMapComponent> components,
      OdiMappingScope scope,
      Set<ReusableMapping> activeReusableMappings,
      MappingGraphBuilder mappingGraph)
      throws MappingException, AdapterException {
    mappingGraph.addComponentScope(new OdiComponentScope(components, scope));
    for (final IMapComponent component : components) {
      collectComponent(component, scope, activeReusableMappings, mappingGraph);
    }
  }

  private void collectComponent(
      IMapComponent component,
      OdiMappingScope scope,
      Set<ReusableMapping> activeReusableMappings,
      MappingGraphBuilder mappingGraph)
      throws MappingException, AdapterException {
    final IMapComponent delegate = OdiMappingScope.delegate(component);
    if (delegate instanceof DatastoreComponent datastore && isBoundEndpoint(datastore)) {
      mappingGraph.addEndpointScope(new OdiEndpointScope(datastore, scope));
    } else if (delegate instanceof ReusableMappingComponent reusable) {
      collectReusableMapping(reusable, scope, activeReusableMappings, mappingGraph);
    }
  }

  private boolean isBoundEndpoint(DatastoreComponent component)
      throws MappingException, AdapterException {
    return component.getBoundDataStore() != null && (component.isSource() || component.isTarget());
  }

  private void collectReusableMapping(
      ReusableMappingComponent component,
      OdiMappingScope scope,
      Set<ReusableMapping> activeReusableMappings,
      MappingGraphBuilder mappingGraph)
      throws MappingException, AdapterException {
    final ReusableMapping reusableMapping = realReusableMapping(component);
    if (scope.depth() >= MAX_REUSABLE_DEPTH) {
      throw new OdiConnectionException("Reusable Mapping nesting exceeds the supported limit.");
    } else if (reusableMapping != null && activeReusableMappings.add(reusableMapping)) {
      collectMappingGraph(
          reusableMapping.getAllComponents(),
          scope.enter(component, component),
          activeReusableMappings,
          mappingGraph);
      activeReusableMappings.remove(reusableMapping);
    }
  }

  private ReusableMapping realReusableMapping(ReusableMappingComponent component)
      throws MappingException, AdapterException {
    final ReusableMapping reusableMapping = component.getReusableMapping();
    return reusableMapping != null && reusableMapping.isShortcut()
        ? reusableMapping.getRealObject()
        : reusableMapping;
  }

  private List<MappingEdge> mapEdges(
      List<OdiComponentScope> componentScopes,
      List<OdiEndpointScope> endpointScopes,
      List<MappingColumnLineage> columnLineage)
      throws MappingException {
    final Set<MappingEdge> result = new LinkedHashSet<>();
    result.addAll(collapsedComponentEdges(componentScopes, endpointScopes));
    columnLineage.stream()
        .map(edge -> new MappingEdge(edge.fromComponentId(), edge.toComponentId()))
        .forEach(result::add);
    return result.stream().sorted(mappingEdgeComparator()).toList();
  }

  private Set<MappingEdge> collapsedComponentEdges(
      List<OdiComponentScope> componentScopes, List<OdiEndpointScope> endpointScopes)
      throws MappingException {
    final Map<String, Set<String>> adjacency = componentAdjacency(componentScopes);
    final Set<String> sourceIds = endpointIds(endpointScopes, false);
    final Set<String> targetIds = endpointIds(endpointScopes, true);
    final Set<MappingEdge> result = new LinkedHashSet<>();
    for (final String sourceId : sourceIds) {
      reachableTargets(sourceId, adjacency, targetIds).stream()
          .map(targetId -> new MappingEdge(sourceId, targetId))
          .forEach(result::add);
    }
    return result;
  }

  private Map<String, Set<String>> componentAdjacency(List<OdiComponentScope> componentScopes)
      throws MappingException {
    final Map<String, Set<String>> result = new HashMap<>();
    for (final OdiComponentScope componentScope : componentScopes) {
      addComponentScopeEdges(componentScope, result);
    }
    return result;
  }

  private void addComponentScopeEdges(
      OdiComponentScope componentScope, Map<String, Set<String>> adjacency)
      throws MappingException {
    final Set<MapConnector> connectors = Collections.newSetFromMap(new IdentityHashMap<>());
    for (final IMapComponent component : componentScope.components()) {
      collectConnectors(component, connectors);
    }
    for (final MapConnector connector : connectors) {
      final MappingEdge edge = mapEdge(connector, componentScope.scope());
      adjacency
          .computeIfAbsent(edge.fromComponentId(), ignored -> new LinkedHashSet<>())
          .add(edge.toComponentId());
    }
  }

  private Set<String> reachableTargets(
      String sourceId, Map<String, Set<String>> adjacency, Set<String> targetIds) {
    final Set<String> result = new LinkedHashSet<>();
    final Set<String> visited = new HashSet<>();
    final Deque<String> remaining = new ArrayDeque<>();
    remaining.add(sourceId);
    while (!remaining.isEmpty()) {
      final String componentId = remaining.removeFirst();
      if (visited.add(componentId)) {
        addReachableComponent(componentId, sourceId, targetIds, result);
        remaining.addAll(adjacency.getOrDefault(componentId, Set.of()));
      }
    }
    return result;
  }

  private void addReachableComponent(
      String componentId, String sourceId, Set<String> targetIds, Set<String> reachableTargets) {
    if (!componentId.equals(sourceId) && targetIds.contains(componentId)) {
      reachableTargets.add(componentId);
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

  private Comparator<MappingEdge> mappingEdgeComparator() {
    return Comparator.comparing(MappingEdge::fromComponentId)
        .thenComparing(MappingEdge::toComponentId);
  }

  private void collectConnectors(IMapComponent component, Set<MapConnector> connectors) {
    for (final MapConnectorPoint point : component.getOutputConnectorPoints()) {
      connectors.addAll(point.getFromConnectors());
    }
  }

  private MappingEdge mapEdge(MapConnector connector, OdiMappingScope scope)
      throws MappingException {
    return new MappingEdge(
        connectorComponentId(connector.getStartPoint(), scope),
        connectorComponentId(connector.getEndPoint(), scope));
  }

  private String connectorComponentId(MapConnectorPoint point, OdiMappingScope scope)
      throws MappingException {
    final MapComponent owner = point.getOwningComponent();
    final IMapComponent delegate = OdiMappingScope.delegate(owner);
    String result = scope.componentId(owner);
    if (delegate instanceof ReusableMappingComponent reusable) {
      final SignatureComponentDelegate signature = reusable.findSignatureComponent(point);
      if (signature != null) {
        result = scope.enter(reusable, owner).componentId(signature);
      }
    }
    return result;
  }

  private String datastoreType(IMapComponent component) throws MappingException {
    String result = DatastoreComponent.COMPONENT_TYPE_NAME;
    if (component.isTarget()) {
      result = DATASTORE_TARGET_TYPE;
    } else if (component.isSource()) {
      result = DATASTORE_SOURCE_TYPE;
    }
    return result;
  }

  private String entityId(Mapping mapping) {
    final Object internalId = mapping.getInternalId();
    return internalId == null ? mapping.getQualifiedName() : internalId.toString();
  }

  private static final class MappingGraphBuilder {
    private final List<OdiComponentScope> componentScopes = new ArrayList<>();
    private final List<OdiEndpointScope> endpointScopes = new ArrayList<>();

    private void addComponentScope(OdiComponentScope componentScope) {
      componentScopes.add(componentScope);
    }

    private void addEndpointScope(OdiEndpointScope endpointScope) {
      endpointScopes.add(endpointScope);
    }

    private OdiMappingGraph build() {
      return new OdiMappingGraph(endpointScopes, componentScopes);
    }
  }
}
