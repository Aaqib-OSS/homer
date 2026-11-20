'use client';

import { useEffect, type RefObject } from 'react';

// The nearest scrollable ancestor (the dialog's overflow-auto pane), or null.
function scrollContainerOf(element: HTMLElement): HTMLElement | null {
  let container = element.parentElement;
  while (container) {
    const oy = getComputedStyle(container).overflowY;
    if (oy === 'auto' || oy === 'scroll') break;
    container = container.parentElement;
  }
  return container;
}

/**
 * While `enabled`, scrolls `sentinelRef` into view on every change to `deps` when
 * its scroll container is within `threshold` px of the bottom (always, if none).
 */
export function useAutoScroll(
  sentinelRef: RefObject<HTMLElement | null>,
  enabled: boolean,
  deps: unknown[],
  threshold = 120
): void {
  useEffect(() => {
    if (!enabled) return;
    const sentinel = sentinelRef.current;
    if (!sentinel) return;
    const container = scrollContainerOf(sentinel);
    // No scroll container (e.g. jsdom): scroll unconditionally.
    if (!container) {
      sentinel.scrollIntoView?.({ block: 'end' });
      return;
    }
    const distanceFromBottom =
      container.scrollHeight - container.scrollTop - container.clientHeight;
    if (distanceFromBottom < threshold) {
      sentinel.scrollIntoView?.({ block: 'end' });
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps -- caller supplies the dependency list explicitly
  }, [enabled, ...deps]);
}
