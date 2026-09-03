import type {
  ContextCode,
  LoadPlanDetail,
  LoadPlanSummary,
  LoadPlanTreeStep,
  MappingDetail,
  OdiContext,
  PhysicalObjectMetadata,
  ScenarioMapping,
} from './types';

export const demoContexts: OdiContext[] = [
  { code: 'DEV', name: 'Development', isDefault: true },
  { code: 'TST', name: 'Test', isDefault: false },
  { code: 'PRD', name: 'Production', isDefault: false },
];

export const demoLoadPlans: LoadPlanSummary[] = [
  {
    id: 'lp-daily-sales',
    name: 'LP_DAILY_SALES',
    description: 'Dzienny przepływ zamówień i agregatów sprzedaży',
    project: 'DWH_SALES',
    folder: 'LOAD_PLANS',
    status: 'ENABLED',
    scenarioCount: 3,
    mappingCount: 2,
    unresolvedCount: 0,
    updatedAt: '2026-09-02T08:42:00Z',
  },
  {
    id: 'lp-customer-360',
    name: 'LP_CUSTOMER_360',
    description: 'Konsolidacja profilu klienta z systemów operacyjnych',
    project: 'DWH_CUSTOMER',
    folder: 'MASTER_DATA',
    status: 'ENABLED',
    scenarioCount: 7,
    mappingCount: 5,
    unresolvedCount: 1,
    updatedAt: '2026-09-01T18:10:00Z',
  },
  {
    id: 'lp-finance-close',
    name: 'LP_FINANCE_CLOSE',
    description: 'Miesięczne zamknięcie księgowe',
    project: 'DWH_FINANCE',
    folder: 'PERIOD_CLOSE',
    status: 'DISABLED',
    scenarioCount: 12,
    mappingCount: 9,
    unresolvedCount: 2,
    updatedAt: '2026-08-29T21:15:00Z',
  },
];

export function getDemoLoadPlan(id: string, contextCode: ContextCode): LoadPlanDetail {
  const summary = demoLoadPlans.find((item) => item.id === id);
  if (!summary) {
    throw new Error(`Nie znaleziono Load Planu: ${id}`);
  }

  const mappings: ScenarioMapping[] = [
      {
        stepId: 'step-orders',
        scenarioName: 'SCN_LOAD_ORDERS',
        scenarioVersion: '003',
        mappingId: 'map-load-orders',
        mappingName: 'MAP_LOAD_ORDERS',
        project: 'DWH_SALES',
        folder: 'ORDERS',
        enabled: true,
        resolution: 'RESOLVED',
      },
      {
        stepId: 'step-sales-fact',
        scenarioName: 'SCN_SALES_FACT',
        scenarioVersion: '012',
        mappingId: 'map-sales-fact',
        mappingName: 'MAP_SALES_FACT',
        project: 'DWH_SALES',
        folder: 'FACTS',
        enabled: true,
        resolution: 'RESOLVED',
      },
      {
        stepId: 'step-audit',
        scenarioName: 'PROC_REFRESH_AUDIT',
        scenarioVersion: '001',
        project: 'DWH_COMMON',
        folder: 'MAINTENANCE',
        enabled: true,
        resolution: 'OUT_OF_SCOPE',
      },
    ];
  const rootPath = ['root_step', 'parallel_sales'];
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
      path: rootPath,
      enabled: true,
    },
    ...mappings.map((mapping) => ({
      id: mapping.stepId,
      parentStepId: 'parallel-sales',
      name: mapping.mappingName ?? mapping.scenarioName,
      stepType: 'RUN_SCENARIO' as const,
      path: [...rootPath, mapping.mappingName ?? mapping.scenarioName],
      declaredContextCode: contextCode,
      scenarioName: mapping.scenarioName,
      scenarioVersion: mapping.scenarioVersion,
      mappingId: mapping.mappingId,
      mappingName: mapping.mappingName,
      resolution: mapping.resolution,
      enabled: mapping.enabled,
    })),
  ];

  return {
    ...summary,
    contextCode,
    mappings,
    steps,
  };
}

const contextMetadata: Record<string, Pick<PhysicalObjectMetadata, 'physicalSchema' | 'dataServer' | 'catalog' | 'schema' | 'isPhysicalLocationResolved'>> = {
  DEV: {
    physicalSchema: 'DWH_DEV',
    dataServer: 'DATA-DEV-01',
    catalog: 'ODIDEV',
    schema: 'DWH_DEV',
    isPhysicalLocationResolved: true,
  },
  TST: {
    physicalSchema: 'DWH_TEST',
    dataServer: 'DATA-TEST-01',
    catalog: 'ODITEST',
    schema: 'DWH_TEST',
    isPhysicalLocationResolved: true,
  },
  PRD: {
    physicalSchema: 'DWH_PROD',
    dataServer: 'DATA-PROD-01',
    catalog: 'ODIPROD',
    schema: 'DWH_PROD',
    isPhysicalLocationResolved: true,
  },
};

