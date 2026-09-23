import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';

import type { LoadPlanDetail, SessionInfo } from '../api/types';
import { LoadPlanView } from './LoadPlanView';

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

const detail: LoadPlanDetail = {
  id: 'load-plan-1',
  name: 'LP_DUPLICATE',
  contextCode: 'DEV',
  steps: [],
  mappings: [
    {
      stepId: 'step-first',
      scenarioName: 'SCN_SHARED',
      scenarioVersion: '001',
      mappingId: 'map-shared',
      mappingName: 'MAP_SHARED',
      stepPath: ['root', 'parallel', 'first'],
      enabled: true,
      resolution: 'RESOLVED',
    },
    {
      stepId: 'step-second',
      scenarioName: 'SCN_SHARED',
      scenarioVersion: '001',
      mappingId: 'map-shared',
      mappingName: 'MAP_SHARED',
      stepPath: ['root', 'parallel', 'second'],
      enabled: true,
      resolution: 'RESOLVED',
    },
    {
      stepId: 'procedure-step',
      scenarioName: 'PROC_AUDIT',
      scenarioVersion: '001',
      enabled: true,
      resolution: 'OUT_OF_SCOPE',
    },
  ],
};

function renderView(onExport = vi.fn().mockResolvedValue('odi-lineage.json')) {
  render(
    <LoadPlanView
      contexts={[{ code: 'DEV', name: 'Development', isDefault: true }]}
      detail={detail}
      session={session}
      onBack={vi.fn()}
      onContextChange={vi.fn()}
      onExport={onExport}
      onLogout={vi.fn()}
      onOpenMapping={vi.fn()}
    />
  );
  return onExport;
}

describe('LoadPlanView JSON export', () => {
  it('eksportuje dokładnie wybrane wystąpienie, a nie wspólny mappingId', async () => {
    const user = userEvent.setup();
    const onExport = renderView();
    const firstOccurrence = screen.getByRole('checkbox', {
      name: 'Wybierz MAP_SHARED; wystąpienie root / parallel / first; ID step-first',
    });
    const secondOccurrence = screen.getByRole('checkbox', {
      name: 'Wybierz MAP_SHARED; wystąpienie root / parallel / second; ID step-second',
    });
    expect(screen.getByText('Wystąpienie: root / parallel / first')).toBeVisible();
    expect(screen.getByText('Wystąpienie: root / parallel / second')).toBeVisible();

    await user.click(secondOccurrence);
    await user.click(screen.getByRole('button', { name: 'Eksportuj JSON' }));

    expect(onExport).toHaveBeenCalledWith(['step-first']);
    expect(firstOccurrence).toBeChecked();
    expect(await screen.findByRole('status')).toHaveTextContent(
      'Rozpoczęto pobieranie: odi-lineage.json'
    );
  });

  it('pokazuje stan mixed oraz blokuje eksport po wyczyszczeniu wyboru', async () => {
    const user = userEvent.setup();
    renderView();
    const selectAll = screen.getByRole('checkbox', { name: 'Wybierz wszystkie mappingi' });
    const secondOccurrence = screen.getByRole('checkbox', {
      name: 'Wybierz MAP_SHARED; wystąpienie root / parallel / second; ID step-second',
    });

    await user.click(secondOccurrence);
    expect(selectAll).toHaveProperty('indeterminate', true);

    await user.click(selectAll);
    expect(selectAll).toBeChecked();
    await user.click(selectAll);
    expect(screen.getByRole('button', { name: 'Eksportuj JSON' })).toBeDisabled();
  });

  it('zachowuje wybór po błędzie i pozwala ponowić eksport', async () => {
    const user = userEvent.setup();
    const onExport = vi.fn()
      .mockRejectedValueOnce(new Error('Eksport chwilowo niedostępny.'))
      .mockResolvedValueOnce('odi-lineage-retry.json');
    renderView(onExport);
    const exportButton = screen.getByRole('button', { name: 'Eksportuj JSON' });

    await user.click(exportButton);

    expect(await screen.findByRole('alert')).toHaveTextContent('Eksport chwilowo niedostępny.');
    expect(screen.getByRole('checkbox', {
      name: 'Wybierz MAP_SHARED; wystąpienie root / parallel / first; ID step-first',
    })).toBeChecked();

    await user.click(exportButton);
    expect(await screen.findByRole('status')).toHaveTextContent(
      'Rozpoczęto pobieranie: odi-lineage-retry.json'
    );
    expect(onExport).toHaveBeenCalledTimes(2);
  });

  it('pokazuje lokalny stan pracy bez zasłaniania Load Planu', async () => {
    const user = userEvent.setup();
    let finishExport: (fileName: string) => void = () => undefined;
    const onExport = vi.fn().mockImplementation(() => new Promise<string>((resolve) => {
      finishExport = resolve;
    }));
    renderView(onExport);

    await user.click(screen.getByRole('button', { name: 'Eksportuj JSON' }));

    const busyButton = screen.getByRole('button', { name: 'Przygotowuję JSON…' });
    expect(busyButton).toBeDisabled();
    expect(busyButton).toHaveAttribute('aria-busy', 'true');
    expect(screen.getByRole('heading', { name: 'LP_DUPLICATE' })).toBeVisible();

    finishExport('odi-lineage.json');
    expect(await screen.findByRole('status')).toHaveTextContent(
      'Rozpoczęto pobieranie: odi-lineage.json'
    );
  });
});
