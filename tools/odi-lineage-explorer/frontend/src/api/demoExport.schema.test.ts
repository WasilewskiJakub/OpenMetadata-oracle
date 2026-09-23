import Ajv from 'ajv';
import { describe, expect, it } from 'vitest';

import exportSchema from '../../../docs/odi-lineage-export-v1.schema.json';
import { createDemoApiClient } from './client';

const readBrowserBlob = (blob: Blob) => new Promise<string>((resolve, reject) => {
  const reader = new FileReader();
  reader.addEventListener('load', () => resolve(String(reader.result)));
  reader.addEventListener('error', () => reject(reader.error));
  reader.readAsText(blob);
});

describe('demo lineage export contract', () => {
  it('waliduje cały artefakt względem śledzonego JSON Schema v1', async () => {
    const client = createDemoApiClient();
    const session = await client.createDemoSession();
    const download = await client.exportLoadPlanLineage(
      session.token,
      'lp-daily-sales',
      'DEV',
      ['step-orders', 'step-sales-fact']
    );
    const artifact = JSON.parse(await readBrowserBlob(download.blob)) as unknown;
    const validate = new Ajv({ allErrors: true }).compile(exportSchema);

    expect(validate(artifact), JSON.stringify(validate.errors, null, 2)).toBe(true);
  });
});
