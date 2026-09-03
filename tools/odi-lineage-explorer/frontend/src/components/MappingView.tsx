import { useEffect, useRef, useState } from 'react';

import type { MappingDetail, MappingNode, SessionInfo } from '../api/types';
import { AppShell } from './AppShell';
import { Icon } from './Icon';
import { LineageGraph } from './LineageGraph';

interface MappingViewProps {
  error?: string;
  mapping: MappingDetail;
  session: SessionInfo;
  onBack(): void;
  onDashboard(): void;
  onLogout(): void;
}

function physicalResolutionStatus(mapping: MappingDetail) {
  const physicalObjects = mapping.nodes.flatMap((node) => node.metadata ? [node.metadata] : []);
  const unresolvedCount = physicalObjects.filter(
    (metadata) => !metadata.isPhysicalLocationResolved
  ).length;

  if (physicalObjects.length === 0) return 'Brak obiektów fizycznych';
  if (unresolvedCount === 0) return 'Wszystkie lokalizacje rozwiązane';
  if (unresolvedCount === 1) return '1 lokalizacja nierozwiązana';
  return `Nierozwiązane lokalizacje: ${unresolvedCount}`;
}

function MetadataPanel({ node }: { node: MappingNode }) {
  return (
    <aside aria-label="Metadane zaznaczonego obiektu" className="metadata-panel" role="region">
      <header><span><Icon name={node.metadata ? 'database' : 'flow'} /></span><div><small>Zaznaczony obiekt</small><h2>{node.label}</h2></div></header>
      {node.metadata ? (
        <dl>
          <div><dt>Alias w mappingu</dt><dd>{node.metadata.alias}</dd></div>
          <div><dt>Datastore</dt><dd>{node.metadata.datastoreName}</dd></div>
          <div className="metadata-emphasis"><dt>Resource Name</dt><dd>{node.metadata.resourceName}</dd></div>
          <div><dt>Model</dt><dd>{node.metadata.modelName}</dd></div>
          <div><dt>Logical Schema</dt><dd>{node.metadata.logicalSchema}</dd></div>
          <div className="resolution-divider"><span>Context resolution</span></div>
          <div><dt>Physical Schema</dt><dd>{node.metadata.physicalSchema ?? 'Nierozwiązane'}</dd></div>
          <div><dt>Data Server</dt><dd>{node.metadata.dataServer ?? '—'}</dd></div>
          <div><dt>Catalog</dt><dd>{node.metadata.catalog ?? '—'}</dd></div>
          <div><dt>Schema</dt><dd>{node.metadata.schema ?? '—'}</dd></div>
          {!node.metadata.isPhysicalLocationResolved ? <div className="resolution-warning"><dt>Status</dt><dd>{node.metadata.resolutionReason ?? 'Logical Schema nie ma fizycznego mapowania w wybranym Contexcie.'}</dd></div> : null}
        </dl>
      ) : <p className="transform-note">Metadane fizyczne datastore nie są dostępne.</p>}
    </aside>
  );
}

export function MappingView({ error, mapping, session, onBack, onDashboard, onLogout }: MappingViewProps) {
  const headingRef = useRef<HTMLHeadingElement>(null);
  const [selectedNode, setSelectedNode] = useState<MappingNode | undefined>(() => mapping.nodes[0]);
  const physicalStatus = physicalResolutionStatus(mapping);

  useEffect(() => {
    headingRef.current?.focus();
    setSelectedNode(mapping.nodes[0]);
  }, [mapping]);

  return (
    <AppShell error={error} mode={session.mode} repository={session.repository} onLogout={onLogout}>
      <nav className="breadcrumbs" aria-label="Okruszki">
        <button type="button" onClick={onDashboard}>Load Plany</button><span>/</span><button type="button" onClick={onBack}>Plan</button><span>/</span><span aria-current="page">{mapping.name}</span>
      </nav>
      <div className="mapping-page-header">
        <div><button className="back-button" type="button" onClick={onBack}><Icon name="arrow-left" /> Wróć do mappingów</button><h1 ref={headingRef} tabIndex={-1}>{mapping.name}</h1>{mapping.project || mapping.folder ? <p>{mapping.project ?? '—'} / {mapping.folder ?? '—'}</p> : null}</div>
        <div className="mapping-context-badge"><span>Context</span><strong>{mapping.contextCode}</strong><small>{physicalStatus}</small></div>
      </div>
      <div className={`mapping-workbench ${selectedNode ? '' : 'mapping-workbench-empty'}`}>
        <section className="graph-panel" aria-labelledby="graph-title">
          <header><div><p className="kicker">Design-time lineage</p><h2 id="graph-title">Przepływ danych</h2></div><span className="read-only-pill"><Icon name="shield" /> Read-only</span></header>
          {mapping.warnings.length > 0 ? (
            <div aria-label="Ostrzeżenia lineage" className="lineage-warnings" role="status">
              <Icon name="warning" />
              <div>
                <strong>Column lineage może być niepełny</strong>
                <ul>{mapping.warnings.map((warning) => <li key={warning}>{warning}</li>)}</ul>
              </div>
            </div>
          ) : null}
          {selectedNode ? (
            <LineageGraph key={mapping.id} mapping={mapping} selectedNodeId={selectedNode.id} onSelectNode={setSelectedNode} />
          ) : (
            <div className="mapping-empty-state" role="status">
              <span><Icon name="warning" /></span>
              <strong>Brak komponentów mappingu</strong>
              <p>Mapping nie zawiera komponentów dostępnych do podglądu.</p>
            </div>
          )}
        </section>
        {selectedNode ? <MetadataPanel node={selectedNode} /> : null}
      </div>
    </AppShell>
  );
}
