// @vitest-environment node
import fs from 'node:fs';
import path from 'node:path';
import { describe, it, expect, vi } from 'vitest';

// The worker entry installs a browser message handler at import, so capture the
// factory it registers there and call it directly — jsdom has no Worker to run it.
interface Marker {
  message: string;
  range: { start: { line: number } };
}

interface YamlWorker {
  doValidation: (uri: string) => Promise<Marker[] | undefined>;
}

type WorkerFactory = (ctx: unknown, createData: unknown) => YamlWorker;

const factory = vi.hoisted(() => ({
  current: null as WorkerFactory | null,
}));

vi.mock('monaco-worker-manager/worker', () => ({
  initialize: (fn: WorkerFactory) => {
    factory.current = fn;
  },
}));

import '@/lib/monaco/yaml.worker';

const AGENT_SCHEMA_URI = 'https://homer.local/schemas/agent.json';
const AGENT_SCHEMA: object = JSON.parse(
  fs.readFileSync(
    path.join(__dirname, '../../../app/schemas/homer-agent.schema.json'),
    'utf8',
  ),
);

// The options AgentFileEditor passes to configureMonacoYaml, agent schema included.
const OPTIONS = {
  enableSchemaRequest: false,
  validate: true,
  hover: true,
  completion: true,
  schemas: [
    {
      uri: AGENT_SCHEMA_URI,
      fileMatch: ['*agent.yaml', '*agent.yml'],
      schema: AGENT_SCHEMA,
    },
  ],
};

// The language service caches diagnostics per document URI, so each case gets
// its own agent file, the way a real editor session has one model per file.
function validate(agent: string, content: string): Promise<Marker[] | undefined> {
  const uri = `file:///agents/${agent}/agent.yaml`;
  const worker = factory.current!(
    { getMirrorModels: () => [{ uri, version: 1, getValue: () => content }] },
    OPTIONS,
  );
  return worker.doValidation(uri);
}

const VALID = ['name: demo', 'commands:', '  - type: script', '    content: echo hi'];

describe('yaml worker', () => {
  it('reports a schema marker for an agent.yaml that violates the agent schema', async () => {
    const markers = await validate('invalid', [...VALID, 'bogusKey: true'].join('\n'));

    expect(markers).toHaveLength(1);
    expect(markers![0].message).toMatch(/bogusKey/);
    expect(markers![0].range.start.line).toBe(4);
  });

  it('reports no marker for an agent.yaml that satisfies the agent schema', async () => {
    const markers = await validate('valid', VALID.join('\n'));

    expect(markers).toEqual([]);
  });
});
