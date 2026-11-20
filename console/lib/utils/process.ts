import type { ProcessInfo, ProcessStatus } from '@/types/homer';
import { splitCommaList } from './list';

/** A run that has not ended yet (`CREATED` or `WORKING`) — the statuses Kill applies to. */
export function isActiveStatus(status: ProcessStatus): boolean {
  return status === 'WORKING' || status === 'CREATED';
}

/** A run that ended normally (`FINISHED`, `FAILED` or `KILLED`) — the statuses Retry applies to. */
export function isRetryableStatus(status: ProcessStatus): boolean {
  return status === 'FINISHED' || status === 'FAILED' || status === 'KILLED';
}

export type LastCommandStatus = 'success' | 'running' | 'failed';

export interface LastCommand {
  status: LastCommandStatus | null;
  label: string | null;
}

/**
 * The Last Command icon's status and label, from the last dot of a row already computed by
 * `commandDotStates`, so the dots row and the icon share one classification.
 */
export function lastCommandFromDots(dots: CommandDot[]): LastCommand {
  const last = dots[dots.length - 1];
  if (!last) {
    return { status: null, label: null };
  }

  return {
    status: last.state === 'ok' ? 'success' : last.state,
    label: last.label,
  };
}

/**
 * When the run started: its first history entry's timestamp, or `null` when history is missing,
 * empty or malformed (agent-written). `runDurationSeconds` measures from the same instant.
 */
export function runStartTimestamp(process: ProcessInfo): number | null {
  const history = process.history ?? [];
  if (history.length === 0) {
    return null;
  }
  const start = history[0].timestamp;
  return Number.isFinite(start) ? start : null;
}

/**
 * Elapsed seconds from the first history entry to the last (ended run) or to `now` (active);
 * `null` when history is missing, malformed or the span is negative.
 * `now === 0` is `useNow`'s pre-tick value: an active run then ends at its last entry.
 */
export function runDurationSeconds(process: ProcessInfo, now: number): number | null {
  const start = runStartTimestamp(process);
  if (start === null) {
    return null;
  }
  // A non-null start means history is non-empty, so the last entry exists.
  const history = process.history ?? [];
  const last = history[history.length - 1].timestamp;
  if (!Number.isFinite(last)) {
    return null;
  }

  const active = isActiveStatus(process.status) && now > 0;
  return elapsedSeconds(start, active ? now : last);
}

/** `end - start` in seconds when the span is finite and not negative, else null. */
function elapsedSeconds(start: number, end: number): number | null {
  const seconds = end - start;
  return Number.isFinite(seconds) && seconds >= 0 ? seconds : null;
}

export type CommandDotState = 'ok' | 'failed' | 'running';

/**
 * One dot of a run's command-progress row: the command's label, how it ended,
 * and how long it took — `null` when the execution carries no usable span.
 */
export interface CommandDot {
  label: string;
  state: CommandDotState;
  durationSeconds: number | null;
}

/**
 * One dot per executed command (`ok` for resultCode 0, else `failed`), plus a `running` dot for
 * the in-flight command while WORKING. Skipped or not-yet-run commands get none. Each duration
 * is `null` for a missing, non-finite or reversed (agent-written) span.
 */
export function commandDotStates(process: ProcessInfo): CommandDot[] {
  const dots: CommandDot[] = (process.executions ?? [])
    .filter((execution) => !execution.skipped)
    .map((execution) => ({
      label: execution.label,
      state: execution.resultCode === 0 ? 'ok' : 'failed',
      durationSeconds: elapsedSeconds(execution.start, execution.end),
    }));

  if (process.status === 'WORKING' && process.currentCommand) {
    dots.push({ label: process.currentCommand, state: 'running', durationSeconds: null });
  }

  return dots;
}

/** The status filters the process list offers, in display order. */
export const PROCESS_STATUS_FILTERS: ProcessStatus[] = ['WORKING', 'FINISHED', 'FAILED', 'KILLED', 'CREATED'];

/** The process list's filters, sort and paging as read from its URL. */
export interface ProcessListParams {
  selectedStatuses: ProcessStatus[];
  selectedAgent: string;
  order: 'asc' | 'desc';
  offset: number;
  parentProcessId: number | undefined;
  rootsView: boolean;
  showRoots: boolean;
  selectedTags: string[];
  /** Any filter (status, agent, parent or tag) is set. */
  hasFilters: boolean;
}

/** `raw` as an integer of at least `min`, or undefined when it is missing, malformed or smaller. */
function intAtLeast(raw: string | null, min: number): number | undefined {
  const parsed = parseInt(raw ?? '', 10);
  return Number.isNaN(parsed) || parsed < min ? undefined : parsed;
}

/** The comma-joined `tag` value as trimmed, non-empty, de-duplicated tags. */
function tagFilters(raw: string | null): string[] {
  return [...new Set(splitCommaList(raw))];
}

function anyFilter(
  selectedStatuses: ProcessStatus[],
  selectedAgent: string,
  parentProcessId: number | undefined,
  selectedTags: string[],
): boolean {
  return (
    selectedStatuses.length > 0 ||
    selectedAgent !== '' ||
    parentProcessId !== undefined ||
    selectedTags.length > 0
  );
}

/** Reads the process list's state from its URL; malformed values fall back to the defaults. */
export function parseProcessListParams(searchParams: Pick<URLSearchParams, 'get'>): ProcessListParams {
  const selectedStatuses = (searchParams
    .get('status')
    ?.split(',')
    .filter((s) => PROCESS_STATUS_FILTERS.includes(s as ProcessStatus)) ?? []) as ProcessStatus[];
  const selectedAgent = searchParams.get('agent') ?? '';
  const order = searchParams.get('order') === 'asc' ? 'asc' : 'desc';
  const offset = intAtLeast(searchParams.get('offset'), 0) ?? 0;
  const parentProcessId = intAtLeast(searchParams.get('parent'), 1);
  // A `parent` filter drops the roots view: children have no flow to summarize (ADR-0058).
  const rootsView = searchParams.get('view') === 'roots';
  const showRoots = rootsView && parentProcessId === undefined;
  // `tag` is one comma-joined value: runs carrying every listed subject tag (ADR-0060).
  const selectedTags = tagFilters(searchParams.get('tag'));
  const hasFilters = anyFilter(selectedStatuses, selectedAgent, parentProcessId, selectedTags);
  return {
    selectedStatuses,
    selectedAgent,
    order,
    offset,
    parentProcessId,
    rootsView,
    showRoots,
    selectedTags,
    hasFilters,
  };
}
