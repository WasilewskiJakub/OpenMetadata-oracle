import { demoContexts, demoLoadPlans, getDemoLoadPlan, getDemoMapping } from './demoData';
import type {
  ApiClient,
  ContextCode,
  LoadPlanDetail,
  LoadPlanStepType,
  LoadPlanSummary,
  MappingDetail,
  MappingNode,
  MappingNodeKind,
  OdiContext,
  ScenarioResolution,
  SessionCredentials,
  SessionInfo,
} from './types';

const API_ROOT = '/api';

interface ErrorPayload {
  code: string;
  message: string;
}

export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string
  ) {
    super(message);
    this.name = 'ApiError';
  }
}

async function readErrorPayload(response: Response): Promise<ErrorPayload> {
  const fallbackMessage = `API zwróciło status ${response.status}`;
  try {
    const body = (await response.json()) as unknown;
    if (
      typeof body === 'object' &&
      body !== null &&
      'message' in body &&
      typeof body.message === 'string' &&
      body.message.trim()
    ) {
      return {
        code: 'code' in body && typeof body.code === 'string' ? body.code : 'HTTP_ERROR',
        message: body.message,
      };
    }
  } catch {
    return { code: 'HTTP_ERROR', message: fallbackMessage };
  }
  return { code: 'HTTP_ERROR', message: fallbackMessage };
}

async function request<T>(path: string, token?: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`${API_ROOT}${path}`, {
    ...init,
    headers: {
      Accept: 'application/json',
      ...(init?.body ? { 'Content-Type': 'application/json' } : {}),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...init?.headers,
    },
  });

  if (!response.ok) {
    const error = await readErrorPayload(response);
    throw new ApiError(response.status, error.code, error.message);
  }

  return response.status === 204 ? (undefined as T) : ((await response.json()) as T);
}

export function createHttpApiClient(): ApiClient {
  return {
    createSession: async (credentials) => {
      const session = await request<WireSessionInfo>('/sessions', undefined, {
        method: 'POST',
        body: JSON.stringify(credentials),
      });
      return { ...session, mode: 'REPOSITORY' };
    },
    createDemoSession: async () => {
      const session = await request<WireSessionInfo>('/sessions/demo', undefined, {
        method: 'POST',
      });
      return { ...session, mode: 'DEMO' };
    },
    getContexts: (token) => request<OdiContext[]>('/contexts', token),
    getLoadPlans: async (token) => {
      const plans = await request<WireLoadPlanSummary[]>('/load-plans', token);
      return plans.map(toLoadPlanSummary);
    },
    getLoadPlan: async (token, id, contextCode) => {
      const plan = await request<WireLoadPlanDetail>(
        `/load-plans/${encodeURIComponent(id)}?contextCode=${encodeURIComponent(contextCode)}`,
        token
      );
      return toLoadPlanDetail(plan);
    },
    getMapping: async (token, id, contextCode) => {
      const mapping = await request<WireMappingDetail>(
        `/mappings/${encodeURIComponent(id)}?contextCode=${encodeURIComponent(contextCode)}`,
        token
      );
      return toMappingDetail(mapping);
    },
    endSession: (token) =>
      request<void>('/sessions/current', token, { method: 'DELETE' }),
  };
}

export function createDemoApiClient(): ApiClient {
  let activeToken: string | undefined;

  const assertSession = (token: string) => {
    if (!activeToken || token !== activeToken) {
      throw new Error('Sesja demonstracyjna wygasła. Uruchom ją ponownie.');
    }
  };

  return {
    async createSession(_credentials: SessionCredentials) {
      throw new Error(
        'Realne połączenie wymaga uruchomienia interfejsu w trybie VITE_API_MODE=http.'
      );
    },
    async createDemoSession() {
      activeToken = 'demo-session-memory-only';
      return {
        token: activeToken,
        repository: {
          name: 'ODI_DEMO',
          masterRepository: 'ODI_DEMO_MASTER',
          workRepository: 'ODI_DEMO_WORK',
        },
        expiresAt: new Date(Date.now() + 60 * 60 * 1000).toISOString(),
        mode: 'DEMO',
      };
    },
    async getContexts(token) {
      assertSession(token);
      return demoContexts;
    },
    async getLoadPlans(token) {
      assertSession(token);
      return demoLoadPlans;
    },
    async getLoadPlan(token, id, contextCode) {
      assertSession(token);
      return getDemoLoadPlan(id, contextCode);
    },
    async getMapping(token, id, contextCode) {
      assertSession(token);
      return getDemoMapping(id, contextCode);
    },
    async endSession(token) {
      assertSession(token);
      activeToken = undefined;
    },
  };
}

export function createConfiguredApiClient(mode: string | undefined = import.meta.env.VITE_API_MODE): ApiClient {
  return mode === 'http' ? createHttpApiClient() : createDemoApiClient();
}

export function asContextCode(value: string): ContextCode {
  if (value.trim()) {
    return value;
  }
  throw new Error(`Nieobsługiwany Context: ${value}`);
}

interface WireLoadPlanSummary {
  id: string;
  name: string;
  description: string;
  scenarioCount: number;
  mappingCount: number;
}

type WireSessionInfo = Omit<SessionInfo, 'mode'>;

