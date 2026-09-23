import { afterEach, describe, expect, it, vi } from 'vitest';

import { downloadBlob } from './download';

describe('downloadBlob', () => {
  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it('uruchamia pobieranie bezpiecznej nazwy i zawsze zwalnia Object URL', () => {
    const objectUrl = 'blob:odi-lineage-export';
    const createObjectURL = vi.fn().mockReturnValue(objectUrl);
    const revokeObjectURL = vi.fn();
    vi.stubGlobal('URL', { createObjectURL, revokeObjectURL });
    const anchor = document.createElement('a');
    const click = vi.spyOn(anchor, 'click').mockImplementation(() => undefined);
    vi.spyOn(document, 'createElement').mockReturnValueOnce(anchor);
    const blob = new Blob(['{}\n'], { type: 'application/json' });

    downloadBlob({ blob, fileName: '../../ODI export?.json' });

    expect(createObjectURL).toHaveBeenCalledWith(blob);
    expect(anchor.href).toBe(objectUrl);
    expect(anchor.download).toBe('ODI export_.json');
    expect(click).toHaveBeenCalledOnce();
    expect(revokeObjectURL).toHaveBeenCalledWith(objectUrl);
  });

  it('ogranicza nazwę pobieranego pliku bez utraty rozszerzenia JSON', () => {
    vi.stubGlobal('URL', {
      createObjectURL: vi.fn().mockReturnValue('blob:long-export'),
      revokeObjectURL: vi.fn(),
    });
    const anchor = document.createElement('a');
    vi.spyOn(anchor, 'click').mockImplementation(() => undefined);
    vi.spyOn(document, 'createElement').mockReturnValueOnce(anchor);

    const fileName = downloadBlob({
      blob: new Blob(['{}']),
      fileName: `${'a'.repeat(240)}.json`,
    });

    expect(fileName).toHaveLength(180);
    expect(fileName).toMatch(/\.json$/);
    expect(anchor.download).toBe(fileName);
  });
});
