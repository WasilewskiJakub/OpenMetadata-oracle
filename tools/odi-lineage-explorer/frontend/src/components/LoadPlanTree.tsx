import { useId, useMemo, useState } from 'react';

import type { LoadPlanTreeStep } from '../api/types';
import { Icon } from './Icon';

interface LoadPlanTreeProps {
  steps: LoadPlanTreeStep[];
}

interface LoadPlanTreeItemProps {
  childrenByParentId: ReadonlyMap<string | null, LoadPlanTreeStep[]>;
  collapsedStepIds: ReadonlySet<string>;
  step: LoadPlanTreeStep;
  onToggle(stepId: string): void;
}

const ROOT_PARENT_ID = null;
const COLLAPSIBLE_STEP_TYPES = new Set<LoadPlanTreeStep['stepType']>([
  'ROOT_SERIAL',
  'SERIAL',
  'PARALLEL',
  'CASE',
  'WHEN',
  'ELSE',
]);

const stepTypeLabel: Record<LoadPlanTreeStep['stepType'], string> = {
  ROOT_SERIAL: 'Root Step (Serial)',
  SERIAL: 'Serial',
  PARALLEL: 'Parallel',
  CASE: 'Case',
  WHEN: 'When',
  ELSE: 'Else',
  RUN_SCENARIO: 'Run Scenario',
  PACKAGE_MAPPING: 'Package Mapping',
};

function groupStepsByParent(
  steps: LoadPlanTreeStep[]
): ReadonlyMap<string | null, LoadPlanTreeStep[]> {
  const knownStepIds = new Set(steps.map((step) => step.id));
  const childrenByParentId = new Map<string | null, LoadPlanTreeStep[]>();

  for (const step of steps) {
    const parentId =
      step.parentStepId && knownStepIds.has(step.parentStepId)
        ? step.parentStepId
        : ROOT_PARENT_ID;
    const siblings = childrenByParentId.get(parentId);
    if (siblings) {
      siblings.push(step);
    } else {
      childrenByParentId.set(parentId, [step]);
    }
  }

  return childrenByParentId;
}

function LoadPlanTreeItem({
  childrenByParentId,
  collapsedStepIds,
  step,
  onToggle,
}: LoadPlanTreeItemProps) {
  const childrenId = useId();
  const children = childrenByParentId.get(step.id) ?? [];
  const hasChildren = children.length > 0;
  const isCollapsible =
    hasChildren && COLLAPSIBLE_STEP_TYPES.has(step.stepType);
  const isExpanded = isCollapsible && !collapsedStepIds.has(step.id);
  const shouldShowChildren = hasChildren && (!isCollapsible || isExpanded);
  const hasStatuses = Boolean(
    step.declaredContextCode || !step.enabled || step.resolution
  );

  return (
    <li className="load-plan-tree-item">
      <div className="load-plan-tree-row">
        {isCollapsible ? (
          <button
            aria-controls={childrenId}
            aria-expanded={isExpanded}
            aria-label={`${isExpanded ? 'Zwiń' : 'Rozwiń'} ${stepTypeLabel[step.stepType]} ${step.name}`}
            className="tree-toggle"
            type="button"
            onClick={() => onToggle(step.id)}>
            <span className="tree-toggle-icon">
              <Icon name="chevron-down" />
            </span>
          </button>
        ) : (
          <span aria-hidden="true" className="tree-toggle-spacer" />
        )}
        <span className="tree-step-icon">
          <Icon
            name={
              step.stepType === 'RUN_SCENARIO' ||
              step.stepType === 'PACKAGE_MAPPING'
                ? 'flow'
                : 'layers'
            }
          />
        </span>
        <span className="tree-step-copy">
          <small>{stepTypeLabel[step.stepType]}</small>
          <strong>{step.name}</strong>
          {step.scenarioName ? (
            <em>
              {step.scenarioName} · v{step.scenarioVersion ?? '—'}
            </em>
          ) : null}
        </span>
        {hasStatuses ? (
          <span className="tree-step-statuses">
            {!step.enabled ? (
              <span className="execution-state execution-state-disabled">
                Wyłączony w wykonaniu
              </span>
            ) : null}
            {step.declaredContextCode ? (
              <span className="tree-context">
                Context {step.declaredContextCode}
              </span>
            ) : null}
            {step.resolution ? (
              <span
                className={`resolution resolution-${step.resolution.toLocaleLowerCase()}`}>
                {step.resolution.replaceAll('_', ' ')}
              </span>
            ) : null}
          </span>
        ) : null}
      </div>
      {shouldShowChildren ? (
        <ol className="load-plan-tree-children" id={childrenId}>
          {children.map((child) => (
            <LoadPlanTreeItem
              childrenByParentId={childrenByParentId}
              collapsedStepIds={collapsedStepIds}
              key={child.id}
              step={child}
              onToggle={onToggle}
            />
          ))}
        </ol>
      ) : null}
    </li>
  );
}

export function LoadPlanTree({ steps }: LoadPlanTreeProps) {
  const childrenByParentId = useMemo(() => groupStepsByParent(steps), [steps]);
  const [collapsedStepIds, setCollapsedStepIds] = useState<Set<string>>(
    () => new Set()
  );
  const rootSteps = childrenByParentId.get(ROOT_PARENT_ID) ?? [];

  function toggleStep(stepId: string) {
    setCollapsedStepIds((current) => {
      const next = new Set(current);
      if (next.has(stepId)) {
        next.delete(stepId);
      } else {
        next.add(stepId);
      }
      return next;
    });
  }

  return (
    <ol aria-label="Hierarchia kroków Load Planu" className="load-plan-tree">
      {rootSteps.map((step) => (
        <LoadPlanTreeItem
          childrenByParentId={childrenByParentId}
          collapsedStepIds={collapsedStepIds}
          key={step.id}
          step={step}
          onToggle={toggleStep}
        />
      ))}
    </ol>
  );
}
