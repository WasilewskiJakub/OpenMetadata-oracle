import type { MappingNode } from '../api/types';

export const LINEAGE_WORLD_WIDTH = 1120;

const WORLD_MIN_HEIGHT = 460;
const WORLD_PADDING_X = 36;
const WORLD_PADDING_TOP = 64;
const WORLD_PADDING_BOTTOM = 44;
const NODE_WIDTH = 320;
const NODE_GAP = 28;
const NODE_HEADER_HEIGHT = 76;
const COLUMN_ROW_HEIGHT = 36;
const COLUMN_LIST_PADDING = 8;

export interface Point {
  x: number;
  y: number;
}

export interface LineageNodeLayout {
  node: MappingNode;
  x: number;
  y: number;
  width: number;
  height: number;
  expanded: boolean;
}

export interface LineageLayout {
  width: number;
  height: number;
  nodes: LineageNodeLayout[];
  byId: Map<string, LineageNodeLayout>;
}

function nodeHeight(node: MappingNode, expanded: boolean): number {
  if (!expanded) return NODE_HEADER_HEIGHT;
  return NODE_HEADER_HEIGHT + COLUMN_LIST_PADDING * 2 + Math.max(node.columns.length, 1) * COLUMN_ROW_HEIGHT;
}

function layoutColumn(
  nodes: MappingNode[],
  x: number,
  expandedNodeIds: ReadonlySet<string>
): LineageNodeLayout[] {
  let y = WORLD_PADDING_TOP;
  return nodes.map((node) => {
    const expanded = expandedNodeIds.has(node.id);
    const height = nodeHeight(node, expanded);
    const result = { node, x, y, width: NODE_WIDTH, height, expanded };
    y += height + NODE_GAP;
    return result;
  });
}

export function buildLineageLayout(
  nodes: MappingNode[],
  expandedNodeIds: ReadonlySet<string>
): LineageLayout {
  const sources = nodes.filter(({ kind }) => kind === 'DATASTORE_SOURCE');
  const targets = nodes.filter(({ kind }) => kind === 'DATASTORE_TARGET');
  const sourceLayouts = layoutColumn(sources, WORLD_PADDING_X, expandedNodeIds);
  const targetLayouts = layoutColumn(
    targets,
    LINEAGE_WORLD_WIDTH - WORLD_PADDING_X - NODE_WIDTH,
    expandedNodeIds
  );
  const layouts = [...sourceLayouts, ...targetLayouts];
  const contentBottom = layouts.reduce(
    (maximum, node) => Math.max(maximum, node.y + node.height),
    0
  );

  return {
    width: LINEAGE_WORLD_WIDTH,
    height: Math.max(WORLD_MIN_HEIGHT, contentBottom + WORLD_PADDING_BOTTOM),
    nodes: layouts,
    byId: new Map(layouts.map((node) => [node.node.id, node])),
  };
}

export function nodeAnchor(layout: LineageNodeLayout): Point {
  return {
    x: layout.node.kind === 'DATASTORE_SOURCE' ? layout.x + layout.width : layout.x,
    y: layout.y + NODE_HEADER_HEIGHT / 2,
  };
}

export function columnAnchor(
  layout: LineageNodeLayout,
  columnId: string
): Point | undefined {
  if (!layout.expanded) return undefined;
  const columnIndex = layout.node.columns.findIndex(({ id }) => id === columnId);
  if (columnIndex < 0) return undefined;

  return {
    x: layout.node.kind === 'DATASTORE_SOURCE' ? layout.x + layout.width : layout.x,
    y:
      layout.y +
      NODE_HEADER_HEIGHT +
      COLUMN_LIST_PADDING +
      columnIndex * COLUMN_ROW_HEIGHT +
      COLUMN_ROW_HEIGHT / 2,
  };
}

export function curvedEdgePath(source: Point, target: Point): string {
  const controlOffset = Math.max(100, (target.x - source.x) * 0.42);
  return [
    `M ${source.x} ${source.y}`,
    `C ${source.x + controlOffset} ${source.y},`,
    `${target.x - controlOffset} ${target.y},`,
    `${target.x} ${target.y}`,
  ].join(' ');
}
