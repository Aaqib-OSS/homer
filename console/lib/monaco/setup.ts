'use client';

import { loader } from '@monaco-editor/react';

declare global {
  interface Window {
    MonacoEnvironment?: {
      getWorker: (moduleId: string, label: string) => Worker;
    };
  }
}

// Each worker entry must be a local module: `new URL` over a bare package
// specifier copies that file verbatim, so its own imports 404 in production.
let loading: Promise<void> | null = null;

/**
 * Loads Monaco on the client and points @monaco-editor/react at it: the import
 * happens here, never at module scope, so a server render of the editor page
 * touches no browser global. Concurrent and repeated calls share one load.
 */
export function setupMonaco(): Promise<void> {
  if (typeof window === 'undefined') return Promise.resolve();
  loading ??= configureMonaco();
  return loading;
}

async function configureMonaco(): Promise<void> {
  const monaco = await import('monaco-editor');

  window.MonacoEnvironment = {
    getWorker(_moduleId: string, label: string): Worker {
      switch (label) {
        case 'json':
          return new Worker(new URL('./json.worker.ts', import.meta.url), {
            type: 'module',
          });
        case 'yaml':
          return new Worker(new URL('./yaml.worker.ts', import.meta.url), {
            type: 'module',
          });
        default:
          return new Worker(new URL('./editor.worker.ts', import.meta.url), {
            type: 'module',
          });
      }
    },
  };

  loader.config({ monaco });
}
