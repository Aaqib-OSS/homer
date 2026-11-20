/** Final segment of an artifact path: `/tmp/homer/ws/artifacts/cmd_0.out` and `cmd_0.out` both give `cmd_0.out`. */
export function extractFilename(path: string): string {
  const parts = path.split('/');
  return parts[parts.length - 1] || 'artifact';
}

/**
 * Converts an artifact path to the backend's `path` query form (relative to `artifactsDir`):
 * absolute paths shrink to their final segment, relative ones (`subdir/foo.log`) stay as given.
 */
export function toArtifactApiPath(path: string): string {
  return path.startsWith('/') ? extractFilename(path) : path;
}

/** Downloads `blob` in the browser as `filename`. */
export function triggerDownload(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = filename;
  document.body.appendChild(link);
  link.click();
  document.body.removeChild(link);
  URL.revokeObjectURL(url); // Clean up memory
}
