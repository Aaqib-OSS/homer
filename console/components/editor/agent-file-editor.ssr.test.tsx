// @vitest-environment node
import { describe, it, expect, vi } from 'vitest';
import { renderToString } from 'react-dom/server';

// Monaco reads `window` at module scope: a server render that imports it is the
// direct-load 500 this file guards against.
const evaluated = vi.hoisted(() => ({ monaco: false }));
vi.mock('monaco-editor', () => {
  evaluated.monaco = true;
  return { default: {} };
});
vi.mock('@/lib/api/queries', () => ({
  useAgentSchema: () => ({ data: undefined }),
}));

import { AgentFileEditor } from './agent-file-editor';

describe('AgentFileEditor server render', () => {
  it('renders the loading placeholder without touching Monaco', () => {
    const html = renderToString(
      <AgentFileEditor path="agent.yaml" value="name: demo" onChange={vi.fn()} />,
    );

    expect(html).toContain('Loading editor...');
    expect(evaluated.monaco).toBe(false);
  });
});
