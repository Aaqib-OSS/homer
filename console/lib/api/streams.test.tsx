import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, act, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

// Capture the handlers passed to openStream so the test can drive
// log.chunk / log.end events synchronously.
const { openStreamMock } = vi.hoisted(() => ({
  openStreamMock: vi.fn(),
}));

vi.mock('./sse', () => ({
  openStream: (...args: unknown[]) => openStreamMock(...args),
}));

import { useAgentReloadStream, useProcessLogStream, useProcessStatusStream, type LogStreamState } from './streams';
import { queryKeys } from './queries';

describe('useProcessStatusStream', () => {
  // Animation frames run only when a test calls nextFrame, so a test decides which
  // events share a frame.
  const frames = new Map<number, FrameRequestCallback>();
  let lastFrameId = 0;
  const nextFrame = () =>
    act(() => {
      const due = [...frames.values()];
      frames.clear();
      due.forEach((cb) => cb(0));
    });
  // The callbacks scheduled but not yet run — what a browser would call on the next
  // tick, whether or not the component is still mounted.
  const pendingFrames = () => [...frames.values()];
  // The ids of those frames, so a test can name the one a cleanup must cancel.
  const pendingFrameIds = () => [...frames.keys()];
  const cancelAnimationFrameMock = vi.fn((id: number) => frames.delete(id));

  beforeEach(() => {
    openStreamMock.mockReset();
    cancelAnimationFrameMock.mockClear();
    frames.clear();
    vi.stubGlobal('requestAnimationFrame', (cb: FrameRequestCallback) => {
      frames.set(++lastFrameId, cb);
      return lastFrameId;
    });
    vi.stubGlobal('cancelAnimationFrame', cancelAnimationFrameMock);
  });
  afterEach(() => vi.unstubAllGlobals());

  function StatusHarness() {
    useProcessStatusStream();
    return null;
  }

  it('invalidates the process artifact content when its status changes', () => {
    let captured: { onEvent: (n: string, d: unknown) => void } | undefined;
    openStreamMock.mockImplementation((_p: string, h: { onEvent: (n: string, d: unknown) => void }) => {
      captured = h;
      return () => {};
    });
    const qc = new QueryClient();
    const invalidate = vi.spyOn(qc, 'invalidateQueries');
    render(
      <QueryClientProvider client={qc}>
        <StatusHarness />
      </QueryClientProvider>
    );
    if (!captured) throw new Error('openStream not called');

    act(() => captured!.onEvent('process.status', { processId: 7 }));
    nextFrame();

    // The artifact snapshot for the finished process must be invalidated so a
    // dialog showing it leaves "streaming" mode (clears the stale Live badge).
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['artifact-content', 7] });
  });

  function mountWithSpy() {
    let captured: { onEvent: (n: string, d: unknown) => void } | undefined;
    const close = vi.fn();
    openStreamMock.mockImplementation((_p: string, h: { onEvent: (n: string, d: unknown) => void }) => {
      captured = h;
      return close;
    });
    const qc = new QueryClient();
    const invalidate = vi.spyOn(qc, 'invalidateQueries');
    const { unmount } = render(
      <QueryClientProvider client={qc}>
        <StatusHarness />
      </QueryClientProvider>
    );
    if (!captured) throw new Error('openStream not called');
    const keys = () => invalidate.mock.calls.map((c) => c[0]?.queryKey);
    return {
      emit: (name: string, data: unknown) => act(() => captured!.onEvent(name, data)),
      keys,
      count: (key: readonly unknown[]) => keys().filter((k) => JSON.stringify(k) === JSON.stringify(key)).length,
      unmount,
      close,
    };
  }

  it('coalesces process events within one frame into one invalidation per query', () => {
    const { emit, keys, count } = mountWithSpy();

    emit('process.status', { processId: 7, status: 'WORKING' });
    emit('process.cmd', { processId: 7 });
    emit('process.status', { processId: 8, status: 'WORKING' });
    // The burst is collected, not invalidated event by event.
    expect(keys()).toEqual([]);

    nextFrame();

    expect(count(['processes'])).toBe(1);
    expect(count(['process', 7])).toBe(1);
    expect(count(['process', 8])).toBe(1);
    expect(count(['artifact-content', 7])).toBe(1);
    expect(count(['artifact-content', 8])).toBe(1);
    expect(count(['flow-summary'])).toBe(1);
  });

  it('invalidates once per frame for events in separate frames, so the last event is never dropped', () => {
    const { emit, count } = mountWithSpy();

    emit('process.status', { processId: 7, status: 'WORKING' });
    nextFrame();
    emit('process.status', { processId: 7, status: 'FINISHED' });
    nextFrame();

    expect(count(['processes'])).toBe(2);
    expect(count(['process', 7])).toBe(2);
    expect(count(['artifact-content', 7])).toBe(2);
  });

  it('cancels a pending flush on unmount', () => {
    const { emit, keys, unmount } = mountWithSpy();

    emit('process.status', { processId: 7, status: 'WORKING' });
    unmount();
    nextFrame();

    expect(keys()).toEqual([]);
    expect(frames.size).toBe(0);
  });

  it('cancels the pending frame and closes the stream on unmount', () => {
    const { emit, unmount, close } = mountWithSpy();

    emit('process.status', { processId: 7, status: 'WORKING' });
    const [pendingId] = pendingFrameIds();

    unmount();

    // The flush the burst scheduled is cancelled by its own id, and the SSE
    // subscription is torn down — a hidden tab would otherwise keep a frame
    // callback alive against a closed stream.
    expect(cancelAnimationFrameMock).toHaveBeenCalledWith(pendingId);
    expect(frames.size).toBe(0);
    expect(close).toHaveBeenCalledTimes(1);
  });

  it('question events invalidate question + process views', () => {
    const { emit, keys } = mountWithSpy();

    emit('question.asked', { processId: 7, questionId: 'q-1', agentName: 'a' });

    expect(keys()).toContainEqual(['questions']);
    expect(keys()).toContainEqual(['processes']);
    expect(keys()).toContainEqual(['process', 7]);
  });

  it('question.expired invalidates question + process views', () => {
    const { emit, keys } = mountWithSpy();

    emit('question.expired', { processId: 7, questionId: 'q-1' });

    expect(keys()).toContainEqual(['questions']);
    expect(keys()).toContainEqual(['processes']);
    expect(keys()).toContainEqual(['process', 7]);
  });

  it.each(['message.queued', 'message.delivered', 'message.undelivered'])(
    '%s invalidates only that run\'s messages',
    (name) => {
      const { emit, keys } = mountWithSpy();

      emit(name, { processId: 7, messageId: 'm-1' });

      expect(keys()).toEqual([['run-messages', 7]]);
    },
  );

  it('a process.status event leaves question views alone (expiry has its own event)', () => {
    const { emit, keys } = mountWithSpy();

    emit('process.status', { processId: 7, status: 'KILLED' });
    nextFrame();

    expect(keys()).toContainEqual(['processes']);
    expect(keys()).not.toContainEqual(['questions']);
  });

  it('a process event refetches only the flow summaries it is about', () => {
    let captured: { onEvent: (n: string, d: unknown) => void } | undefined;
    openStreamMock.mockImplementation((_p: string, h: { onEvent: (n: string, d: unknown) => void }) => {
      captured = h;
      return () => {};
    });
    const qc = new QueryClient();
    const flow = (id: number) => ({ processes: [{ id }], total: 1, offset: 0, limit: 100 });
    qc.setQueryData(['flow-summary', 1], flow(11));
    qc.setQueryData(['flow-summary', 2], flow(21));
    qc.setQueryData(['flow-summary', 3], { processes: [], total: 0, offset: 0, limit: 100 });
    render(
      <QueryClientProvider client={qc}>
        <StatusHarness />
      </QueryClientProvider>
    );
    const invalidated = (root: number) => qc.getQueryState(['flow-summary', root])?.isInvalidated;

    // A run the summary already lists.
    act(() => captured!.onEvent('process.cmd', { processId: 21 }));
    nextFrame();
    expect([invalidated(1), invalidated(2), invalidated(3)]).toEqual([false, true, false]);

    // The root itself, whose summary lists only its descendants.
    act(() => captured!.onEvent('question.asked', { processId: 3, questionId: 'q-1', agentName: 'a' }));
    expect([invalidated(1), invalidated(3)]).toEqual([false, true]);
  });

  it('leaves a flow summary that holds no data yet alone (the predicate reads it optionally)', () => {
    let captured: { onEvent: (n: string, d: unknown) => void } | undefined;
    openStreamMock.mockImplementation((_p: string, h: { onEvent: (n: string, d: unknown) => void }) => {
      captured = h;
      return () => {};
    });
    const qc = new QueryClient();
    // A summary the list mounted but whose first fetch has not resolved: it is in
    // the cache with no data, so the predicate must not dereference it.
    qc.getQueryCache().build(qc, { queryKey: queryKeys.flowSummary(9) });
    render(
      <QueryClientProvider client={qc}>
        <StatusHarness />
      </QueryClientProvider>
    );
    if (!captured) throw new Error('openStream not called');

    act(() => captured!.onEvent('process.cmd', { processId: 11 }));
    nextFrame();

    expect(qc.getQueryState(queryKeys.flowSummary(9))?.isInvalidated).toBe(false);
  });

  it('flushes nothing when the scheduled frame runs after unmount', () => {
    const { emit, keys, unmount } = mountWithSpy();

    emit('process.status', { processId: 7, status: 'WORKING' });
    // The cleanup cancels the frame, but the browser may still hand the callback over.
    const scheduled = pendingFrames();
    expect(scheduled).toHaveLength(1);
    unmount();
    act(() => scheduled.forEach((cb) => cb(0)));

    expect(keys()).toEqual([]);
  });

  it('does not replay the previous frame\'s processes when a frame holds none', () => {
    const { emit, count } = mountWithSpy();

    emit('process.status', { processId: 1, status: 'WORKING' });
    nextFrame();
    expect(count(queryKeys.process(1))).toBe(1);

    // An event with no numeric processId still flushes — the list is refreshed —
    // but the ids the previous frame collected are spent, so nothing is refetched
    // a second time for them.
    emit('process.status', { status: 'WORKING' });
    nextFrame();

    expect(count(queryKeys.allProcesses())).toBe(2);
    expect(count(queryKeys.process(1))).toBe(1);
    expect(count(queryKeys.artifactContentByProcess(1))).toBe(1);
  });

  it('a frame with no numeric process id refreshes only the list, not the flow summaries', () => {
    const { emit, keys } = mountWithSpy();

    emit('process.status', { status: 'WORKING' });
    nextFrame();

    // Nothing per-process to invalidate, so the flush stops before it would ask
    // any flow summary — the summaries ride their own poll instead.
    expect(keys()).toEqual([queryKeys.allProcesses()]);
  });

  it('a null payload (data: null) is taken without throwing by every frame class', () => {
    const { emit, keys, count } = mountWithSpy();

    // sse.ts delivers `null` whenever a frame's `data:` is the literal `null`
    // (JSON.parse succeeds), so the `?.` guards must hold for null too, not
    // just for a frame that merely omits the field.
    emit('process.status', null);
    nextFrame();
    // The blanket flush still runs, but no process id rides it — the list only.
    expect(count(queryKeys.allProcesses())).toBe(1);
    expect(keys()).toEqual([queryKeys.allProcesses()]);

    emit('message.queued', null);
    // No processId → no run-messages query to name.
    expect(keys()).toEqual([queryKeys.allProcesses()]);

    emit('question.asked', null);
    // The question and list invalidations are unconditional; nothing
    // per-process rides along.
    expect(keys()).toEqual([queryKeys.allProcesses(), queryKeys.allQuestions(), queryKeys.allProcesses()]);
    expect(count(queryKeys.process(7))).toBe(0);
    expect(count(queryKeys.allFlowSummaries())).toBe(0);
  });
});

