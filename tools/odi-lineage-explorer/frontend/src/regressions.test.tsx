import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';

import { App } from './App';
import { createDemoApiClient } from './api/client';
import type { LoadPlanDetail, MappingDetail, SessionInfo } from './api/types';
import { DashboardView } from './components/DashboardView';
import { LoadPlanView } from './components/LoadPlanView';
import { MappingView } from './components/MappingView';

const session: SessionInfo = {
  token: 'test-token',
  repository: {
    name: 'ODI_TEST',
    masterRepository: 'MASTER',
    workRepository: 'WORKREP',
  },
  expiresAt: '2026-09-03T00:00:00Z',
  mode: 'DEMO',
};

const duplicateMappingPlan: LoadPlanDetail = {
  id: 'lp-duplicate',
  name: 'LP_DUPLICATE',
  description: 'Ten sam mapping uruchamiany w dwóch krokach',
  project: 'DWH',
  folder: 'LOAD_PLANS',
  status: 'ENABLED',
  scenarioCount: 2,
  mappingCount: 2,
  unresolvedCount: 0,
  updatedAt: '2026-09-02T00:00:00Z',
  contextCode: 'DEV',
  steps: [],
  mappings: [
    {
      stepId: 'step-first',
      scenarioName: 'SCN_SHARED',
      scenarioVersion: '001',
      mappingId: 'map-shared',
      mappingName: 'MAP_SHARED',
      project: 'DWH',
      folder: 'SHARED',
      enabled: true,
      resolution: 'RESOLVED',
    },
    {
      stepId: 'step-second',
      scenarioName: 'SCN_SHARED',
      scenarioVersion: '001',
      mappingId: 'map-shared',
      mappingName: 'MAP_SHARED',
      project: 'DWH',
      folder: 'SHARED',
      enabled: true,
      resolution: 'RESOLVED',
    },
  ],
};

