import { afterEach, describe, expect, it, vi } from 'vitest';
import { waitFor } from '@testing-library/react';
import { openStream } from './sse';

/** An event-stream response that delivers `frames`, then drops the connection. */
function droppedStream(frames: string): Response {
  let delivered = false;
  const body = new ReadableStream<Uint8Array>({
    pull(controller) {
      if (delivered) controller.error(new Error('connection dropped'));
      else controller.enqueue(new TextEncoder().encode(frames));
      delivered = true;
    },
  });
  return new Response(body, { headers: { 'content-type': 'text/event-stream' } });
}

describe('openStream reconnect', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('resumes from the last frame id by sending it as Last-Event-ID', async () => {
    const sent: (string | undefined)[] = [];
    vi.stubGlobal(
      'fetch',
      vi.fn(async (_url: string, init: RequestInit) => {
        sent.push((init.headers as Record<string, string>)['last-event-id']);
        return droppedStream(
          sent.length === 1 ? 'event: log.chunk\nid: 5\ndata: {"content":"hello"}\n\n' : '',
        );
      }),
    );

    const close = openStream('/api/v1/stream/processes/1/logs?cmd=0&stream=out', { onEvent: () => {} });
    try {
      await waitFor(() => expect(sent.length).toBeGreaterThanOrEqual(2), { timeout: 3000 });
    } finally {
      close();
    }

    expect(sent.slice(0, 2)).toEqual([undefined, '5']);
  });
});
