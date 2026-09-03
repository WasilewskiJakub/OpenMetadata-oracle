import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';

import type { LoadPlanDetail, LoadPlanTreeStep, SessionInfo } from '../api/types';
import { LoadPlanTree } from './LoadPlanTree';
import { LoadPlanView } from './LoadPlanView';

const steps: LoadPlanTreeStep[] = [
  {
    id: 'root-step',
    name: 'root_step',
    stepType: 'ROOT_SERIAL',
    path: ['root_step'],
    enabled: true,
  },
  {
    id: 'parallel-sales',
    parentStepId: 'root-step',
    name: 'parallel_sales',
    stepType: 'PARALLEL',
    path: ['root_step', 'parallel_sales'],
    enabled: true,
  },
  {
    id: 'step-orders',
    parentStepId: 'parallel-sales',
    name: 'load_orders',
    stepType: 'RUN_SCENARIO',
    path: ['root_step', 'parallel_sales', 'load_orders'],
    scenarioName: 'SCN_LOAD_ORDERS',
    scenarioVersion: '003',
    mappingId: 'map-load-orders',
    mappingName: 'MAP_LOAD_ORDERS',
    resolution: 'RESOLVED',
    enabled: false,
  },
  {
    id: 'serial-audit',
    parentStepId: 'root-step',
    name: 'serial_audit',
    stepType: 'SERIAL',
    path: ['root_step', 'serial_audit'],
    enabled: true,
  },
];

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

describe('LoadPlanTree', () => {
  it('renders the parentStepId hierarchy as nested ordered lists', () => {
    render(<LoadPlanTree steps={steps} />);

    const rootItem = screen.getByText('root_step').closest('li');
    expect(rootItem).not.toBeNull();
    const rootChildren = rootItem?.querySelector(':scope > ol');
    expect(rootChildren).not.toBeNull();
    expect(within(rootChildren as HTMLOListElement).getByText('parallel_sales')).toBeVisible();
    expect(within(rootChildren as HTMLOListElement).getByText('serial_audit')).toBeVisible();

    const parallelItem = screen.getByText('parallel_sales').closest('li');
    expect(parallelItem).not.toBeNull();
    const parallelChildren = parallelItem?.querySelector(':scope > ol');
    expect(parallelChildren).not.toBeNull();
    expect(within(parallelChildren as HTMLOListElement).getByText('load_orders')).toBeVisible();
  });

  it('collapses and expands a complete branch with an accessible toggle', async () => {
    const user = userEvent.setup();
    render(<LoadPlanTree steps={steps} />);

    const toggle = screen.getByRole('button', {
      name: 'Zwiń Parallel parallel_sales',
    });
    const controlledId = toggle.getAttribute('aria-controls');
    expect(toggle).toHaveAttribute('aria-expanded', 'true');
    expect(controlledId).not.toBeNull();
    expect(document.getElementById(controlledId as string)).not.toBeNull();

    await user.click(toggle);

    expect(toggle).toHaveAttribute('aria-expanded', 'false');
    expect(screen.queryByText('load_orders')).not.toBeInTheDocument();

    await user.click(toggle);

    expect(toggle).toHaveAttribute('aria-expanded', 'true');
    expect(screen.getByText('load_orders')).toBeVisible();
  });

  it('supports native keyboard activation and exposes toggles only for branches', async () => {
    const user = userEvent.setup();
    render(<LoadPlanTree steps={steps} />);

    const rootToggle = screen.getByRole('button', {
      name: 'Zwiń Root Step (Serial) root_step',
    });
    rootToggle.focus();
    await user.keyboard('{Enter}');

    expect(rootToggle).toHaveAttribute('aria-expanded', 'false');
    expect(screen.queryByText('parallel_sales')).not.toBeInTheDocument();

    await user.keyboard(' ');

    expect(rootToggle).toHaveAttribute('aria-expanded', 'true');
    const scenarioItem = screen.getByText('load_orders').closest('li');
    expect(scenarioItem).not.toBeNull();
    expect(within(scenarioItem as HTMLLIElement).queryByRole('button')).not.toBeInTheDocument();
  });

  it('keeps a run-scenario non-collapsible when it owns synthetic package mappings', () => {
    const stepsWithPackageMapping: LoadPlanTreeStep[] = [
      ...steps,
      {
        id: 'package-mapping',
        parentStepId: 'step-orders',
        name: 'MAP_FROM_PACKAGE',
        stepType: 'PACKAGE_MAPPING',
        path: [
          'root_step',
          'parallel_sales',
          'load_orders',
          'MAP_FROM_PACKAGE',
        ],
        mappingId: 'map-from-package',
        mappingName: 'MAP_FROM_PACKAGE',
        resolution: 'RESOLVED',
        enabled: false,
      },
    ];
    render(<LoadPlanTree steps={stepsWithPackageMapping} />);

    const scenarioItem = screen.getByText('load_orders').closest('li');
    expect(scenarioItem).not.toBeNull();
    expect(within(scenarioItem as HTMLLIElement).queryByRole('button')).not.toBeInTheDocument();
    expect(within(scenarioItem as HTMLLIElement).getByText('MAP_FROM_PACKAGE')).toBeVisible();
  });

  it('labels effective disablement independently from scenario resolution', () => {
    render(<LoadPlanTree steps={steps} />);

    const scenarioRow = screen.getByText('load_orders').closest('.load-plan-tree-row');
    expect(scenarioRow).not.toBeNull();
    expect(within(scenarioRow as HTMLElement).getByText('Wyłączony w wykonaniu')).toBeVisible();
    expect(within(scenarioRow as HTMLElement).getByText('RESOLVED')).toBeVisible();
    expect(scenarioRow).not.toHaveClass('load-plan-tree-row-disabled');
  });
});

describe('LoadPlanView execution state', () => {
  it('shows a disabled mapping occurrence without replacing its resolution', () => {
    const detail: LoadPlanDetail = {
      id: 'load-plan-1',
      name: 'LP_DAILY_SALES',
      contextCode: 'DEV',
      mappings: [
        {
          stepId: 'step-orders',
          scenarioName: 'SCN_LOAD_ORDERS',
          scenarioVersion: '003',
          mappingId: 'map-load-orders',
          mappingName: 'MAP_LOAD_ORDERS',
          enabled: false,
          resolution: 'RESOLVED',
        },
      ],
      steps,
    };

    render(
      <LoadPlanView
        contexts={[{ code: 'DEV', name: 'Development', isDefault: true }]}
        detail={detail}
        session={session}
        onBack={vi.fn()}
        onContextChange={vi.fn()}
        onLogout={vi.fn()}
        onOpenMapping={vi.fn()}
      />
    );

    const mappingPanel = screen
      .getByRole('heading', { name: 'Mappingi w Load Planie' })
      .closest('section');
    expect(mappingPanel).not.toBeNull();
    expect(within(mappingPanel as HTMLElement).getByText('Wyłączony w wykonaniu')).toBeVisible();
    expect(within(mappingPanel as HTMLElement).getByText('Mapping')).toBeVisible();
  });
});
