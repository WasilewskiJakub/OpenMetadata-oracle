export type ContextCode = string;

export interface RepositoryDescriptor {
  name: string;
  masterRepository: string;
  workRepository: string;
}

export interface SessionInfo {
  token: string;
  repository: RepositoryDescriptor;
  expiresAt: string;
  mode: 'DEMO' | 'REPOSITORY';
}

export interface SessionCredentials {
  jdbcUrl: string;
  repositoryUsername: string;
  repositoryPassword: string;
  workRepositoryName: string;
  odiUsername: string;
  odiPassword: string;
}

export interface OdiContext {
  code: ContextCode;
  name: string;
  isDefault: boolean;
}

export type LoadPlanStatus = 'ENABLED' | 'DISABLED';

export interface LoadPlanSummary {
  id: string;
  name: string;
  description?: string;
  project?: string;
  folder?: string;
  status?: LoadPlanStatus;
  scenarioCount?: number;
  mappingCount?: number;
  unresolvedCount?: number;
  updatedAt?: string;
}

export type ScenarioResolution = 'RESOLVED' | 'STALE' | 'UNRESOLVED' | 'OUT_OF_SCOPE';
export type LoadPlanStepType =
  | 'ROOT_SERIAL'
  | 'SERIAL'
  | 'PARALLEL'
  | 'CASE'
  | 'WHEN'
  | 'ELSE'
  | 'RUN_SCENARIO'
  | 'PACKAGE_MAPPING';

export interface LoadPlanTreeStep {
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

export interface ScenarioMapping {
  stepId: string;
  scenarioName: string;
  scenarioVersion: string;
  mappingId?: string;
  mappingName?: string;
  project?: string;
  folder?: string;
  enabled: boolean;
  resolution: ScenarioResolution;
  resolutionReason?: string;
  stepName?: string;
  stepPath?: string[];
  stepType?: LoadPlanStepType;
  declaredContextCode?: string;
}

export interface LoadPlanDetail extends LoadPlanSummary {
  contextCode: ContextCode;
  mappings: ScenarioMapping[];
  steps: LoadPlanTreeStep[];
}

export type MappingNodeKind = 'DATASTORE_SOURCE' | 'DATASTORE_TARGET';

export interface MappingColumn {
  id: string;
  name: string;
}

export interface PhysicalObjectMetadata {
  alias: string;
  datastoreName: string;
  resourceName: string;
  modelName: string;
  logicalSchema: string;
  physicalSchema?: string;
  dataServer?: string;
  catalog?: string;
  schema?: string;
  isPhysicalLocationResolved: boolean;
  resolutionReason?: string;
}

export interface MappingNode {
  id: string;
  label: string;
  kind: MappingNodeKind;
  rawComponentType: string;
  columns: MappingColumn[];
  metadata?: PhysicalObjectMetadata;
}

export interface MappingEdge {
  id: string;
  from: string;
  to: string;
  label?: string;
}

export interface ColumnLineageEdge {
  id: string;
  fromComponentId: string;
  fromColumnId: string;
  toComponentId: string;
  toColumnId: string;
}

export interface MappingDetail {
  id: string;
  name: string;
  project?: string;
  folder?: string;
  contextCode: ContextCode;
  nodes: MappingNode[];
  edges: MappingEdge[];
  columnLineage: ColumnLineageEdge[];
  warnings: string[];
}

export interface ApiClient {
  createSession(credentials: SessionCredentials): Promise<SessionInfo>;
  createDemoSession(): Promise<SessionInfo>;
  getContexts(token: string): Promise<OdiContext[]>;
  getLoadPlans(token: string): Promise<LoadPlanSummary[]>;
  getLoadPlan(token: string, id: string, contextCode: ContextCode): Promise<LoadPlanDetail>;
  getMapping(token: string, id: string, contextCode: ContextCode): Promise<MappingDetail>;
  endSession(token: string): Promise<void>;
}
