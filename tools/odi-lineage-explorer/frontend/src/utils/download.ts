import type { LineageExportDownload } from '../api/types';

const FALLBACK_FILE_NAME = 'odi-lineage-export.json';
const MAX_FILE_NAME_LENGTH = 180;

function safeJsonFileName(fileName: string): string {
  const baseName = fileName.split(/[\\/]/).pop()?.trim() ?? '';
  const sanitized = baseName
    .replace(/[<>:"|?*\u0000-\u001f]/g, '_')
    .replace(/^\.+/, '');
  if (!sanitized) return FALLBACK_FILE_NAME;
  const extension = '.json';
  const stem = sanitized.toLocaleLowerCase().endsWith(extension)
    ? sanitized.slice(0, -extension.length)
    : sanitized;
  const boundedStem = stem
    .slice(0, MAX_FILE_NAME_LENGTH - extension.length)
    .replace(/[. ]+$/, '');
  return boundedStem ? `${boundedStem}${extension}` : FALLBACK_FILE_NAME;
}

export function downloadBlob({ blob, fileName }: LineageExportDownload): string {
  const objectUrl = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = objectUrl;
  const safeFileName = safeJsonFileName(fileName);
  anchor.download = safeFileName;
  anchor.style.display = 'none';
  document.body.append(anchor);

  try {
    anchor.click();
  } finally {
    anchor.remove();
    URL.revokeObjectURL(objectUrl);
  }
  return safeFileName;
}
