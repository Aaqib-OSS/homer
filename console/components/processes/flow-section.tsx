'use client';

import { useState } from 'react';
import Link from 'next/link';
import { useProcesses } from '@/lib/api/queries';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { StatusBadge } from './status-badge';
import { formatCost } from '@/lib/utils/format';
import { Eye, EyeOff } from 'lucide-react';

/**
 * Runs fetched for one flow. Unlike the roots list this is not paged — a flow
 * is one debugging unit, and the view says so rather than hiding the tail.
 */
export const FLOW_RUNS_LIMIT = 500;

/**
 * The Flow section of a root run's page: every visible run of the flow (by stored root id,
 * ADR-0058) in id order, one flat list since the parent column carries the tree. Resume
 * segments of the root's agent are hidden by default, with a toggle to show them.
 */
export function FlowSection({
  processId,
  rootAgentName,
}: {
  processId: number;
  // The root's own agent name; the segment toggle hides rows matching it.
  rootAgentName: string | null;
}) {
  const [showSegments, setShowSegments] = useState(false);
  const { data } = useProcesses({ rootProcessId: processId, order: 'asc', limit: FLOW_RUNS_LIMIT });

  if (!data) return null;

  const runs = data.processes;
  // Matched by agent name: a resume's parent is the run it replays, possibly a deeper
  // descendant, so `parentProcessId === root` would miss the orchestrator's segments.
  const hasSegments = rootAgentName !== null && runs.some((run) => run.agentName === rootAgentName);
  const visibleRuns = showSegments ? runs : runs.filter((run) => run.agentName !== rootAgentName);

  return (
    <Card>
      <CardHeader className="flex flex-row items-start justify-between gap-3 space-y-0">
        <div className="space-y-1.5">
          <CardTitle>Flow</CardTitle>
          <CardDescription>Every run below this one, oldest first</CardDescription>
        </div>
        {hasSegments && (
          <SegmentToggle shown={showSegments} onToggle={() => setShowSegments((v) => !v)} />
        )}
      </CardHeader>
      <CardContent>
        <ul className="space-y-2" aria-label="Flow runs">
          {visibleRuns.map((run) => (
            <li key={run.id} className="flex flex-wrap items-center gap-3 text-sm">
              <Link
                href={`/processes/${run.id}`}
                className="font-mono underline-offset-2 hover:underline"
                aria-label={`Open flow run ${run.id} details`}
              >
                #{run.id}
              </Link>
              <span className="font-medium">{run.agentName}</span>
              <StatusBadge status={run.status} />
              <span className="font-mono text-muted-foreground">{formatCost(run.costUsd ?? 0)}</span>
              <span className="font-mono text-xs text-muted-foreground">
                parent {run.parentProcessId ?? '—'}
              </span>
            </li>
          ))}
        </ul>
        <p className="mt-3 text-sm text-muted-foreground">
          {data.total} {data.total === 1 ? 'run' : 'runs'} in this flow
          {data.total > runs.length && ` (first ${runs.length} shown)`}
        </p>
      </CardContent>
    </Card>
  );
}

// Shows or hides the root agent's resume segments.
function SegmentToggle({ shown, onToggle }: { shown: boolean; onToggle: () => void }) {
  return (
    <Button variant="outline" size="sm" onClick={onToggle} aria-pressed={shown}>
      {shown ? (
        <EyeOff className="mr-1 h-3 w-3" aria-hidden="true" />
      ) : (
        <Eye className="mr-1 h-3 w-3" aria-hidden="true" />
      )}
      {shown ? 'Hide resume segments' : 'Show resume segments'}
    </Button>
  );
}
