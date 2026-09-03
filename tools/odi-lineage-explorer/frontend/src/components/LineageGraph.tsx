import { useId, useMemo, useState } from 'react';

import type {
  ColumnLineageEdge,
  MappingColumn,
  MappingDetail,
  MappingNode,
} from '../api/types';
import { Icon } from './Icon';
import {
  buildLineageLayout,
  columnAnchor,
  curvedEdgePath,
  nodeAnchor,
  type LineageNodeLayout,
} from './lineageLayout';

interface LineageGraphProps {
  mapping: MappingDetail;
  selectedNodeId: string;
  onSelectNode(node: MappingNode): void;
}

interface SelectedColumn {
  componentId: string;
  columnId: string;
}

interface SelectionProjection {
  diagramEdges: ColumnLineageEdge[];
  relatedEdgeCount: number;
  relatedNames: string[];
}

const MIN_ZOOM = 0.5;
const MAX_ZOOM = 2;
const ZOOM_STEP = 0.25;
const DEFAULT_EXPANDED_COLUMN_LIMIT = 40;
const MAX_DIAGRAM_COLUMN_EDGES = 1000;
const MAX_SUMMARY_RELATED_NAMES = 3;
const TABLE_PAGE_SIZE = 250;

function columnKey(componentId: string, columnId: string): string {
  return `${componentId}\u0000${columnId}`;
}

function isSelectedColumn(
  selected: SelectedColumn | undefined,
  componentId: string,
  columnId: string
): boolean {
  return selected?.componentId === componentId && selected.columnId === columnId;
}

function lineageTouchesColumn(edge: ColumnLineageEdge, selected: SelectedColumn): boolean {
  return (
    (edge.fromComponentId === selected.componentId && edge.fromColumnId === selected.columnId) ||
    (edge.toComponentId === selected.componentId && edge.toColumnId === selected.columnId)
  );
}

function qualifiedColumn(
  nodeById: ReadonlyMap<string, MappingNode>,
  componentId: string,
  columnId: string
): string {
  const node = nodeById.get(componentId);
  const column = node?.columns.find(({ id }) => id === columnId);
  return `${node?.label ?? componentId}.${column?.name ?? columnId}`;
}

function boundedRelatedEdges(
  edges: ColumnLineageEdge[],
  selected: SelectedColumn
): ColumnLineageEdge[] {
  const result: ColumnLineageEdge[] = [];
  for (const edge of edges) {
    if (!lineageTouchesColumn(edge, selected)) continue;
    result.push(edge);
    if (result.length === MAX_DIAGRAM_COLUMN_EDGES) break;
  }
  return result;
}

function projectSelection(
  edges: ColumnLineageEdge[],
  selected: SelectedColumn | undefined,
  nodeById: ReadonlyMap<string, MappingNode>
): SelectionProjection {
  if (!selected) return { diagramEdges: [], relatedEdgeCount: 0, relatedNames: [] };

  const diagramEdges: ColumnLineageEdge[] = [];
  const relatedNames: string[] = [];
  const seenNames = new Set<string>();
  const selectedNode = nodeById.get(selected.componentId);
  let relatedEdgeCount = 0;

  for (const edge of edges) {
    if (!lineageTouchesColumn(edge, selected)) continue;
    relatedEdgeCount += 1;
    if (diagramEdges.length < MAX_DIAGRAM_COLUMN_EDGES) diagramEdges.push(edge);

    if (relatedNames.length >= MAX_SUMMARY_RELATED_NAMES) continue;
    const relatedName = selectedNode?.kind === 'DATASTORE_SOURCE'
      ? qualifiedColumn(nodeById, edge.toComponentId, edge.toColumnId)
      : qualifiedColumn(nodeById, edge.fromComponentId, edge.fromColumnId);
    if (!seenNames.has(relatedName)) {
      seenNames.add(relatedName);
      relatedNames.push(relatedName);
    }
  }

  return { diagramEdges, relatedEdgeCount, relatedNames };
}

