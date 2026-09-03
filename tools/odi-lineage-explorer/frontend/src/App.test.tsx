import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { App } from './App';
import { ApiError, createDemoApiClient } from './api/client';
import type { ApiClient, SessionCredentials } from './api/types';

const credentials: SessionCredentials = {
  jdbcUrl: 'jdbc:oracle:thin:@//172.28.48.1:15210/odipdb',
  repositoryUsername: 'CBK_ODI14C_MASTER',
  repositoryPassword: 'repository-secret',
  workRepositoryName: 'DEV_WORKREP',
  odiUsername: 'ODI_READONLY',
  odiPassword: 'odi-secret',
};

async function fillConnectionForm(user: ReturnType<typeof userEvent.setup>) {
  const fields = [
    ['JDBC URL', credentials.jdbcUrl],
    ['Schemat repozytorium', credentials.repositoryUsername],
    ['Hasło schematu repozytorium', credentials.repositoryPassword],
    ['Work Repository', credentials.workRepositoryName],
    ['Użytkownik ODI', credentials.odiUsername],
    ['Hasło użytkownika ODI', credentials.odiPassword],
  ] as const;

  for (const [label, value] of fields) {
    const input = screen.getByLabelText(label);
    await user.clear(input);
    await user.type(input, value);
  }
}

function realSessionApi(overrides: Partial<ApiClient> = {}) {
  const demo = createDemoApiClient();
  return {
    ...demo,
    createSession: vi.fn(async () => ({
      ...(await demo.createDemoSession()),
      mode: 'REPOSITORY' as const,
    })),
    ...overrides,
  } satisfies ApiClient;
}

async function openDemoDashboard() {
  const user = userEvent.setup();
  const setItem = vi.spyOn(Storage.prototype, 'setItem');

  render(<App />);

  await user.click(screen.getByRole('button', { name: 'Otwórz demo' }));

  expect(await screen.findByRole('heading', { name: 'Load Plany' })).toBeVisible();
  expect(setItem).not.toHaveBeenCalled();
  expect(window.localStorage.length).toBe(0);

  setItem.mockRestore();
  return user;
}