interface CapturedHandlers {
  onEvent: (name: string, data: unknown) => void;
}

function Harness({
  enabled,
  onState,
}: {
  enabled: boolean;
  onState: (s: LogStreamState) => void;
}) {
  const s = useProcessLogStream(1, 0, 'out', enabled);
  onState(s);
  return null;
}

describe('useProcessLogStream', () => {
  beforeEach(() => {
    openStreamMock.mockReset();
  });

  function mountAndCapture() {
    let lastState: LogStreamState = { content: '', offset: 0, ended: false };
    let captured: CapturedHandlers | undefined;
    openStreamMock.mockImplementation((_path: string, handlers: CapturedHandlers) => {
      captured = handlers;
      return () => {};
    });
    render(<Harness enabled onState={(s) => { lastState = s; }} />);
    if (!captured) throw new Error('openStream was not called');
    return {
      emit: (name: string, data: unknown) => act(() => captured!.onEvent(name, data)),
      read: () => lastState,
    };
  }

  it('appends each chunk to the buffer, in order', async () => {
    const { emit, read } = mountAndCapture();
    emit('log.chunk', { content: 'hello' });
    emit('log.chunk', { content: ' world' });
    await waitFor(() => expect(read().content).toBe('hello world'));
    expect(read().offset).toBe(11);
  });

  it('advances the offset by UTF-8 bytes, not UTF-16 units', async () => {
    const { emit, read } = mountAndCapture();
    emit('log.chunk', { content: 'café' });
    emit('log.chunk', { content: ' 🚀' });
    await waitFor(() => expect(read().content).toBe('café 🚀'));
    expect(read().offset).toBe(5 + 5);
  });

  it('appends nothing for a chunk without content', async () => {
    const { emit, read } = mountAndCapture();
    emit('log.chunk', { content: 'a' });
    emit('log.chunk', {});
    await waitFor(() => expect(read().offset).toBe(1));
    expect(read().content).toBe('a');
  });

  it('marks ended on log.end', async () => {
    const { emit, read } = mountAndCapture();
    expect(read().ended).toBe(false);
    emit('log.end', {});
    await waitFor(() => expect(read().ended).toBe(true));
  });
});