function datastoreMetadata(
  contextCode: ContextCode,
  values: Pick<PhysicalObjectMetadata, 'alias' | 'datastoreName' | 'resourceName'>
): PhysicalObjectMetadata {
  return {
    ...values,
    modelName: 'MDL_DWH_SALES',
    logicalSchema: 'LS_DWH',
    ...(contextMetadata[contextCode] ?? contextMetadata.DEV),
  };
}

export function getDemoMapping(id: string, contextCode: ContextCode): MappingDetail {
  const isSalesFact = id === 'map-sales-fact';

  return {
    id,
    name: isSalesFact ? 'MAP_SALES_FACT' : 'MAP_LOAD_ORDERS',
    project: 'DWH_SALES',
    folder: isSalesFact ? 'FACTS' : 'ORDERS',
    contextCode,
    warnings: [],
    nodes: [
      {
        id: 'src-orders',
        label: 'ORDERS',
        kind: 'DATASTORE_SOURCE',
        rawComponentType: 'DATASTORE_SOURCE',
        columns: [
          { id: 'orders-order-id', name: 'ORDER_ID' },
          { id: 'orders-customer-id', name: 'CUSTOMER_ID' },
          { id: 'orders-net-amount', name: 'NET_AMOUNT' },
        ],
        metadata: datastoreMetadata(contextCode, {
          alias: 'SRC_ORDERS',
          datastoreName: 'DS_ORDERS',
          resourceName: 'ORDERS',
        }),
      },
      {
        id: 'src-customers',
        label: 'CUSTOMERS',
        kind: 'DATASTORE_SOURCE',
        rawComponentType: 'DATASTORE_SOURCE',
        columns: [
          { id: 'customers-customer-id', name: 'CUSTOMER_ID' },
          { id: 'customers-country-code', name: 'COUNTRY_CODE' },
        ],
        metadata: datastoreMetadata(contextCode, {
          alias: 'SRC_CUSTOMERS',
          datastoreName: 'DS_CUSTOMERS',
          resourceName: 'CUSTOMERS',
        }),
      },
      {
        id: 'tgt-order-fact',
        label: 'ORDER_FACT',
        kind: 'DATASTORE_TARGET',
        rawComponentType: 'DATASTORE_TARGET',
        columns: [
          { id: 'fact-order-id', name: 'ORDER_ID' },
          { id: 'fact-customer-key', name: 'CUSTOMER_KEY' },
          { id: 'fact-net-amount', name: 'NET_AMOUNT' },
          { id: 'fact-country-code', name: 'COUNTRY_CODE' },
        ],
        metadata: datastoreMetadata(contextCode, {
          alias: 'TGT_ORDER_FACT',
          datastoreName: 'DS_ORDER_FACT',
          resourceName: 'ORDER_FACT',
        }),
      },
    ],
    edges: [
      { id: 'e1', from: 'src-orders', to: 'tgt-order-fact' },
      { id: 'e2', from: 'src-customers', to: 'tgt-order-fact' },
    ],
    columnLineage: [
      {
        id: 'ce1',
        fromComponentId: 'src-orders',
        fromColumnId: 'orders-order-id',
        toComponentId: 'tgt-order-fact',
        toColumnId: 'fact-order-id',
      },
      {
        id: 'ce2',
        fromComponentId: 'src-orders',
        fromColumnId: 'orders-customer-id',
        toComponentId: 'tgt-order-fact',
        toColumnId: 'fact-customer-key',
      },
      {
        id: 'ce3',
        fromComponentId: 'src-customers',
        fromColumnId: 'customers-customer-id',
        toComponentId: 'tgt-order-fact',
        toColumnId: 'fact-customer-key',
      },
      {
        id: 'ce4',
        fromComponentId: 'src-orders',
        fromColumnId: 'orders-net-amount',
        toComponentId: 'tgt-order-fact',
        toColumnId: 'fact-net-amount',
      },
      {
        id: 'ce5',
        fromComponentId: 'src-customers',
        fromColumnId: 'customers-country-code',
        toComponentId: 'tgt-order-fact',
        toColumnId: 'fact-country-code',
      },
    ],
  };
}
