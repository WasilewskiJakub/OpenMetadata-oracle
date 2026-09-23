import { afterEach, describe, expect, it, vi } from 'vitest';

import { ApiError, createDemoApiClient, createHttpApiClient } from './client';
import type { SessionCredentials } from './types';

const response = (body: unknown, status = 200) =>
  Promise.resolve(new Response(status === 204 ? null : JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  }));

const readBrowserBlob = (blob: Blob) => new Promise<string>((resolve, reject) => {
  const reader = new FileReader();
  reader.addEventListener('load', () => resolve(String(reader.result)));
  reader.addEventListener('error', () => reject(reader.error));
  reader.readAsText(blob);
});

describe('HTTP API adapter', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('adaptuje wire contract backendu do modelu widoków', async () => {
    const fetchMock = vi.fn()
      .mockImplementationOnce(() => response({
        token: 'token',
        repository: { name: 'ODI_DEMO', masterRepository: 'MASTER', workRepository: 'WORKREP' },
        expiresAt: '2026-09-02T22:00:00Z',
      }))
      .mockImplementationOnce(() => response([
        { code: 'DEV', name: 'Development', isDefault: true },
        { code: 'PROD', name: 'Production', isDefault: false },
      ]))
      .mockImplementationOnce(() => response([
        { id: 'lp-sales', name: 'Daily Sales Load', description: 'Daily load', scenarioCount: 1, mappingCount: 1 },
      ]))
      .mockImplementationOnce(() => response({
        id: 'lp-sales',
        name: 'Daily Sales Load',
        contextCode: 'PROD',
        steps: [
          {
            id: 'root',
            name: 'root_step',
            stepType: 'ROOT_SERIAL',
            path: ['root_step'],
            enabled: true,
          },
          {
            id: 'parallel',
            parentStepId: 'root',
            name: 'parallel_loads',
            stepType: 'PARALLEL',
            path: ['root_step', 'parallel_loads'],
            enabled: true,
          },
          {
            id: 'step-orders',
            parentStepId: 'parallel',
            name: 'load_orders',
            stepType: 'RUN_SCENARIO',
            path: ['root_step', 'parallel_loads', 'load_orders'],
            declaredContextCode: 'PROD',
            scenarioName: 'SCEN_LOAD_ORDERS',
            scenarioVersion: '001',
            mappingId: 'map-orders',
            mappingName: 'Load Orders',
            resolution: 'RESOLVED',
            enabled: true,
          },
        ],
      }))
      .mockImplementationOnce(() => response({
        id: 'map-orders',
        name: 'Load Orders',
        contextCode: 'PROD',
        components: [
          {
            id: 'orders-source',
            componentType: 'DATASTORE_SOURCE',
            componentAlias: 'ORDERS_SRC',
            datastoreName: 'Orders',
            resourceName: 'ORDERS',
            modelName: 'Sales Source Model',
            logicalSchema: 'SALES_LOGICAL',
            columns: [{ id: 'source-order-id', name: 'ORDER_ID' }],
            physicalLocation: {
              physicalSchema: 'ORACLE_PROD.SALES',
              dataServer: 'oracle-prod',
              catalog: 'ODIPDB',
              schema: 'SALES',
            },
          },
          {
            id: 'orders-filter',
            componentType: 'FILTER',
            componentAlias: 'ONLY_ACTIVE',
            columns: [{ id: 'filtered-order-id', name: 'ORDER_ID' }],
          },
          {
            id: 'orders-target',
            componentType: 'DATASTORE_TARGET',
            componentAlias: 'ORDERS_TGT',
            datastoreName: 'Order fact',
            resourceName: 'ORDER_FACT',
            modelName: 'Sales Target Model',
            logicalSchema: 'DWH_LOGICAL',
            columns: [{ id: 'target-order-id', name: 'ORDER_ID' }],
          },
        ],
        edges: [{ fromComponentId: 'orders-source', toComponentId: 'orders-target' }],
        columnLineage: [{
          fromComponentId: 'orders-source',
          fromColumnId: 'source-order-id',
          toComponentId: 'orders-target',
          toColumnId: 'target-order-id',
        }],
        warnings: ['Reusable mapping zawiera nierozwiązaną referencję.'],
      }));
    vi.stubGlobal('fetch', fetchMock);

    const client = createHttpApiClient();
    const session = await client.createDemoSession();
    const contexts = await client.getContexts(session.token);
    const plans = await client.getLoadPlans(session.token);
    const plan = await client.getLoadPlan(session.token, plans[0].id, 'PROD');
    const mapping = await client.getMapping(session.token, plan.mappings[0].mappingId!, 'PROD');

    expect(session.repository.masterRepository).toBe('MASTER');
    expect(session.mode).toBe('DEMO');
    expect(contexts.map(({ code }) => code)).toEqual(['DEV', 'PROD']);
    expect(plan.mappings[0]).toMatchObject({
      scenarioName: 'SCEN_LOAD_ORDERS',
      mappingName: 'Load Orders',
      resolution: 'RESOLVED',
      declaredContextCode: 'PROD',
    });
    expect(plan.steps.map(({ stepType }) => stepType)).toEqual([
      'ROOT_SERIAL',
      'PARALLEL',
      'RUN_SCENARIO',
    ]);
    expect(mapping.nodes[0].metadata).toMatchObject({
      alias: 'ORDERS_SRC',
      resourceName: 'ORDERS',
      modelName: 'Sales Source Model',
      logicalSchema: 'SALES_LOGICAL',
      physicalSchema: 'ORACLE_PROD.SALES',
      dataServer: 'oracle-prod',
    });
    expect(mapping.nodes.map(({ label }) => label)).toEqual(['ORDERS', 'ORDER_FACT']);
    expect(mapping.nodes[0].columns).toEqual([{ id: 'source-order-id', name: 'ORDER_ID' }]);
    expect(mapping.edges).toEqual([
      { id: 'edge-1', from: 'orders-source', to: 'orders-target' },
    ]);
    expect(mapping.columnLineage).toEqual([
      {
        id: 'column-edge-1',
        fromComponentId: 'orders-source',
        fromColumnId: 'source-order-id',
        toComponentId: 'orders-target',
        toColumnId: 'target-order-id',
      },
    ]);
    expect(mapping.warnings).toEqual([
      'Reusable mapping zawiera nierozwiązaną referencję.',
    ]);
    expect(fetchMock).toHaveBeenNthCalledWith(
      4,
      '/api/load-plans/lp-sales?contextCode=PROD',
      expect.objectContaining({ headers: expect.objectContaining({ Authorization: 'Bearer token' }) })
    );
  });

  it('wysyła dane połączenia do endpointu realnej sesji', async () => {
    const credentials: SessionCredentials = {
      jdbcUrl: 'jdbc:oracle:thin:@//172.28.48.1:15210/odipdb',
      repositoryUsername: 'CBK_ODI14C_MASTER',
      repositoryPassword: 'repository-secret',
      workRepositoryName: 'DEV_WORKREP',
      odiUsername: 'ODI_READONLY',
      odiPassword: 'odi-secret',
    };
    const fetchMock = vi.fn().mockImplementation(() => response({
      token: 'real-session-token',
      repository: {
        name: 'DEV_WORKREP',
        masterRepository: 'CBK_ODI14C_MASTER',
        workRepository: 'DEV_WORKREP',
      },
      expiresAt: '2026-09-02T23:00:00Z',
    }, 201));
    vi.stubGlobal('fetch', fetchMock);

    const session = await createHttpApiClient().createSession(credentials);

    expect(session.token).toBe('real-session-token');
    expect(session.mode).toBe('REPOSITORY');
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/sessions',
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify(credentials),
        headers: expect.objectContaining({ 'Content-Type': 'application/json' }),
      })
    );
  });

  it('pobiera eksport wybranych wystąpień mappingów jako nazwany plik JSON', async () => {
    const exportBody = '{"schemaVersion":"1.0"}\n';
    const fetchMock = vi.fn().mockResolvedValue(new Response(exportBody, {
      status: 200,
      headers: {
        'Content-Type': 'application/json',
        'Content-Disposition': 'attachment; filename="odi-lineage-demo.json"',
      },
    }));
    vi.stubGlobal('fetch', fetchMock);

    const download = await createHttpApiClient().exportLoadPlanLineage(
      'session-token',
      'load-plan-1',
      'MIG_CSIRE_DEV',
      ['step-first', 'step-second']
    );

    expect(download.fileName).toBe('odi-lineage-demo.json');
    expect(download.blob.type).toBe('application/json');
    expect(await download.blob.text()).toBe(exportBody);
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/load-plan-exports',
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({
          loadPlanId: 'load-plan-1',
          contextCode: 'MIG_CSIRE_DEV',
          mappingOccurrenceIds: ['step-first', 'step-second'],
        }),
        headers: expect.objectContaining({
          Accept: 'application/json',
          Authorization: 'Bearer session-token',
          'Content-Type': 'application/json',
        }),
      })
    );
  });

  it('tworzy deterministyczny demo eksport v1 z jawnym NULL_LITERAL', async () => {
    const client = createDemoApiClient();
    const session = await client.createDemoSession();

    const first = await client.exportLoadPlanLineage(
      session.token,
      'lp-daily-sales',
      'DEV',
      ['step-orders']
    );
    const second = await client.exportLoadPlanLineage(
      session.token,
      'lp-daily-sales',
      'DEV',
      ['step-orders']
    );
    const firstText = await readBrowserBlob(first.blob);
    const document = JSON.parse(firstText) as {
      schemaVersion: string;
      source: { repository: { workRepository: string } };
      mappingOccurrences: Array<{ occurrenceId: string }>;
      mappings: Array<{
        columnDerivations: Array<{
          kind: string;
          complete: boolean;
          fromColumns: unknown[];
          toColumn: { columnId: string };
        }>;
      }>;
    };

    expect(first.fileName).toBe('odi-lineage_ODI_DEMO_WORK_LP_DAILY_SALES_DEV.json');
    expect(second.fileName).toBe(first.fileName);
    expect(await readBrowserBlob(second.blob)).toBe(firstText);
    expect(document.schemaVersion).toBe('1.0');
    expect(document.source.repository.workRepository).toBe('ODI_DEMO_WORK');
    expect(document.mappingOccurrences.map(({ occurrenceId }) => occurrenceId)).toEqual([
      'step-orders',
    ]);
    expect(
      document.mappings.flatMap(({ columnDerivations }) => columnDerivations)
    ).toContainEqual(expect.objectContaining({
      kind: 'NULL_LITERAL',
      complete: true,
      fromColumns: [],
      toColumn: expect.objectContaining({ columnId: 'fact-load-note' }),
    }));
    expect(firstText).not.toMatch(/jdbc|password|credential/i);
  });

  it('odrzuca pustą listę wystąpień w demo eksporcie', async () => {
    const client = createDemoApiClient();
    const session = await client.createDemoSession();

    await expect(client.exportLoadPlanLineage(
      session.token,
      'lp-daily-sales',
      'DEV',
      []
    )).rejects.toThrow('Wybierz co najmniej jedno wystąpienie mappingu.');
  });

  it('odrzuca zduplikowane identyfikatory wystąpień w demo eksporcie', async () => {
    const client = createDemoApiClient();
    const session = await client.createDemoSession();

    await expect(client.exportLoadPlanLineage(
      session.token,
      'lp-daily-sales',
      'DEV',
      ['step-orders', 'step-orders']
    )).rejects.toThrow('Lista wystąpień mappingów zawiera duplikaty.');
  });

  it('ogranicza demo eksport do 500 wystąpień', async () => {
    const client = createDemoApiClient();
    const session = await client.createDemoSession();

    await expect(client.exportLoadPlanLineage(
      session.token,
      'lp-daily-sales',
      'DEV',
      Array.from({ length: 501 }, (_, index) => `step-${index}`)
    )).rejects.toThrow('Jednorazowo można wyeksportować maksymalnie 500 wystąpień mappingów.');
  });

  it('przekazuje bezpieczny komunikat błędu zwrócony przez backend', async () => {
    vi.stubGlobal('fetch', vi.fn().mockImplementation(() => response({
      code: 'AUTHENTICATION_FAILED',
      message: 'Authentication to the ODI repository failed',
    }, 401)));

    await expect(createHttpApiClient().createSession({
      jdbcUrl: 'jdbc:oracle:thin:@//172.28.48.1:15210/odipdb',
      repositoryUsername: 'CBK_ODI14C_MASTER',
      repositoryPassword: 'invalid',
      workRepositoryName: 'DEV_WORKREP',
      odiUsername: 'ODI_READONLY',
      odiPassword: 'invalid',
    })).rejects.toMatchObject({
      status: 401,
      code: 'AUTHENTICATION_FAILED',
      message: 'Authentication to the ODI repository failed',
    } satisfies Partial<ApiError>);
  });

  it('nie wystawia komponentów transformacji jako obiektów lineage', async () => {
    vi.stubGlobal('fetch', vi.fn().mockImplementation(() => response({
      id: 'map-lookup',
      name: 'Lookup customers',
      contextCode: 'DEV',
      components: [
        {
          id: 'customer-source',
          componentType: 'DATASTORE_SOURCE',
          componentAlias: 'CUSTOMER_SRC',
          datastoreName: 'Customers',
          resourceName: 'CUSTOMERS',
          modelName: 'Customer Model',
          logicalSchema: 'CUSTOMER_LOGICAL',
          resolutionReason: 'Logical Schema is not mapped in DEV',
          columns: [{ id: 'customer-id', name: 'CUSTOMER_ID' }],
        },
        {
          id: 'customer-lookup',
          componentType: 'LOOKUP',
          componentAlias: 'CUSTOMER_LKP',
          columns: [{ id: 'lookup-id', name: 'CUSTOMER_ID' }],
        },
        {
          id: 'customer-expression',
          componentType: 'EXPRESSION',
          componentAlias: 'CUSTOMER_EXPR',
          columns: [{ id: 'expression-id', name: 'CUSTOMER_KEY' }],
        },
        {
          id: 'customer-target',
          componentType: 'DATASTORE_TARGET',
          componentAlias: 'CUSTOMER_TGT',
          datastoreName: 'Customer dimension',
          resourceName: 'DIM_CUSTOMER',
          modelName: 'DWH Model',
          logicalSchema: 'DWH_LOGICAL',
          columns: [{ id: 'target-id', name: 'CUSTOMER_KEY' }],
        },
      ],
      edges: [
        { fromComponentId: 'customer-source', toComponentId: 'customer-target' },
        { fromComponentId: 'customer-source', toComponentId: 'customer-lookup' },
      ],
      columnLineage: [
        {
          fromComponentId: 'customer-source',
          fromColumnId: 'customer-id',
          toComponentId: 'customer-target',
          toColumnId: 'target-id',
        },
        {
          fromComponentId: 'customer-source',
          fromColumnId: 'customer-id',
          toComponentId: 'customer-expression',
          toColumnId: 'expression-id',
        },
      ],
    })));

    const mapping = await createHttpApiClient().getMapping('token', 'map-lookup', 'DEV');

    expect(mapping.nodes).toHaveLength(2);
    expect(mapping.nodes.map(({ kind }) => kind)).toEqual([
      'DATASTORE_SOURCE',
      'DATASTORE_TARGET',
    ]);
    expect(mapping.nodes[0]).toMatchObject({
      label: 'CUSTOMERS',
      rawComponentType: 'DATASTORE_SOURCE',
      metadata: {
        datastoreName: 'Customers',
        resourceName: 'CUSTOMERS',
        logicalSchema: 'CUSTOMER_LOGICAL',
        isPhysicalLocationResolved: false,
        resolutionReason: 'Logical Schema is not mapped in DEV',
      },
    });
    expect(mapping.edges).toEqual([
      { id: 'edge-1', from: 'customer-source', to: 'customer-target' },
    ]);
    expect(mapping.columnLineage).toEqual([
      {
        id: 'column-edge-1',
        fromComponentId: 'customer-source',
        fromColumnId: 'customer-id',
        toComponentId: 'customer-target',
        toColumnId: 'target-id',
      },
    ]);
  });
});
