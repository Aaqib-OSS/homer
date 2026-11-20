'use client';

import { useEffect, useState } from 'react';

/**
 * The current time in whole seconds, refreshed once per second. It is 0 until
 * the first tick and while `enabled` is falsy, and re-seeds whenever `enabled`
 * changes.
 */
export function useNow(enabled: unknown = true): number {
  const [now, setNow] = useState(0);

  useEffect(() => {
    if (!enabled) return;
    // eslint-disable-next-line react-hooks/set-state-in-effect -- seed current time on mount; Date.now() in render would violate purity
    setNow(Math.floor(Date.now() / 1000));
    const interval = setInterval(() => setNow(Math.floor(Date.now() / 1000)), 1000);
    return () => clearInterval(interval);
  }, [enabled]);

  return now;
}