describe('useAgentReloadStream', () => {
  beforeEach(() => {
    openStreamMock.mockReset();
  });

  function ReloadHarness() {
    useAgentReloadStream();
    return null;
  }

  function mountWithSpy() {
    let captured: CapturedHandlers | undefined;
    openStreamMock.mockImplementation((_path: string, handlers: CapturedHandlers) => {
      captured = handlers;
      return () => {};
    });
    const qc = new QueryClient();
    const invalidate = vi.spyOn(qc, 'invalidateQueries');
    render(
      <QueryClientProvider client={qc}>
        <ReloadHarness />
      </QueryClientProvider>
    );
    if (!captured) throw new Error('openStream not called');
    return {
      emit: (name: string, data: unknown) => act(() => captured!.onEvent(name, data)),
      keys: () => invalidate.mock.calls.map((c) => c[0]?.queryKey),
    };
  }

  it('the agent.reload frame invalidates the agents queries, whatever the diff holds', () => {
    const { emit, keys } = mountWithSpy();

    emit('agent.reload', { added: ['a'], updated: [], removed: ['b'] });
    // An empty diff is still a completed reload (file edits reload inline), so
    // the frame itself is the signal, not its contents.
    emit('agent.reload', { added: [], updated: [], removed: [] });
    // A payload-less frame arrives as the raw empty string (sse.ts falls back
    // when JSON.parse throws) and invalidates too — the frame name is the signal.
    emit('agent.reload', '');

    expect(keys()).toEqual([queryKeys.agents(), queryKeys.agents(), queryKeys.agents()]);
  });

  it('a frame that is not a reload invalidates nothing', () => {
    const { emit, keys } = mountWithSpy();

    emit('process.status', { processId: 7, status: 'WORKING' });

    expect(keys()).toEqual([]);
  });
});
