import type { ReactNode } from 'react';

import type { SessionInfo } from '../api/types';
import { Icon } from './Icon';

interface AppShellProps {
  children: ReactNode;
  error?: string;
  mode: SessionInfo['mode'];
  repository: SessionInfo['repository'];
  onLogout(): void;
}

export function AppShell({ children, error, mode, repository, onLogout }: AppShellProps) {
  return (
    <div className="app-shell">
      <a className="skip-link" href="#main-content">
        Przejdź do treści
      </a>
      <header className="topbar">
        <div className="brand-lockup">
          <span className="brand-mark"><Icon name="flow" size={20} /></span>
          <span>
            <strong>ODI Lineage</strong>
            <small>Explorer</small>
          </span>
        </div>
        <div className="repository-context" aria-label="Aktywne repozytorium">
          <span className="status-dot" aria-hidden="true" />
          <span className="repository-copy">
            <small>{repository.name} / {repository.masterRepository}</small>
            <strong>{repository.workRepository}</strong>
          </span>
          <span className={`badge ${mode === 'DEMO' ? 'badge-demo' : 'badge-repository'}`}>
            {mode === 'DEMO' ? 'Tryb demo' : 'Repozytorium read-only'}
          </span>
          <button className="icon-button" type="button" aria-label="Zakończ sesję" onClick={onLogout}>
            <Icon name="logout" />
          </button>
        </div>
      </header>
      <main id="main-content" className="workspace">
        {error ? <div className="workspace-error" role="alert"><Icon name="warning" />{error}</div> : null}
        {children}
      </main>
    </div>
  );
}