describe('regresje krytycznych stanów UI', () => {
  it('oznacza brak licznika nierozwiązanych jako nieznany zamiast zera', () => {
    render(
      <DashboardView
        contexts={[{ code: 'DEV', name: 'Development', isDefault: true }]}
        plans={[
          {
            id: 'lp-without-unresolved-count',
            name: 'LP_WITHOUT_UNRESOLVED_COUNT',
            scenarioCount: 1,
            mappingCount: 1,
          },
        ]}
        session={session}
        onLogout={vi.fn()}
        onOpenPlan={vi.fn()}
      />
    );

    const metric = screen.getByText('Nierozwiązane').closest('article');
    expect(metric).not.toBeNull();
    expect(within(metric!).getByText('—')).toBeVisible();
    expect(within(metric!).getByText('brak danych w podsumowaniu')).toBeVisible();
  });

  it('pokazuje przyczynę nierozwiązanego kroku obok jego statusu', () => {
    const resolutionReason = 'Scenariusz wskazany przez krok nie istnieje.';
    const unresolvedPlan: LoadPlanDetail = {
      ...duplicateMappingPlan,
      mappings: [
        {
          stepId: 'step-missing',
          scenarioName: 'SCN_MISSING',
          scenarioVersion: '001',
          enabled: true,
          resolution: 'UNRESOLVED',
          resolutionReason,
        },
      ],
    };

    render(
      <LoadPlanView
        contexts={[{ code: 'DEV', name: 'Development', isDefault: true }]}
        detail={unresolvedPlan}
        session={session}
        onBack={vi.fn()}
        onContextChange={vi.fn()}
        onLogout={vi.fn()}
        onOpenMapping={vi.fn()}
      />
    );

    expect(screen.getByText(resolutionReason)).toBeVisible();
  });

  it('pokazuje nierozwiązaną lokalizację fizyczną w statusie mappingu', () => {
    const unresolvedMapping: MappingDetail = {
      id: 'map-unresolved',
      name: 'MAP_UNRESOLVED',
      contextCode: 'DEV',
      nodes: [
        {
          id: 'source',
          label: 'SOURCE',
          kind: 'DATASTORE_SOURCE',
          rawComponentType: 'DATASTORE_SOURCE',
          columns: [],
          metadata: {
            alias: 'SOURCE',
            datastoreName: 'DS_SOURCE',
            resourceName: 'SOURCE_TABLE',
            modelName: 'SOURCE_MODEL',
            logicalSchema: 'SOURCE_LOGICAL',
            isPhysicalLocationResolved: false,
            resolutionReason: 'Brak mapowania dla Contextu DEV.',
          },
        },
      ],
      edges: [],
      columnLineage: [],
      warnings: [],
    };

    render(
      <MappingView
        mapping={unresolvedMapping}
        session={session}
        onBack={vi.fn()}
        onDashboard={vi.fn()}
        onLogout={vi.fn()}
      />
    );

    expect(screen.getByText('1 lokalizacja nierozwiązana')).toBeVisible();
    expect(screen.queryByText('Physical Schema rozwiązany')).not.toBeInTheDocument();
  });

  it('pokazuje ostrzeżenie o niepełnym column lineage', () => {
    const warning = 'Reusable mapping zawiera nierozwiązaną referencję.';
    const warningMapping: MappingDetail = {
      id: 'map-warning',
      name: 'MAP_WARNING',
      contextCode: 'DEV',
      nodes: [
        {
          id: 'source/reusable/source',
          label: 'SOURCE_TABLE',
          kind: 'DATASTORE_SOURCE',
          rawComponentType: 'DATASTORE_SOURCE',
          columns: [{ id: 'source/reusable/source/id', name: 'ID' }],
        },
      ],
      edges: [],
      columnLineage: [],
      warnings: [warning],
    };

    render(
      <MappingView
        mapping={warningMapping}
        session={session}
        onBack={vi.fn()}
        onDashboard={vi.fn()}
        onLogout={vi.fn()}
      />
    );

    expect(screen.getByRole('status', { name: 'Ostrzeżenia lineage' })).toHaveTextContent(
      warning
    );
  });

  it('wybiera niezależnie każde wystąpienie tego samego mappingu w Load Planie', async () => {
    const user = userEvent.setup();
    render(
      <LoadPlanView
        contexts={[{ code: 'DEV', name: 'Development', isDefault: true }]}
        detail={duplicateMappingPlan}
        session={session}
        onBack={vi.fn()}
        onContextChange={vi.fn()}
        onLogout={vi.fn()}
        onOpenMapping={vi.fn()}
      />
    );

    const occurrences = screen.getAllByRole('checkbox', { name: 'Wybierz MAP_SHARED' });
    expect(occurrences).toHaveLength(2);
    expect(occurrences[0]).toBeChecked();
    expect(occurrences[1]).toBeChecked();

    await user.click(occurrences[1]);

    expect(occurrences[0]).toBeChecked();
    expect(occurrences[1]).not.toBeChecked();
  });

  it('pokazuje błąd API również po zalogowaniu', async () => {
    const user = userEvent.setup();
    const demo = createDemoApiClient();
    render(
      <App
        api={{
          ...demo,
          getLoadPlan: vi.fn().mockRejectedValue(new Error('Repozytorium ODI jest chwilowo niedostępne.')),
        }}
      />
    );

    await user.click(screen.getByRole('button', { name: 'Otwórz demo' }));
    await user.click(await screen.findByRole('button', { name: 'Otwórz load plan LP_DAILY_SALES' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Repozytorium ODI jest chwilowo niedostępne.'
    );
  });

  it('pokazuje bezpieczny empty state dla mappingu bez komponentów', () => {
    const emptyMapping: MappingDetail = {
      id: 'map-empty',
      name: 'MAP_EMPTY',
      project: 'DWH',
      folder: 'EMPTY',
      contextCode: 'DEV',
      nodes: [],
      edges: [],
      columnLineage: [],
      warnings: [],
    };

    render(
      <MappingView
        mapping={emptyMapping}
        session={session}
        onBack={vi.fn()}
        onDashboard={vi.fn()}
        onLogout={vi.fn()}
      />
    );

    expect(screen.getByRole('heading', { name: 'MAP_EMPTY' })).toBeVisible();
    expect(screen.getByRole('status')).toHaveTextContent(
      'Mapping nie zawiera komponentów dostępnych do podglądu.'
    );
  });

  it('czyści lokalną sesję również po wygaśnięciu sesji backendu', async () => {
    const user = userEvent.setup();
    const demo = createDemoApiClient();
    const endSession = vi.fn().mockRejectedValue(new Error('Sesja już wygasła.'));

    render(<App api={{ ...demo, endSession }} />);

    await user.click(screen.getByRole('button', { name: 'Otwórz demo' }));
    await user.click(await screen.findByRole('button', { name: 'Zakończ sesję' }));

    expect(endSession).toHaveBeenCalledWith('demo-session-memory-only');
    expect(await screen.findByRole('heading', { name: 'Połączenie z ODI' })).toBeVisible();
  });

  it('pokazuje błąd pobierania mappingu bez opuszczania Load Planu', async () => {
    const user = userEvent.setup();
    const demo = createDemoApiClient();

    render(
      <App
        api={{
          ...demo,
          getMapping: vi.fn().mockRejectedValue(new Error('Mapping nie jest dostępny.')),
        }}
      />
    );

    await user.click(screen.getByRole('button', { name: 'Otwórz demo' }));
    await user.click(
      await screen.findByRole('button', { name: 'Otwórz load plan LP_DAILY_SALES' })
    );
    await user.click(await screen.findByRole('button', { name: 'Pokaż lineage MAP_LOAD_ORDERS' }));

    expect(await screen.findByRole('heading', { name: 'LP_DAILY_SALES' })).toBeVisible();
    expect(screen.getByRole('alert')).toHaveTextContent('Mapping nie jest dostępny.');
  });
});
