// @vitest-environment node
import { describe, it, expect, vi } from 'vitest';
import { renderToString } from 'react-dom/server';
import type { AgentInfo } from '@/types/homer';

// A module-scope `window` read in any file of the edit route's tree must fail
// here: the component-level ssr test only ever evaluates the editor itself.
vi.mock('next/navigation', () => ({
  useRouter: () => ({ push: vi.fn() }),
}));

const hooks = vi.hoisted(() => ({
  useAgent: vi.fn(),
  useAgentFiles: vi.fn(),
  useAgentFile: vi.fn(),
  useSaveAgentFile: vi.fn(),
  useDeleteAgentFile: vi.fn(),
  useDebugCommand: vi.fn(),
}));
vi.mock('@/lib/api/queries', () => hooks);

vi.mock('@/lib/auth/use-can', () => ({
  useCan: () => ({
    isAdmin: false,
    can: () => true,
    canSeeProcess: () => false,
    canKillProcess: () => false,
    canRetryProcess: () => false,
  }),
}));

import AgentEditPage from './page';

const writableAgent: AgentInfo = {
  name: 'demo',
  description: null,
  inputs: null,
  queryParams: null,
  scriptCount: 1,
  cron: null,
  writable: true,
};

// Pre-attaches the {status, value} fields React's `use()` reads synchronously, like Next.js's params.
function fulfilledParams(name: string): Promise<{ name: string }> {
  const p = Promise.resolve({ name }) as Promise<{ name: string }> & {
    status?: string;
    value?: { name: string };
  };
  p.status = 'fulfilled';
  p.value = { name };
  return p;
}

describe('AgentEditPage server render', () => {
  it('renders the edit route without touching window', () => {
    hooks.useAgent.mockReturnValue({ data: writableAgent, isLoading: false });
    hooks.useAgentFiles.mockReturnValue({
      data: { entries: [] },
      isLoading: false,
    });
    hooks.useAgentFile.mockReturnValue({ data: undefined });
    hooks.useSaveAgentFile.mockReturnValue({
      mutateAsync: vi.fn(),
      isPending: false,
    });
    hooks.useDeleteAgentFile.mockReturnValue({ mutateAsync: vi.fn() });
    hooks.useDebugCommand.mockReturnValue({ mutateAsync: vi.fn() });

    const html = renderToString(
      <AgentEditPage params={fulfilledParams('demo')} />,
    );

    expect(html).toContain('Select a file to edit');
    expect(html).toContain('Editing');
  });
});