function selectionSummary(
  selected: SelectedColumn | undefined,
  projection: SelectionProjection,
  nodeById: ReadonlyMap<string, MappingNode>
): string {
  if (!selected) {
    return 'Wybierz kolumnę źródłową lub docelową, aby podświetlić jej zależności.';
  }

  const selectedName = qualifiedColumn(
    nodeById,
    selected.componentId,
    selected.columnId
  );
  const selectedNode = nodeById.get(selected.componentId);
  if (projection.relatedEdgeCount === 0) {
    return `${selectedName}: brak znalezionych zależności.`;
  }

  const verb = selectedNode?.kind === 'DATASTORE_SOURCE' ? 'wpływa na' : 'zależy od';
  const omittedCount = projection.relatedEdgeCount - projection.relatedNames.length;
  const omittedSuffix = omittedCount > 0
    ? ` oraz ${omittedCount} kolejnych relacji`
    : '';
  const diagramLimitSuffix = projection.relatedEdgeCount > projection.diagramEdges.length
    ? ` Na diagramie pokazano ${projection.diagramEdges.length} z ${projection.relatedEdgeCount} relacji.`
    : '';
  return `${selectedName} ${verb} ${projection.relatedNames.join(', ')}${omittedSuffix}.${diagramLimitSuffix}`;
}

function initialExpandedNodes(mapping: MappingDetail): Set<string> {
  const columnCount = mapping.nodes.reduce((total, node) => total + node.columns.length, 0);
  return columnCount <= DEFAULT_EXPANDED_COLUMN_LIMIT
    ? new Set(mapping.nodes.map(({ id }) => id))
    : new Set();
}

function ColumnButton({
  column,
  isRelated,
  isSelected,
  node,
  onSelect,
}: {
  column: MappingColumn;
  isRelated: boolean;
  isSelected: boolean;
  node: MappingNode;
  onSelect(): void;
}) {
  const action =
    node.kind === 'DATASTORE_SOURCE' ? 'Pokaż wpływ kolumny' : 'Pokaż źródła kolumny';

  return (
    <button
      aria-label={`${action} ${node.label}.${column.name}`}
      aria-pressed={isSelected}
      className={`lineage-column-button ${isSelected ? 'lineage-column-selected' : ''} ${isRelated ? 'lineage-column-related' : ''}`}
      data-related={String(isRelated)}
      onClick={onSelect}
      type="button">
      <span aria-hidden="true" className="lineage-column-port" />
      <span>{column.name}</span>
      {isRelated && !isSelected ? <span className="sr-only">Powiązana kolumna</span> : null}
    </button>
  );
}

function NodeCard({
  columnListId,
  layout,
  relatedColumnKeys,
  selectedColumn,
  selectedNodeId,
  onSelectColumn,
  onSelectNode,
  onToggle,
}: {
  columnListId: string;
  layout: LineageNodeLayout;
  relatedColumnKeys: ReadonlySet<string>;
  selectedColumn?: SelectedColumn;
  selectedNodeId: string;
  onSelectColumn(node: MappingNode, column: MappingColumn): void;
  onSelectNode(node: MappingNode): void;
  onToggle(node: MappingNode): void;
}) {
  const { node } = layout;
  const roleLabel = node.kind === 'DATASTORE_SOURCE' ? 'Źródło' : 'Target';

  return (
    <li
      className={`lineage-node-card lineage-node-${node.kind.toLocaleLowerCase()} ${node.id === selectedNodeId ? 'lineage-node-selected' : ''}`}
      style={{
        height: `${layout.height}px`,
        left: `${layout.x}px`,
        top: `${layout.y}px`,
        width: `${layout.width}px`,
      }}>
      <div className="lineage-node-header">
        <button
          aria-pressed={node.id === selectedNodeId}
          className="lineage-node-identity"
          onClick={() => onSelectNode(node)}
          type="button">
          <span className="lineage-node-icon"><Icon name="database" /></span>
          <span className="lineage-node-copy">
            <small>{roleLabel}</small>
            <strong title={node.label}>{node.label}</strong>
            {node.metadata?.alias && node.metadata.alias !== node.label ? (
              <em title={node.metadata.alias}>Alias: {node.metadata.alias}</em>
            ) : null}
          </span>
        </button>
        <button
          aria-controls={columnListId}
          aria-expanded={layout.expanded}
          aria-label={`${layout.expanded ? 'Zwiń' : 'Rozwiń'} kolumny ${node.label}`}
          className="lineage-column-toggle"
          onClick={() => onToggle(node)}
          type="button">
          <span>{node.columns.length}</span>
          <Icon className={layout.expanded ? 'toggle-icon-expanded' : ''} name="chevron-down" />
        </button>
      </div>
      {layout.expanded ? (
        <ul aria-label={`Kolumny ${node.label}`} className="lineage-column-list" id={columnListId}>
          {node.columns.length > 0 ? node.columns.map((column) => {
            const key = columnKey(node.id, column.id);
            return (
              <li key={column.id}>
                <ColumnButton
                  column={column}
                  isRelated={relatedColumnKeys.has(key)}
                  isSelected={isSelectedColumn(selectedColumn, node.id, column.id)}
                  node={node}
                  onSelect={() => onSelectColumn(node, column)}
                />
              </li>
            );
          }) : <li className="lineage-columns-empty">Brak kolumn w odpowiedzi SDK</li>}
        </ul>
      ) : null}
    </li>
  );
}

