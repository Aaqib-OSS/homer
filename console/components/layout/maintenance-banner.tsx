'use client';

import { useHealth } from '@/lib/api/queries';

/**
 * The server's maintenance mode, on every dashboard page: while on, the backend skips cron
 * fires and queues new starts. Renders nothing while off or before health has answered.
 * Fixed brand colors, not theme tokens, so the alarm reads the same under every theme.
 */
export function MaintenanceBanner() {
  const { data } = useHealth();
  if (!data?.maintenance) return null;

  return (
    <div
      className="border-b border-red-500 bg-red-500/10 px-4 py-2 text-sm font-medium text-red-600"
      role="alert"
      aria-label="Maintenance mode"
    >
      Maintenance mode is on. Scheduled runs are skipped and new runs wait in a queue
      until an admin turns it off.
    </div>
  );
}
