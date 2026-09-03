import { useState } from 'react';

import { ApiError, asContextCode, createConfiguredApiClient } from './api/client';
import type {
  ApiClient,
  ContextCode,
  LoadPlanDetail,
  LoadPlanSummary,
  MappingDetail,
  OdiContext,
  SessionCredentials,
  SessionInfo,
} from './api/types';
import { ConnectionView } from './components/ConnectionView';
import { DashboardView } from './components/DashboardView';
import { Icon } from './components/Icon';
import { LoadPlanView } from './components/LoadPlanView';
import { MappingView } from './components/MappingView';

type View = 'connection' | 'dashboard' | 'load-plan' | 'mapping';

interface AppProps {
  api?: ApiClient;
}

export function App({ api }: AppProps) {
  const [client] = useState<ApiClient>(() => api ?? createConfiguredApiClient());
  const [view, setView] = useState<View>('connection');
  const [session, setSession] = useState<SessionInfo>();
  const [contexts, setContexts] = useState<OdiContext[]>([]);
  const [plans, setPlans] = useState<LoadPlanSummary[]>([]);
  const [loadPlan, setLoadPlan] = useState<LoadPlanDetail>();
  const [mapping, setMapping] = useState<MappingDetail>();
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState<string>();

  async function openSession(
    createSession: () => Promise<SessionInfo>,
    fallbackError: string
  ) {
    setIsLoading(true);
    setError(undefined);
    let newSession: SessionInfo | undefined;
    try {
      newSession = await createSession();
      const bootstrap = await Promise.allSettled([
        client.getContexts(newSession.token),
        client.getLoadPlans(newSession.token),
      ]);
      const failure = bootstrap.find((result) => result.status === 'rejected');
      if (failure?.status === 'rejected') {
        throw failure.reason;
      }
      const [availableContexts, availablePlans] = bootstrap.map(
        (result) => result.status === 'fulfilled' ? result.value : []
      ) as [OdiContext[], LoadPlanSummary[]];
      setSession(newSession);
      setContexts(availableContexts);
      setPlans(availablePlans);
      setView('dashboard');
    } catch (caught) {
      if (newSession) {
        await closeBackendSession(newSession.token);
      }
      resetLocalSession();
      setError(caught instanceof Error ? caught.message : fallbackError);
    } finally {
      setIsLoading(false);
    }
  }

  async function connect(credentials: SessionCredentials) {
    await openSession(
      () => client.createSession(credentials),
      'Nie udało się połączyć z repozytorium ODI.'
    );
  }

  async function openDemo() {
    await openSession(
      () => client.createDemoSession(),
      'Nie udało się otworzyć sesji demo.'
    );
  }

  async function openLoadPlan(id: string, contextCode?: ContextCode) {
    if (!session) return;
    setIsLoading(true);
    setError(undefined);
    try {
      const selectedContext =
        contextCode ?? contexts.find((context) => context.isDefault)?.code;
      if (!selectedContext) {
        throw new Error(
          'Repozytorium ODI nie wskazuje domyślnego Contextu. Ustaw go w ODI i spróbuj ponownie.'
        );
      }
      const detail = await client.getLoadPlan(session.token, id, selectedContext);
      setLoadPlan(detail);
      setMapping(undefined);
      setView('load-plan');
    } catch (caught) {
      handleAuthenticatedError(caught, 'Nie udało się pobrać Load Planu.');
    } finally {
      setIsLoading(false);
    }
  }

  async function changeContext(contextCode: ContextCode) {
    if (!loadPlan) return;
    await openLoadPlan(loadPlan.id, asContextCode(contextCode));
  }

  async function openMapping(id: string) {
    if (!session || !loadPlan) return;
    setIsLoading(true);
    setError(undefined);
    try {
      const detail = await client.getMapping(session.token, id, loadPlan.contextCode);
      setMapping(detail);
      setView('mapping');
    } catch (caught) {
      handleAuthenticatedError(caught, 'Nie udało się pobrać mappingu.');
    } finally {
      setIsLoading(false);
    }
  }

  async function logout() {
    if (session) {
      await closeBackendSession(session.token);
    }
    resetLocalSession();
  }

  async function closeBackendSession(token: string) {
    try {
      await client.endSession(token);
    } catch {
      // Local cleanup must still complete when the backend session already expired.
    }
  }

  function handleAuthenticatedError(caught: unknown, fallbackError: string) {
    if (caught instanceof ApiError && caught.status === 401) {
      resetLocalSession();
    }
    setError(caught instanceof Error ? caught.message : fallbackError);
  }

  function resetLocalSession() {
    setSession(undefined);
    setLoadPlan(undefined);
    setMapping(undefined);
    setPlans([]);
    setContexts([]);
    setView('connection');
  }

  if (isLoading && view !== 'connection') {
    return <div className="loading-screen" role="status"><span className="loading-mark"><Icon name="flow" /></span><strong>Odczytuję repozytorium ODI…</strong><small>Operacje pozostają tylko do odczytu</small></div>;
  }

  if (!session || view === 'connection') {
    return (
      <ConnectionView
        error={error}
        isLoading={isLoading}
        onConnect={connect}
        onOpenDemo={openDemo}
      />
    );
  }

  if (view === 'mapping' && mapping && loadPlan) {
    return <MappingView error={error} mapping={mapping} session={session} onBack={() => setView('load-plan')} onDashboard={() => setView('dashboard')} onLogout={logout} />;
  }

  if (view === 'load-plan' && loadPlan) {
    return <LoadPlanView contexts={contexts} detail={loadPlan} error={error} session={session} onBack={() => setView('dashboard')} onContextChange={changeContext} onLogout={logout} onOpenMapping={openMapping} />;
  }

  return <DashboardView contexts={contexts} error={error} plans={plans} session={session} onLogout={logout} onOpenPlan={openLoadPlan} />;
}