export function LineageGraph({ mapping, selectedNodeId, onSelectNode }: LineageGraphProps) {
  const markerSeed = useId().replaceAll(':', '');
  const tableArrowId = `table-arrow-${markerSeed}`;
  const columnArrowId = `column-arrow-${markerSeed}`;
  const activeArrowId = `active-arrow-${markerSeed}`;
  const [zoom, setZoom] = useState(1);
  const [expandedNodeIds, setExpandedNodeIds] = useState(() => initialExpandedNodes(mapping));
  const [selectedColumn, setSelectedColumn] = useState<SelectedColumn>();
  const [tablePage, setTablePage] = useState(0);
  const nodeById = useMemo(
    () => new Map(mapping.nodes.map((node) => [node.id, node])),
    [mapping.nodes]
  );
  const selectionProjection = useMemo(
    () => projectSelection(mapping.columnLineage, selectedColumn, nodeById),
    [mapping.columnLineage, nodeById, selectedColumn]
  );
  const relatedEdgeIds = useMemo(
    () => new Set(selectionProjection.diagramEdges.map(({ id }) => id)),
    [selectionProjection.diagramEdges]
  );
  const relatedColumnKeys = useMemo(() => {
    const keys = new Set<string>();
    selectionProjection.diagramEdges.forEach((edge) => {
      keys.add(columnKey(edge.fromComponentId, edge.fromColumnId));
      keys.add(columnKey(edge.toComponentId, edge.toColumnId));
    });
    return keys;
  }, [selectionProjection.diagramEdges]);
  const layout = useMemo(
    () => buildLineageLayout(mapping.nodes, expandedNodeIds),
    [expandedNodeIds, mapping.nodes]
  );

  const selectColumn = (node: MappingNode, column: MappingColumn) => {
    onSelectNode(node);
    if (isSelectedColumn(selectedColumn, node.id, column.id)) {
      setSelectedColumn(undefined);
      return;
    }

    const nextSelection = { componentId: node.id, columnId: column.id };
    const nextRelatedEdges = boundedRelatedEdges(mapping.columnLineage, nextSelection);
    setExpandedNodeIds((current) => {
      const next = new Set(current);
      next.add(node.id);
      nextRelatedEdges.forEach((edge) => {
        next.add(edge.fromComponentId);
        next.add(edge.toComponentId);
      });
      return next;
    });
    setSelectedColumn(nextSelection);
  };

  const toggleNode = (node: MappingNode) => {
    setExpandedNodeIds((current) => {
      const next = new Set(current);
      if (next.has(node.id)) next.delete(node.id);
      else next.add(node.id);
      return next;
    });
    if (selectedColumn?.componentId === node.id) setSelectedColumn(undefined);
  };

  const sourceLayouts = layout.nodes.filter(({ node }) => node.kind === 'DATASTORE_SOURCE');
  const targetLayouts = layout.nodes.filter(({ node }) => node.kind === 'DATASTORE_TARGET');
  const isLargeGraph = mapping.columnLineage.length > MAX_DIAGRAM_COLUMN_EDGES;
  const columnEdgesToRender = isLargeGraph
    ? selectionProjection.diagramEdges
    : mapping.columnLineage;
  const lastTablePage = Math.max(0, Math.ceil(mapping.columnLineage.length / TABLE_PAGE_SIZE) - 1);
  const activeTablePage = Math.min(tablePage, lastTablePage);
  const tablePageStart = activeTablePage * TABLE_PAGE_SIZE;
  const tablePageEnd = Math.min(
    mapping.columnLineage.length,
    tablePageStart + TABLE_PAGE_SIZE
  );
  const visibleColumnLineage = mapping.columnLineage.slice(tablePageStart, tablePageEnd);
  const summary = !selectedColumn && isLargeGraph
    ? `Graf zawiera ${mapping.columnLineage.length} zależności kolumnowych. Kliknij kolumnę, aby narysować tylko jej relacje.`
    : selectionSummary(selectedColumn, selectionProjection, nodeById);

  return (
    <>
      <div className="lineage-graph-toolbar">
        <p aria-label="Podsumowanie zaznaczonej kolumny" aria-live="polite" role="status">
          {summary}
        </p>
        <div aria-label="Powiększenie grafu" className="lineage-zoom-controls" role="group">
          <button
            aria-label="Pomniejsz graf"
            disabled={zoom <= MIN_ZOOM}
            onClick={() => setZoom((current) => Math.max(MIN_ZOOM, current - ZOOM_STEP))}
            type="button">
            <Icon name="minus" />
          </button>
          <button
            aria-label="Resetuj powiększenie grafu"
            className="lineage-zoom-value"
            disabled={zoom === 1}
            onClick={() => setZoom(1)}
            type="button">
            <span aria-hidden="true">{Math.round(zoom * 100)}%</span>
          </button>
          <button
            aria-label="Powiększ graf"
            disabled={zoom >= MAX_ZOOM}
            onClick={() => setZoom((current) => Math.min(MAX_ZOOM, current + ZOOM_STEP))}
            type="button">
            <Icon name="plus" />
          </button>
          <output
            aria-label="Poziom powiększenia grafu"
            aria-live="polite"
            className="sr-only"
            role="status">
            {Math.round(zoom * 100)}%
          </output>
        </div>
      </div>

      <div className="graph-viewport">
        <div
          className="lineage-world-frame"
          style={{ height: `${layout.height * zoom}px`, width: `${layout.width * zoom}px` }}>
          <div
            className="lineage-world"
            data-layout-direction="source-left-target-right"
            data-testid="lineage-world"
            style={{
              height: `${layout.height}px`,
              transform: `scale(${zoom})`,
              width: `${layout.width}px`,
            }}>
            <h3 className="lineage-column-heading lineage-source-heading">Źródła</h3>
            <h3 className="lineage-column-heading lineage-target-heading">Targety</h3>
            <svg
              aria-hidden="true"
              className="lineage-edges"
              focusable="false"
              height={layout.height}
              viewBox={`0 0 ${layout.width} ${layout.height}`}
              width={layout.width}>
              <defs>
                <marker id={tableArrowId} markerHeight="10" markerUnits="userSpaceOnUse" markerWidth="10" orient="auto" refX="9" refY="5"><path className="table-arrowhead" d="M0,0 L10,5 L0,10 z" /></marker>
                <marker id={columnArrowId} markerHeight="9" markerUnits="userSpaceOnUse" markerWidth="9" orient="auto" refX="8" refY="4.5"><path className="column-arrowhead" d="M0,0 L9,4.5 L0,9 z" /></marker>
                <marker id={activeArrowId} markerHeight="10" markerUnits="userSpaceOnUse" markerWidth="10" orient="auto" refX="9" refY="5"><path className="active-arrowhead" d="M0,0 L10,5 L0,10 z" /></marker>
              </defs>
              {mapping.edges.map((edge) => {
                const source = layout.byId.get(edge.from);
                const target = layout.byId.get(edge.to);
                if (!source || !target) return null;
                return (
                  <path
                    className={`graph-edge graph-table-edge ${selectedColumn ? 'graph-edge-muted' : ''}`}
                    d={curvedEdgePath(nodeAnchor(source), nodeAnchor(target))}
                    data-edge-id={edge.id}
                    key={edge.id}
                    markerEnd={`url(#${tableArrowId})`}
                  />
                );
              })}
              {columnEdgesToRender.map((edge) => {
                const sourceLayout = layout.byId.get(edge.fromComponentId);
                const targetLayout = layout.byId.get(edge.toComponentId);
                if (!sourceLayout || !targetLayout) return null;
                const source = columnAnchor(sourceLayout, edge.fromColumnId);
                const target = columnAnchor(targetLayout, edge.toColumnId);
                if (!source || !target) return null;
                const active = relatedEdgeIds.has(edge.id);
                return (
                  <path
                    className={`graph-edge graph-column-edge ${active ? 'graph-column-edge-active' : ''} ${selectedColumn && !active ? 'graph-edge-muted' : ''}`}
                    d={curvedEdgePath(source, target)}
                    data-column-edge-id={edge.id}
                    key={edge.id}
                    markerEnd={`url(#${active ? activeArrowId : columnArrowId})`}
                  />
                );
              })}
            </svg>
            <ol aria-label="Źródła mappingu" className="lineage-node-list" role="list">
              {sourceLayouts.map((nodeLayout, index) => (
                <NodeCard
                  columnListId={`source-columns-${markerSeed}-${index}`}
                  key={nodeLayout.node.id}
                  layout={nodeLayout}
                  onSelectColumn={selectColumn}
                  onSelectNode={onSelectNode}
                  onToggle={toggleNode}
                  relatedColumnKeys={relatedColumnKeys}
                  selectedColumn={selectedColumn}
                  selectedNodeId={selectedNodeId}
                />
              ))}
            </ol>
            <ol aria-label="Targety mappingu" className="lineage-node-list" role="list">
              {targetLayouts.map((nodeLayout, index) => (
                <NodeCard
                  columnListId={`target-columns-${markerSeed}-${index}`}
                  key={nodeLayout.node.id}
                  layout={nodeLayout}
                  onSelectColumn={selectColumn}
                  onSelectNode={onSelectNode}
                  onToggle={toggleNode}
                  relatedColumnKeys={relatedColumnKeys}
                  selectedColumn={selectedColumn}
                  selectedNodeId={selectedNodeId}
                />
              ))}
            </ol>
          </div>
        </div>
      </div>

      <details className="lineage-table-disclosure" open>
        <summary>Tabelaryczny odpowiednik column lineage</summary>
        <div className="table-scroll">
          <table aria-label="Tabelaryczny column lineage mappingu" className="entity-table compact-table">
            <thead><tr><th>Źródło</th><th>Kolumna źródłowa</th><th>Target</th><th>Kolumna docelowa</th></tr></thead>
            <tbody>
              {visibleColumnLineage.length > 0 ? visibleColumnLineage.map((edge) => {
                const sourceNode = nodeById.get(edge.fromComponentId);
                const targetNode = nodeById.get(edge.toComponentId);
                const sourceColumn = sourceNode?.columns.find(({ id }) => id === edge.fromColumnId);
                const targetColumn = targetNode?.columns.find(({ id }) => id === edge.toColumnId);
                if (!sourceNode || !targetNode || !sourceColumn || !targetColumn) return null;
                return (
                  <tr key={edge.id}>
                    <td>{sourceNode.label}</td>
                    <td>{sourceColumn.name}</td>
                    <td>{targetNode.label}</td>
                    <td>{targetColumn.name}</td>
                  </tr>
                );
              }) : <tr><td colSpan={4}>Brak dostępnego column lineage dla tego mappingu.</td></tr>}
            </tbody>
          </table>
        </div>
        <div className="lineage-table-footer">
          <p aria-label="Licznik relacji column lineage" aria-live="polite" role="status">
            {mapping.columnLineage.length > 0
              ? `Wyświetlono ${tablePageStart + 1}–${tablePageEnd} z ${mapping.columnLineage.length} relacji`
              : 'Wyświetlono 0 z 0 relacji'}
          </p>
          <div className="lineage-table-pagination">
            <button
              aria-label="Poprzednia strona relacji column lineage"
              className="secondary-button"
              disabled={activeTablePage === 0}
              onClick={() => setTablePage((current) => Math.max(0, current - 1))}
              type="button">
              Poprzednia
            </button>
            <button
              aria-label="Następna strona relacji column lineage"
              className="secondary-button"
              disabled={activeTablePage === lastTablePage}
              onClick={() => setTablePage((current) => Math.min(lastTablePage, current + 1))}
              type="button">
              Następna
            </button>
          </div>
        </div>
      </details>
    </>
  );
}