describe('ODI Lineage Explorer', () => {
  beforeEach(() => {
    window.localStorage.clear();
  });

  it('otwiera jawny tryb demo bez zapisywania haseł w localStorage', async () => {
    await openDemoDashboard();

    expect(screen.getByText('Tryb demo')).toBeVisible();
    expect(screen.getByText('ODI_DEMO_WORK')).toBeVisible();
  });

  it('łączy się read-only z kontrolowanym payloadem i bez zapisywania poświadczeń', async () => {
    const user = userEvent.setup();
    const api = realSessionApi();
    const setItem = vi.spyOn(Storage.prototype, 'setItem');
    render(<App api={api} />);

    await fillConnectionForm(user);
    await user.click(screen.getByRole('button', { name: 'Połącz read-only' }));

    expect(await screen.findByRole('heading', { name: 'Load Plany' })).toBeVisible();
    expect(api.createSession).toHaveBeenCalledWith(credentials);
    expect(screen.getByText('Repozytorium read-only')).toBeVisible();
    expect(screen.queryByText('Tryb demo')).not.toBeInTheDocument();
    expect(setItem).not.toHaveBeenCalled();
    expect(window.localStorage.length).toBe(0);
    expect(window.sessionStorage.length).toBe(0);
    setItem.mockRestore();
  });

  it('pokazuje błąd logowania i czyści oba hasła po nieudanej próbie', async () => {
    const user = userEvent.setup();
    const api = realSessionApi({
      createSession: vi.fn().mockRejectedValue(new Error('Nieprawidłowe dane logowania do ODI.')),
    });
    render(<App api={api} />);

    await fillConnectionForm(user);
    await user.click(screen.getByRole('button', { name: 'Połącz read-only' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Nieprawidłowe dane logowania do ODI.'
    );
    expect(screen.getByLabelText('Hasło schematu repozytorium')).toHaveValue('');
    expect(screen.getByLabelText('Hasło użytkownika ODI')).toHaveValue('');
  });

  it('otwiera demo osobną akcją bez wysyłania formularza połączenia', async () => {
    const user = userEvent.setup();
    const demo = createDemoApiClient();
    const createDemoSession = vi.spyOn(demo, 'createDemoSession');
    const createSession = vi.spyOn(demo, 'createSession');
    render(<App api={demo} />);

    await user.click(screen.getByRole('button', { name: 'Otwórz demo' }));

    expect(await screen.findByRole('heading', { name: 'Load Plany' })).toBeVisible();
    expect(createDemoSession).toHaveBeenCalledOnce();
    expect(createSession).not.toHaveBeenCalled();
  });

  it('oznacza wszystkie dane realnego połączenia jako wymagane', () => {
    render(<App api={realSessionApi()} />);

    expect(screen.getByLabelText('JDBC URL')).toBeRequired();
    expect(screen.getByLabelText('Schemat repozytorium')).toBeRequired();
    expect(screen.getByLabelText('Hasło schematu repozytorium')).toBeRequired();
    expect(screen.getByLabelText('Work Repository')).toBeRequired();
    expect(screen.getByLabelText('Użytkownik ODI')).toBeRequired();
    expect(screen.getByLabelText('Hasło użytkownika ODI')).toBeRequired();
  });

  it('wyłącza autocomplete dla formularza poświadczeń', () => {
    render(<App api={realSessionApi()} />);

    const repositoryPassword = screen.getByLabelText('Hasło schematu repozytorium');
    const odiPassword = screen.getByLabelText('Hasło użytkownika ODI');
    expect(repositoryPassword.closest('form')).toHaveAttribute('autocomplete', 'off');
    expect(repositoryPassword).toHaveAttribute('autocomplete', 'off');
    expect(odiPassword).toHaveAttribute('autocomplete', 'off');
  });

  it('zamyka utworzoną sesję, gdy inicjalny odczyt repozytorium zawiedzie', async () => {
    const user = userEvent.setup();
    const endSession = vi.fn().mockResolvedValue(undefined);
    const api = realSessionApi({
      getContexts: vi.fn().mockRejectedValue(new Error('Nie można pobrać Contextów.')),
      endSession,
    });
    render(<App api={api} />);

    await fillConnectionForm(user);
    await user.click(screen.getByRole('button', { name: 'Połącz read-only' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Nie można pobrać Contextów.');
    expect(endSession).toHaveBeenCalledWith('demo-session-memory-only');
    expect(screen.getByRole('heading', { name: 'Połączenie z ODI' })).toBeVisible();
  });

  it('usuwa wygasłą sesję lokalną po odpowiedzi 401', async () => {
    const user = userEvent.setup();
    const api = realSessionApi({
      getLoadPlan: vi.fn().mockRejectedValue(
        new ApiError(401, 'UNAUTHORIZED', 'Sesja ODI wygasła.')
      ),
    });
    render(<App api={api} />);

    await fillConnectionForm(user);
    await user.click(screen.getByRole('button', { name: 'Połącz read-only' }));
    await user.click(
      await screen.findByRole('button', { name: 'Otwórz load plan LP_DAILY_SALES' })
    );

    expect(await screen.findByRole('heading', { name: 'Połączenie z ODI' })).toBeVisible();
    expect(screen.getByRole('alert')).toHaveTextContent('Sesja ODI wygasła.');
  });

  it('nie zgaduje Contextu, gdy repozytorium nie wskazuje domyślnego', async () => {
    const user = userEvent.setup();
    const getLoadPlan = vi.fn();
    const demo = createDemoApiClient();
    render(
      <App
        api={{
          ...demo,
          getContexts: vi.fn().mockResolvedValue([
            { code: 'QA', name: 'Quality assurance', isDefault: false },
          ]),
          getLoadPlan,
        }}
      />
    );

    await user.click(screen.getByRole('button', { name: 'Otwórz demo' }));
    await user.click(
      await screen.findByRole('button', { name: 'Otwórz load plan LP_DAILY_SALES' })
    );

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Repozytorium ODI nie wskazuje domyślnego Contextu.'
    );
    expect(getLoadPlan).not.toHaveBeenCalled();
  });

  it('pokazuje load plany i otwiera szczegół z mapowaniami scenariuszy', async () => {
    const user = await openDemoDashboard();

    expect(screen.getByText('LP_DAILY_SALES')).toBeVisible();
    await user.click(
      screen.getByRole('button', { name: 'Otwórz load plan LP_DAILY_SALES' })
    );

    expect(await screen.findByRole('heading', { name: 'LP_DAILY_SALES' })).toBeVisible();
    const mappingPanel = screen.getByRole('heading', { name: 'Mappingi w Load Planie' }).closest('section')!;
    expect(within(mappingPanel).getByText('SCN_LOAD_ORDERS')).toBeVisible();
    expect(within(mappingPanel).getByText('PROC_REFRESH_AUDIT')).toBeVisible();
    expect(within(mappingPanel).getByText('Poza zakresem')).toBeVisible();
    const hierarchy = screen.getByRole('list', { name: 'Hierarchia kroków Load Planu' });
    expect(within(hierarchy).getByText('root_step')).toBeVisible();
    expect(within(hierarchy).getByText('parallel_sales')).toBeVisible();
  });

  it('zaznacza i odznacza wszystkie dostępne mappingi, bez procedur', async () => {
    const user = await openDemoDashboard();
    await user.click(
      screen.getByRole('button', { name: 'Otwórz load plan LP_DAILY_SALES' })
    );

    const selectAll = await screen.findByRole('checkbox', {
      name: 'Wybierz wszystkie mappingi',
    });
    const mappingOne = screen.getByRole('checkbox', { name: 'Wybierz MAP_LOAD_ORDERS' });
    const mappingTwo = screen.getByRole('checkbox', { name: 'Wybierz MAP_SALES_FACT' });

    expect(selectAll).toBeChecked();
    expect(mappingOne).toBeChecked();
    expect(mappingTwo).toBeChecked();

    await user.click(selectAll);

    expect(mappingOne).not.toBeChecked();
    expect(mappingTwo).not.toBeChecked();
    expect(screen.queryByRole('checkbox', { name: /PROC_REFRESH_AUDIT/ })).not.toBeInTheDocument();
  });

  it('rozwiązuje logical schema przez wybrany Context i pokazuje fizyczne metadane', async () => {
    const user = await openDemoDashboard();
    await user.click(
      screen.getByRole('button', { name: 'Otwórz load plan LP_DAILY_SALES' })
    );

    await user.selectOptions(await screen.findByLabelText('Context podglądu'), 'PRD');
    await user.click(screen.getByRole('button', { name: 'Pokaż lineage MAP_LOAD_ORDERS' }));

    expect(await screen.findByRole('heading', { name: 'MAP_LOAD_ORDERS' })).toBeVisible();
    const metadata = screen.getByRole('region', { name: 'Metadane zaznaczonego obiektu' });
    const physicalSchemaRow = within(metadata).getByText('Physical Schema').parentElement!;
    expect(within(physicalSchemaRow).getByText('DWH_PROD')).toBeVisible();
    expect(within(metadata).getByText('DATA-PROD-01')).toBeVisible();
    const resourceNameRow = within(metadata).getByText('Resource Name').parentElement!;
    expect(within(resourceNameRow).getByText('ORDERS')).toBeVisible();
    const aliasRow = within(metadata).getByText('Alias w mappingu').parentElement!;
    expect(within(aliasRow).getByText('SRC_ORDERS')).toBeVisible();
  });

  it('udostępnia graf Source → Target oraz tabelaryczny column lineage', async () => {
    const user = await openDemoDashboard();
    await user.click(
      screen.getByRole('button', { name: 'Otwórz load plan LP_DAILY_SALES' })
    );
    await user.click(await screen.findByRole('button', { name: 'Pokaż lineage MAP_LOAD_ORDERS' }));

    expect(await screen.findByRole('list', { name: 'Źródła mappingu' })).toBeVisible();
    expect(screen.getByRole('list', { name: 'Targety mappingu' })).toBeVisible();
    const table = screen.getByRole('table', { name: 'Tabelaryczny column lineage mappingu' });
    expect(within(table).getAllByText('ORDERS').length).toBeGreaterThan(0);
    expect(within(table).getAllByText('ORDER_FACT').length).toBeGreaterThan(0);
    expect(within(table).getAllByText('ORDER_ID').length).toBeGreaterThan(0);
    expect(screen.queryByText('FILTER_ACTIVE')).not.toBeInTheDocument();
  });
});
