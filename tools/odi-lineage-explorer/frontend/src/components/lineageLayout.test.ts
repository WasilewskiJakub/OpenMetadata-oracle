import { describe, expect, it } from 'vitest';

import type { MappingNode } from '../api/types';
import { buildLineageLayout, columnAnchor, nodeAnchor } from './lineageLayout';

const nodes: MappingNode[] = [
  {
    id: 'source-a',
    label: 'SOURCE_A',
    kind: 'DATASTORE_SOURCE',
    rawComponentType: 'DATASTORE_SOURCE',
    columns: [{ id: 'a', name: 'A' }],
  },
  {
    id: 'source-b',
    label: 'SOURCE_B',
    kind: 'DATASTORE_SOURCE',
    rawComponentType: 'DATASTORE_SOURCE',
    columns: [{ id: 'b', name: 'B' }],
  },
  {
    id: 'target',
    label: 'TARGET',
    kind: 'DATASTORE_TARGET',
    rawComponentType: 'DATASTORE_TARGET',
    columns: [{ id: 'target-a', name: 'A' }],
  },
  {
    id: 'target-b',
    label: 'TARGET_B',
    kind: 'DATASTORE_TARGET',
    rawComponentType: 'DATASTORE_TARGET',
    columns: [{ id: 'target-b', name: 'B' }],
  },
];

describe('lineage layout', () => {
  it('umieszcza każde źródło na lewo od każdego targetu', () => {
    const layout = buildLineageLayout(nodes, new Set(nodes.map(({ id }) => id)));
    const sources = layout.nodes.filter(({ node }) => node.kind === 'DATASTORE_SOURCE');
    const targets = layout.nodes.filter(({ node }) => node.kind === 'DATASTORE_TARGET');

    expect(Math.max(...sources.map(({ x }) => x))).toBeLessThan(
      Math.min(...targets.map(({ x }) => x))
    );
  });

  it('wyznacza kotwice na prawym brzegu źródła i lewym brzegu targetu', () => {
    const layout = buildLineageLayout(nodes, new Set(nodes.map(({ id }) => id)));
    const source = layout.byId.get('source-a')!;
    const target = layout.byId.get('target')!;

    expect(nodeAnchor(source).x).toBe(source.x + source.width);
    expect(nodeAnchor(target).x).toBe(target.x);
    expect(columnAnchor(source, 'a')?.x).toBe(source.x + source.width);
    expect(columnAnchor(target, 'target-a')?.x).toBe(target.x);
  });

  it('zwiększa wysokość karty wyłącznie po rozwinięciu jej kolumn', () => {
    const collapsed = buildLineageLayout(nodes, new Set());
    const expanded = buildLineageLayout(nodes, new Set(['source-a']));

    expect(expanded.byId.get('source-a')!.height).toBeGreaterThan(
      collapsed.byId.get('source-a')!.height
    );
    expect(expanded.byId.get('source-b')!.height).toBe(
      collapsed.byId.get('source-b')!.height
    );
  });
});
