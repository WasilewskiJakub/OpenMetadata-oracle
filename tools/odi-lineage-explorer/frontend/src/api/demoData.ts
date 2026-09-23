import type {
  ContextCode,
  LoadPlanDetail,
  LoadPlanSummary,
  LoadPlanTreeStep,
  LineageExportDownload,
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
          { id: 'fact-load-note', name: 'LOAD_NOTE' },
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

export function getDemoLineageExport(
  loadPlanId: string,
  contextCode: ContextCode,
  mappingOccurrenceIds: string[]
): LineageExportDownload {
  if (mappingOccurrenceIds.length === 0) {
    throw new Error('Wybierz co najmniej jedno wystąpienie mappingu.');
  }
  if (mappingOccurrenceIds.length > 500) {
    throw new Error(
      'Jednorazowo można wyeksportować maksymalnie 500 wystąpień mappingów.'
    );
  }
  const loadPlan = getDemoLoadPlan(loadPlanId, contextCode);
  const requestedOccurrenceIds = new Set(mappingOccurrenceIds);
  if (requestedOccurrenceIds.size !== mappingOccurrenceIds.length) {
    throw new Error('Lista wystąpień mappingów zawiera duplikaty.');
  }
  const selectedOccurrences = loadPlan.mappings.filter(
    (mapping) => mapping.mappingId && requestedOccurrenceIds.has(mapping.stepId)
  );
  if (selectedOccurrences.length !== requestedOccurrenceIds.size) {
    throw new Error('Wybrano wystąpienie mappingu spoza bieżącego Load Planu.');
  }

  const mappedIds = new Set<string>();
  const selectedMappings = selectedOccurrences.flatMap((occurrence) => {
    if (!occurrence.mappingId || mappedIds.has(occurrence.mappingId)) return [];
    mappedIds.add(occurrence.mappingId);
    return [getDemoMapping(occurrence.mappingId, contextCode)];
  });
  const stepById = new Map(loadPlan.steps.map((step) => [step.id, step]));
  const document = {
    schemaVersion: '1.0',
    producer: {
      name: 'odi-lineage-explorer',
      version: '0.1.0',
    },
    source: {
      product: 'Oracle Data Integrator',
      productVersion: '14.1.2.0.0',
      repository: {
        name: 'ODI_DEMO',
        masterRepository: 'ODI_DEMO_MASTER',
        workRepository: 'ODI_DEMO_WORK',
      },
    },
    loadPlan: {
      id: loadPlan.id,
      name: loadPlan.name,
      contextCode,
    },
    mappingOccurrences: selectedOccurrences.map((occurrence) => {
      const step = stepById.get(occurrence.stepId);
      return {
        occurrenceId: occurrence.stepId,
        parentOccurrenceId: step?.parentStepId ?? null,
        path: step?.path ?? occurrence.stepPath ?? [],
        stepType: step?.stepType === 'PACKAGE_MAPPING'
          ? 'PACKAGE_MAPPING'
          : 'RUN_SCENARIO',
        enabled: occurrence.enabled,
        declaredContextCode:
          step?.declaredContextCode ?? occurrence.declaredContextCode ?? null,
        scenarioName: occurrence.scenarioName,
        scenarioVersion: occurrence.scenarioVersion,
        mappingId: occurrence.mappingId as string,
        mappingName: occurrence.mappingName ?? occurrence.scenarioName,
        resolution: occurrence.resolution,
        resolutionReason: occurrence.resolutionReason ?? null,
      };
    }),
    mappings: selectedMappings.map((mapping) => ({
      id: mapping.id,
      name: mapping.name,
      contextCode: mapping.contextCode,
      endpoints: mapping.nodes.map((node) => ({
        endpointId: node.id,
        role: node.kind === 'DATASTORE_SOURCE' ? 'SOURCE' : 'TARGET',
        alias: node.metadata?.alias ?? null,
        datastoreName: node.metadata?.datastoreName ?? null,
        identity: {
          technology: 'ORACLE',
          dataServer: node.metadata?.dataServer ?? null,
          catalog: node.metadata?.catalog ?? null,
          schema: node.metadata?.schema ?? null,
          resourceName: node.metadata?.resourceName ?? null,
          logicalSchema: node.metadata?.logicalSchema ?? null,
          modelName: node.metadata?.modelName ?? null,
          physicalSchema: node.metadata?.physicalSchema ?? null,
        },
        columns: node.columns,
      })),
      tableEdges: mapping.edges.map((edge) => ({
        fromEndpointId: edge.from,
        toEndpointId: edge.to,
      })),
      columnDerivations: mapping.nodes
        .filter((node) => node.kind === 'DATASTORE_TARGET')
        .flatMap((node) => node.columns.map((column) => {
          const fromColumns = mapping.columnLineage
            .filter(
              (edge) =>
                edge.toComponentId === node.id && edge.toColumnId === column.id
            )
            .map((edge) => ({
              endpointId: edge.fromComponentId,
              columnId: edge.fromColumnId,
            }));
          const isNullLiteral = column.id === 'fact-load-note';
          return {
            toColumn: { endpointId: node.id, columnId: column.id },
            fromColumns: isNullLiteral ? [] : fromColumns,
            kind: isNullLiteral
              ? 'NULL_LITERAL'
              : fromColumns.length > 0
                ? 'SOURCE_COLUMNS'
                : 'UNMAPPED',
            complete: true,
          };
        })),
      warnings: mapping.warnings,
    })),
  };
  const sanitize = (value: string) => value.replace(/[^A-Za-z0-9._-]+/g, '_');
  const fileName = [
    'odi-lineage',
    'ODI_DEMO_WORK',
    loadPlan.name,
    contextCode,
  ].map(sanitize).join('_') + '.json';

  return {
    blob: new Blob([`${JSON.stringify(document, null, 2)}\n`], {
      type: 'application/json',
    }),
    fileName,
  };
}
