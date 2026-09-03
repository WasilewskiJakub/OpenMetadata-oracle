import { useMemo, useRef, useState } from 'react';

import type { LoadPlanSummary, OdiContext, SessionInfo } from '../api/types';
import { AppShell } from './AppShell';
import { Icon } from './Icon';

interface DashboardViewProps {
  contexts: OdiContext[];
  error?: string;
  plans: LoadPlanSummary[];
  session: SessionInfo;
  onLogout(): void;
  onOpenPlan(id: string): void;
}

const dateFormatter = new Intl.DateTimeFormat('pl-PL', {
  dateStyle: 'medium',
  timeStyle: 'short',
});

export function DashboardView({ contexts, error, plans, session, onLogout, onOpenPlan }: DashboardViewProps) {
  const headingRef = useRef<HTMLHeadingElement>(null);
  const [query, setQuery] = useState('');
  const filteredPlans = useMemo(() => {
    const normalized = query.trim().toLocaleLowerCase('pl');
    if (!normalized) return plans;
    return plans.filter((plan) =>
      [plan.name, plan.project, plan.folder, plan.description].some((value) =>
        value?.toLocaleLowerCase('pl').includes(normalized)
      )
    );
  }, [plans, query]);
  const mappingCount = plans.reduce((total, plan) => total + (plan.mappingCount ?? 0), 0);
  const hasUnresolvedData = plans.every((plan) => plan.unresolvedCount !== undefined);
  const unresolvedCount = hasUnresolvedData
    ? plans.reduce((total, plan) => total + (plan.unresolvedCount ?? 0), 0)
    : undefined;
  const activeCount = plans.filter((plan) => plan.status === 'ENABLED').length;
  const hasStatusData = plans.some((plan) => plan.status !== undefined);

  return (
    <AppShell error={error} mode={session.mode} repository={session.repository} onLogout={onLogout}>
      <div className="page-heading-row">
        <div>
          <p className="kicker">Repozytorium / {session.repository.workRepository}</p>
          <h1 ref={headingRef} tabIndex={-1}>Load Plany</h1>
          <p>Wybierz orkiestrację, aby przejść od scenariuszy do mappingów i ich lineage.</p>
        </div>
        <span className="read-only-pill"><Icon name="shield" /> Tylko odczyt</span>
      </div>

      <section className="metric-grid" aria-label="Podsumowanie repozytorium">
        <article><span>Load Plany</span><strong>{plans.length}</strong><small>{hasStatusData ? `${activeCount} aktywne` : 'odczytane z repozytorium'}</small></article>
        <article><span>Mappingi</span><strong>{mappingCount}</strong><small>we wszystkich planach</small></article>
        <article><span>Nierozwiązane</span><strong>{unresolvedCount ?? '—'}</strong><small>{hasUnresolvedData ? 'wymagają uwagi' : 'brak danych w podsumowaniu'}</small></article>
        <article className="accent-metric"><span>Context</span><strong>{contexts.length}</strong><small>{contexts.map((context) => context.code).join(' · ') || 'brak'}</small></article>
      </section>

      <section className="data-panel" aria-labelledby="load-plan-table-title">
        <header className="panel-toolbar">
          <div><h2 id="load-plan-table-title">Wszystkie Load Plany</h2><span>{filteredPlans.length} wyników</span></div>
          <label className="search-control">
            <span className="sr-only">Szukaj Load Planów</span>
            <Icon name="search" />
            <input value={query} onChange={(event) => setQuery(event.target.value)} placeholder="Szukaj po nazwie, projekcie lub folderze…" type="search" />
          </label>
        </header>
        <div className="table-scroll">
          <table className="entity-table">
            <thead><tr><th>Nazwa</th><th>Projekt / folder</th><th>Status</th><th>Scenariusze</th><th>Mappingi</th><th>Aktualizacja</th><th><span className="sr-only">Akcje</span></th></tr></thead>
            <tbody>
              {filteredPlans.map((plan) => (
                <tr key={plan.id}>
                  <td><button className="entity-link" type="button" onClick={() => onOpenPlan(plan.id)}>{plan.name}</button>{plan.description ? <small>{plan.description}</small> : null}</td>
                  <td>{plan.project || plan.folder ? <span className="path-cell"><Icon name="folder" />{plan.project ?? '—'}<b>/</b>{plan.folder ?? '—'}</span> : '—'}</td>
                  <td>{plan.status ? <span className={`badge badge-${plan.status.toLocaleLowerCase()}`}><span />{plan.status === 'ENABLED' ? 'Aktywny' : 'Wyłączony'}</span> : <span className="badge badge-disabled">Brak danych</span>}</td>
                  <td className="numeric">{plan.scenarioCount ?? '—'}</td>
                  <td className="numeric">{plan.mappingCount ?? '—'}</td>
                  <td>{plan.updatedAt ? <time dateTime={plan.updatedAt}>{dateFormatter.format(new Date(plan.updatedAt))}</time> : '—'}</td>
                  <td><button className="row-action" type="button" aria-label={`Otwórz load plan ${plan.name}`} onClick={() => onOpenPlan(plan.id)}><Icon name="arrow-right" /></button></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        {filteredPlans.length === 0 ? <div className="empty-state">Brak Load Planów pasujących do wyszukiwania.</div> : null}
      </section>
    </AppShell>
  );
}
