import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';

import { ConnectionView } from './ConnectionView';

const REPOSITORY_PASSWORD = 'repository-secret';
const ODI_PASSWORD = 'odi-secret';

async function enterPasswords(user: ReturnType<typeof userEvent.setup>) {
  await user.type(
    screen.getByLabelText('Hasło schematu repozytorium'),
    REPOSITORY_PASSWORD
  );
  await user.type(screen.getByLabelText('Hasło użytkownika ODI'), ODI_PASSWORD);
}

describe('formularz połączenia ODI', () => {
  it('podpowiada zweryfikowane dane aktualnego laboratorium ODI', () => {
    render(
      <ConnectionView
        isLoading={false}
        onConnect={vi.fn()}
        onOpenDemo={vi.fn()}
      />
    );

    expect(screen.getByLabelText('Schemat repozytorium')).toHaveValue(
      'CBK_ODI14C_MASTER'
    );
    expect(screen.getByLabelText('Work Repository')).toHaveValue('DEV_WORKREP');
  });

  it('czyści oba hasła po udanym wysłaniu realnego połączenia', async () => {
    const user = userEvent.setup();
    const onConnect = vi.fn().mockResolvedValue(undefined);
    render(
      <ConnectionView
        isLoading={false}
        onConnect={onConnect}
        onOpenDemo={vi.fn()}
      />
    );

    await enterPasswords(user);
    await user.click(screen.getByRole('button', { name: 'Połącz read-only' }));

    await waitFor(() => expect(onConnect).toHaveBeenCalledOnce());
    expect(onConnect).toHaveBeenCalledWith(expect.objectContaining({
      repositoryPassword: REPOSITORY_PASSWORD,
      odiPassword: ODI_PASSWORD,
    }));
    expect(screen.getByLabelText('Hasło schematu repozytorium')).toHaveValue('');
    expect(screen.getByLabelText('Hasło użytkownika ODI')).toHaveValue('');
  });

  it('otwiera demo bez wysyłania poświadczeń i usuwa wpisane hasła', async () => {
    const user = userEvent.setup();
    const onConnect = vi.fn();
    const onOpenDemo = vi.fn().mockResolvedValue(undefined);
    render(
      <ConnectionView
        isLoading={false}
        onConnect={onConnect}
        onOpenDemo={onOpenDemo}
      />
    );

    await enterPasswords(user);
    await user.click(screen.getByRole('button', { name: 'Otwórz demo' }));

    await waitFor(() => expect(onOpenDemo).toHaveBeenCalledOnce());
    expect(onConnect).not.toHaveBeenCalled();
    expect(screen.getByLabelText('Hasło schematu repozytorium')).toHaveValue('');
    expect(screen.getByLabelText('Hasło użytkownika ODI')).toHaveValue('');
  });
});
