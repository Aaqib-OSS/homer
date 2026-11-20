'use client';

import { useEffect } from 'react';

/** The props Next.js passes an App Router error boundary (`error.tsx`, `global-error.tsx`). */
export interface ErrorBoundaryProps {
  error: Error & { digest?: string };
  reset: () => void;
}

/**
 * Logs the caught error once per error and returns the text to show: the
 * message, plus the digest when Next.js attached one. Holds no styling, so
 * `global-error.tsx` can use it without the root layout.
 */
export function useBoundaryError(error: ErrorBoundaryProps['error']): string {
  useEffect(() => {
    console.error(error);
  }, [error]);

  const message = error.message || 'Unknown error';
  return error.digest ? `${message}\n\nDigest: ${error.digest}` : message;
}
