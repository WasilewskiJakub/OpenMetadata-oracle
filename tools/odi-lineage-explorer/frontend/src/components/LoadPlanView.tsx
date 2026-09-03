import { useEffect, useRef, useState } from 'react';

import type { ContextCode, LoadPlanDetail, OdiContext, SessionInfo } from '../api/types';
import { AppShell } from './AppShell';
import { Icon } from './Icon';
import { LoadPlanTree } from './LoadPlanTree';

interface LoadPlanViewProps {
  contexts: OdiContext[];
  detail: LoadPlanDetail;
  error?: string;
  session: SessionInfo;
  onBack(): void;
  onContextChange(context: ContextCode): void;
  onLogout(): void;
  onOpenMapping(id: string): void;
}

function resolutionLabel(resolution: string) {
  switch (resolution) {
    case 'RESOLVED': return 'Mapping';
    case 'STALE': return 'Nieaktualny';
    case 'UNRESOLVED': return 'Nierozwiązany';
    default: return 'Poza zakresem';
  }
}

export function LoadPlanView({
  contexts,
  detail,
  error,
  session,
  onBack,
  onContextChange,
  onLogout,
  onOpenMapping,
}: LoadPlanViewProps) {
  const headingRef = useRef<HTMLHeadingElement>(null);
  const selectableIds = detail.mappings.flatMap((item) => item.mappingId ? [item.stepId] : []);
  const [selectedIds, setSelectedIds] = useState<Set<string>>(() => new Set(selectableIds));

  useEffect(() => {
    headingRef.current?.focus();
    setSelectedIds(new Set(selectableIds));
  }, [detail.id, detail.contextCode]);

  const allSelected = selectableIds.length > 0 && selectableIds.every((id) => selectedIds.has(id));

  function toggleAll() {
    setSelectedIds(allSelected ? new Set() : new Set(selectableIds));
  }

  function toggleMapping(id: string) {
    setSelectedIds((current) => {
      const next = new Set(current);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }

  return (
    <AppShell error={error} mode={session.mode} repository={session.repository} onLogout={onLogout}>
      <nav className="breadcrumbs" aria-label="Okruszki">
        <button type="button" onClick={onBack}>Load Plany</button><span>/</span><span aria-current="page">{detail.name}</span>
      </nav>
      <div className="page-heading-row detail-heading">
        <div>
          <button className="back-button" type="button" onClick={onBack}><Icon name="arrow-left" /> Wróć do Load Planów</button>
          <h1 ref={headingRef} tabIndex={-1}>{detail.name}</h1>
          {detail.description ? <p>{detail.description}</p> : null}
        </div>
        <div className="context-control">
          <label htmlFor="context-select">Context podglądu</label>
          <div><Icon name="layers" /><select id="context-select" value={detail.contextCode} onChange={(event) => onContextChange(event.target.value as ContextCode)}>{contexts.map((context) => <option key={context.code} value={context.code}>{context.code} — {context.name}</option>)}</select><Icon name="chevron-down" /></div>
          <small>Rozwiązuje Logical Schema do Physical Schema</small>
        </div>
      </div>

      <section className="plan-summary-strip" aria-label="Podsumowanie Load Planu">
        <span><Icon name="folder" /><small>Projekt</small><strong>{detail.project ?? '—'}</strong></span>
        <span><Icon name="flow" /><small>Scenariusze</small><strong>{detail.scenarioCount ?? '—'}</strong></span>
        <span><Icon name="database" /><small>Mappingi</small><strong>{detail.mappingCount ?? '—'}</strong></span>
        <span><Icon name="shield" /><small>Wybrane</small><strong>{selectedIds.size}</strong></span>
      </section>

      <section className="data-panel load-plan-tree-panel" aria-labelledby="load-plan-tree-title">
        <header className="panel-toolbar">
          <div><h2 id="load-plan-tree-title">Struktura wykonania</h2><span>Serial, Parallel, Case oraz dokładne wystąpienia scenariuszy</span></div>
        </header>
        <LoadPlanTree steps={detail.steps} />
      </section>

      <section className="data-panel" aria-labelledby="mapping-list-title">
        <header className="panel-toolbar mapping-toolbar">
          <div><h2 id="mapping-list-title">Mappingi w Load Planie</h2><span>Scenariusze połączone ze źródłowymi mappingami</span></div>
          <label className="select-all"><input checked={allSelected} onChange={toggleAll} type="checkbox" />Wybierz wszystkie mappingi</label>
        </header>
        <div className="mapping-list">
          {detail.mappings.map((item) => {
            const isSelectable = Boolean(item.mappingId);
            return (
              <article className={`mapping-row ${isSelectable ? '' : 'mapping-row-muted'}`} key={item.stepId}>
                <div className="mapping-select-cell">
                  {item.mappingId ? <input aria-label={`Wybierz ${item.mappingName}`} checked={selectedIds.has(item.stepId)} onChange={() => toggleMapping(item.stepId)} type="checkbox" /> : <span className="procedure-marker"><Icon name="warning" /></span>}
                </div>
                <div className="mapping-main">
                  <div><span className="scenario-version">v{item.scenarioVersion}</span><strong>{item.scenarioName}</strong></div>
                  {item.project || item.folder ? <span>{item.project ?? '—'} / {item.folder ?? '—'}</span> : null}
                  {item.resolutionReason ? <span>{item.resolutionReason}</span> : null}
                </div>
                <div className="scenario-link"><span>Scenariusz</span><i /><span>{item.mappingName ?? 'Procedura'}</span></div>
                <div className="mapping-statuses">
                  {!item.enabled ? <span className="execution-state execution-state-disabled">Wyłączony w wykonaniu</span> : null}
                  <span className={`resolution resolution-${item.resolution.toLocaleLowerCase()}`}>{resolutionLabel(item.resolution)}</span>
                </div>
                {item.mappingId ? <button className="secondary-button" type="button" onClick={() => onOpenMapping(item.mappingId as string)}>Pokaż lineage {item.mappingName}<Icon name="arrow-right" /></button> : <span className="out-of-scope-note">Procedury pomijamy w MVP</span>}
              </article>
            );
          })}
        </div>
      </section>
    </AppShell>
  );
}
