import { useRef, useState, type FormEvent } from 'react';

import type { SessionCredentials } from '../api/types';
import { Icon } from './Icon';

interface ConnectionViewProps {
  error?: string;
  isLoading: boolean;
  onConnect(credentials: SessionCredentials): Promise<void>;
  onOpenDemo(): Promise<void>;
}

interface PasswordFieldProps {
  id: string;
  label: string;
  value: string;
  required?: boolean;
  onChange(value: string): void;
}

function PasswordField({ id, label, value, required, onChange }: PasswordFieldProps) {
  const [isVisible, setIsVisible] = useState(false);

  return (
    <div className="field-group">
      <label htmlFor={id}>{label}</label>
      <div className="password-control">
        <input
          autoComplete="off"
          id={id}
          name={id}
          onChange={(event) => onChange(event.target.value)}
          required={required}
          type={isVisible ? 'text' : 'password'}
          value={value}
        />
        <button
          aria-label={isVisible ? `Ukryj: ${label}` : `Pokaż: ${label}`}
          className="field-icon-button"
          onClick={() => setIsVisible((current) => !current)}
          type="button">
          <Icon name={isVisible ? 'eye-off' : 'eye'} />
        </button>
      </div>
    </div>
  );
}

const initialCredentials: SessionCredentials = {
  jdbcUrl: 'jdbc:oracle:thin:@//172.28.48.1:15210/odipdb',
  repositoryUsername: 'CBK_ODI14C_MASTER',
  repositoryPassword: '',
  workRepositoryName: 'DEV_WORKREP',
  odiUsername: 'SUPERVISOR',
  odiPassword: '',
};

type PendingAction = 'connect' | 'demo';

export function ConnectionView({
  error,
  isLoading,
  onConnect,
  onOpenDemo,
}: ConnectionViewProps) {
  const headingRef = useRef<HTMLHeadingElement>(null);
  const [credentials, setCredentials] = useState<SessionCredentials>(initialCredentials);
  const [pendingAction, setPendingAction] = useState<PendingAction>();
  const isBusy = isLoading || pendingAction !== undefined;

  function updateCredential<Key extends keyof SessionCredentials>(
    key: Key,
    value: SessionCredentials[Key]
  ) {
    setCredentials((current) => ({ ...current, [key]: value }));
  }

  function clearPasswords() {
    setCredentials((current) => ({
      ...current,
      repositoryPassword: '',
      odiPassword: '',
    }));
  }

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setPendingAction('connect');
    try {
      await onConnect(credentials);
    } finally {
      clearPasswords();
      setPendingAction(undefined);
    }
  }

  async function handleOpenDemo() {
    setPendingAction('demo');
    try {
      await onOpenDemo();
    } finally {
      clearPasswords();
      setPendingAction(undefined);
    }
  }

  return (
    <main className="connection-page">
      <section className="connection-story" aria-labelledby="connection-title">
        <div className="eyebrow"><span /> Read-only workspace</div>
        <h1 id="connection-title" ref={headingRef}>Zobacz przepływ danych<br />z perspektywy ODI.</h1>
        <p className="lede">
          Przeglądaj Load Plany, rozwiązuj Logical Schema przez Context i analizuj mappingi bez
          modyfikowania repozytorium.
        </p>
        <div className="trust-grid">
          <article><Icon name="shield" /><strong>Tylko odczyt</strong><span>SDK bez operacji zapisu</span></article>
          <article><Icon name="lock" /><strong>Hasła w pamięci</strong><span>Bez localStorage i plików</span></article>
          <article><Icon name="flow" /><strong>Context-aware</strong><span>Logical → Physical Schema</span></article>
        </div>
        <div className="lineage-preview" aria-hidden="true">
          <span className="preview-node">ORDERS</span><i /><span className="preview-node target">ORDER_FACT</span>
        </div>
      </section>

      <section className="connection-panel" aria-labelledby="form-title">
        <div className="demo-notice" role="status">
          <Icon name="shield" />
          <span><strong>Połączenie tylko do odczytu</strong> Hasła pozostają w pamięci i są usuwane po każdej próbie.</span>
        </div>
        <header>
          <p className="kicker">Nowa sesja</p>
          <h2 id="form-title">Połączenie z ODI</h2>
          <p>Dane dostępowe żyją wyłącznie w pamięci bieżącej karty.</p>
        </header>
        {error ? <div className="error-banner" role="alert"><Icon name="warning" />{error}</div> : null}
        <form aria-busy={isBusy} autoComplete="off" onSubmit={handleSubmit}>
          <div className="field-group field-span-2">
            <label htmlFor="jdbc-url">JDBC URL</label>
            <div className="input-with-icon"><Icon name="server" /><input autoComplete="off" id="jdbc-url" name="jdbcUrl" onChange={(event) => updateCredential('jdbcUrl', event.target.value)} required value={credentials.jdbcUrl} /></div>
          </div>
          <div className="field-group">
            <label htmlFor="repository-schema">Schemat repozytorium</label>
            <input autoComplete="off" id="repository-schema" name="repositoryUsername" onChange={(event) => updateCredential('repositoryUsername', event.target.value)} required value={credentials.repositoryUsername} />
          </div>
          <PasswordField id="repository-password" label="Hasło schematu repozytorium" required value={credentials.repositoryPassword} onChange={(value) => updateCredential('repositoryPassword', value)} />
          <div className="field-group">
            <label htmlFor="work-repository">Work Repository</label>
            <input autoComplete="off" id="work-repository" name="workRepositoryName" onChange={(event) => updateCredential('workRepositoryName', event.target.value)} required value={credentials.workRepositoryName} />
          </div>
          <div className="field-group">
            <label htmlFor="odi-user">Użytkownik ODI</label>
            <input autoComplete="off" id="odi-user" name="odiUsername" onChange={(event) => updateCredential('odiUsername', event.target.value)} required value={credentials.odiUsername} />
          </div>
          <PasswordField id="odi-password" label="Hasło użytkownika ODI" required value={credentials.odiPassword} onChange={(value) => updateCredential('odiPassword', value)} />
          <div className="security-note field-span-2">
            <Icon name="shield" />
            <span>Backend nie otwiera transakcji i korzysta wyłącznie z odczytowych Finderów ODI SDK.</span>
          </div>
          <button className="primary-button field-span-2" disabled={isBusy} type="submit">
            {pendingAction === 'connect' ? 'Łączenie read-only…' : 'Połącz read-only'}
            <Icon name="arrow-right" />
          </button>
          <div className="demo-action field-span-2">
            <span><strong>Chcesz najpierw obejrzeć interfejs?</strong> Demo nie wymaga danych logowania.</span>
            <button className="secondary-button" disabled={isBusy} onClick={handleOpenDemo} type="button">
              {pendingAction === 'demo' ? 'Otwieranie demo…' : 'Otwórz demo'}
              <Icon name="layers" />
            </button>
          </div>
        </form>
      </section>
    </main>
  );
}
