'use client';

import { useState } from 'react';
import { AlertTriangle, X } from 'lucide-react';
import { useAgentLoadErrors } from '@/lib/api/queries';
import type { AgentLoadErrorsResponse } from '@/types/homer';

/**
 * The panel as the Agents page uses it: it reads the last scan's errors itself, so the page needs
 * no load-error state, and it renders nothing for a non-admin, who never asks the server either.
 * The dismissal is remembered per scan, so a reload ([scanToken] changes) shows the errors again.
 */
export function AgentLoadErrorsSection({ isAdmin, scanToken }: { isAdmin: boolean; scanToken: number }) {
  const [dismissedScan, setDismissedScan] = useState<number | null>(null);
  const { data } = useAgentLoadErrors(isAdmin);

  if (!isAdmin || !data || dismissedScan === scanToken) return null;
  return <AgentLoadErrorsPanel loadErrors={data} onDismiss={() => setDismissedScan(scanToken)} />;
}

/**
 * The last agent scan's load errors, admin-only. Each message is rendered as plain text — the server
 * sends log text, so it is never interpreted as markup — and names a variable or a directory, never
 * a value (ADR-0047 rule 7).
 */
export function AgentLoadErrorsPanel({
  loadErrors,
  onDismiss,
}: {
  loadErrors: AgentLoadErrorsResponse;
  onDismiss: () => void;
}) {
  if (loadErrors.errors.length === 0) return null;
  const noun = loadErrors.errors.length === 1 ? 'agent' : 'agents';
  return (
    <div className="rounded-lg border border-destructive bg-destructive/10 p-4 text-sm space-y-2">
      <div className="flex items-start justify-between gap-2">
        <p className="flex items-center gap-2 font-medium text-destructive">
          <AlertTriangle className="h-4 w-4 shrink-0" aria-hidden="true" />
          {loadErrors.errors.length} {noun} failed to load
        </p>
        <button
          onClick={onDismiss}
          aria-label="Dismiss agent load errors"
          className="shrink-0 text-destructive hover:opacity-70"
        >
          <X className="h-4 w-4" />
        </button>
      </div>
      <div className="space-y-1">
        {loadErrors.errors.map((message, i) => (
          <p key={i} className="break-words text-destructive">
            {message}
          </p>
        ))}
      </div>
      {loadErrors.truncated > 0 && (
        <p className="text-muted-foreground">+{loadErrors.truncated} more not shown</p>
      )}
    </div>
  );
}