interface WireLoadPlanStep {
  id: string;
  parentStepId?: string;
  name: string;
  stepType: LoadPlanStepType;
  path: string[];
  declaredContextCode?: string;
  scenarioName?: string;
  scenarioVersion?: string;
  mappingId?: string;
  mappingName?: string;
  resolution?: ScenarioResolution;
  resolutionReason?: string;
  enabled: boolean;
}

interface WireLoadPlanDetail {
  id: string;
  name: string;
  contextCode: string;
  steps: WireLoadPlanStep[];
}

interface WirePhysicalLocation {
  physicalSchema?: string;
  dataServer: string;
  catalog: string;
  schema: string;
}

interface WireMappingComponent {
  id: string;
  componentType: string;
  componentAlias: string;
  datastoreName?: string;
  resourceName?: string;
  modelName?: string;
  logicalSchema?: string;
  columns?: Array<{ id: string; name: string }>;
  physicalLocation?: WirePhysicalLocation;
  resolutionReason?: string;
}

interface WireMappingDetail {
  id: string;
  name: string;
  contextCode: string;
  components: WireMappingComponent[];
  edges: Array<{ fromComponentId: string; toComponentId: string }>;
  columnLineage?: Array<{
    fromComponentId: string;
    fromColumnId: string;
    toComponentId: string;
    toColumnId: string;
  }>;
  warnings?: string[];
}

function toLoadPlanSummary(plan: WireLoadPlanSummary): LoadPlanSummary {
  return plan;
}

function toLoadPlanDetail(plan: WireLoadPlanDetail): LoadPlanDetail {
  const selectableSteps = plan.steps.filter(isMappingListStep);
  const mappingCount = selectableSteps.filter((step) => step.mappingId).length;
  return {
    id: plan.id,
    name: plan.name,
    scenarioCount: plan.steps.filter((step) => step.stepType === 'RUN_SCENARIO').length,
    mappingCount,
    unresolvedCount: plan.steps.filter(
      (step) => step.resolution === 'STALE' || step.resolution === 'UNRESOLVED'
    ).length,
    contextCode: plan.contextCode,
    steps: plan.steps,
    mappings: selectableSteps.map((step) => ({
      stepId: step.id,
      scenarioName: step.scenarioName ?? step.name,
      scenarioVersion: step.scenarioVersion ?? '—',
      mappingId: step.mappingId,
      mappingName: step.mappingName,
      enabled: step.enabled,
      resolution: step.resolution ?? 'UNRESOLVED',
      resolutionReason: step.resolutionReason,
      stepName: step.name,
      stepPath: step.path,
      stepType: step.stepType,
      declaredContextCode: step.declaredContextCode,
    })),
  };
}

function isMappingListStep(step: WireLoadPlanStep): boolean {
  const isPackageMapping = step.stepType === 'PACKAGE_MAPPING';
  const isScenarioResult =
    step.stepType === 'RUN_SCENARIO' &&
    (step.mappingId !== undefined || step.resolution !== 'RESOLVED');
  return isPackageMapping || isScenarioResult;
}

function asNodeKind(componentType: string): MappingNodeKind | undefined {
  switch (componentType) {
    case 'DATASTORE_SOURCE':
    case 'DATASTORE_TARGET':
      return componentType;
    default:
      return undefined;
  }
}

function toMappingDetail(mapping: WireMappingDetail): MappingDetail {
  const nodes: MappingNode[] = mapping.components.flatMap((component) => {
    const kind = asNodeKind(component.componentType);
    if (!kind) return [];

    return [{
      id: component.id,
      label: component.resourceName ?? component.componentAlias,
      kind,
      rawComponentType: component.componentType,
      columns: component.columns ?? [],
      metadata: component.datastoreName && component.resourceName && component.logicalSchema
        ? {
            alias: component.componentAlias,
            datastoreName: component.datastoreName,
            resourceName: component.resourceName,
            modelName: component.modelName ?? '—',
            logicalSchema: component.logicalSchema,
            physicalSchema: component.physicalLocation?.physicalSchema,
            dataServer: component.physicalLocation?.dataServer,
            catalog: component.physicalLocation?.catalog,
            schema: component.physicalLocation?.schema,
            isPhysicalLocationResolved: component.physicalLocation !== undefined,
            resolutionReason: component.resolutionReason,
          }
        : undefined,
    }];
  });
  const visibleNodeIds = new Set(nodes.map(({ id }) => id));
  const visibleColumnIds = new Map(
    nodes.map((node) => [node.id, new Set(node.columns.map(({ id }) => id))])
  );
  const edges = mapping.edges
    .filter(
      (edge) => visibleNodeIds.has(edge.fromComponentId) && visibleNodeIds.has(edge.toComponentId)
    )
    .map((edge, index) => ({
      id: `edge-${index + 1}`,
      from: edge.fromComponentId,
      to: edge.toComponentId,
    }));
  const columnLineage = (mapping.columnLineage ?? [])
    .filter(
      (edge) =>
        visibleColumnIds.get(edge.fromComponentId)?.has(edge.fromColumnId) &&
        visibleColumnIds.get(edge.toComponentId)?.has(edge.toColumnId)
    )
    .map((edge, index) => ({ id: `column-edge-${index + 1}`, ...edge }));

  return {
    id: mapping.id,
    name: mapping.name,
    contextCode: mapping.contextCode,
    nodes,
    edges,
    columnLineage,
    warnings: mapping.warnings ?? [],
  };
}
