import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';

const config = vi.hoisted(() => vi.fn());
const monacoModule = vi.hoisted(() => ({ value: { default: {}, editor: {} } }));

vi.mock('@monaco-editor/react', () => ({
  loader: { config },
}));

vi.mock('monaco-editor', () => monacoModule.value);

class FakeWorker {
  constructor(
    public url: URL | string,
    public options?: WorkerOptions,
  ) {}
  terminate(): void {}
}

interface FakeEnvironment {
  getWorker: (moduleId: string, label: string) => FakeWorker;
}

function environment(): FakeEnvironment {
  return (window as unknown as { MonacoEnvironment: FakeEnvironment })
    .MonacoEnvironment;
}

describe('setupMonaco', () => {
  beforeEach(() => {
    vi.resetModules();
    config.mockClear();
    // @ts-expect-error — jsdom doesn't ship Worker
    globalThis.Worker = FakeWorker;
    delete (window as unknown as { MonacoEnvironment?: unknown })
      .MonacoEnvironment;
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('imports no Monaco at module scope', async () => {
    vi.doMock('monaco-editor', () => {
      throw new Error('monaco-editor evaluated at import time');
    });

    await expect(import('./setup')).resolves.toBeDefined();

    vi.doMock('monaco-editor', () => monacoModule.value);
  });

  it('installs MonacoEnvironment.getWorker returning dedicated workers per label', async () => {
    const { setupMonaco } = await import('./setup');
    await setupMonaco();

    const env = environment();

    expect(env).toBeDefined();
    expect(env.getWorker('x', 'json')).toBeInstanceOf(FakeWorker);
    expect(env.getWorker('x', 'yaml')).toBeInstanceOf(FakeWorker);
    expect(env.getWorker('x', 'editorWorkerService')).toBeInstanceOf(
      FakeWorker,
    );
    expect(config).toHaveBeenCalledWith({ monaco: monacoModule.value });
  });

  it.each([
    ['json', 'json.worker'],
    ['yaml', 'yaml.worker'],
    ['editorWorkerService', 'editor.worker'],
  ])('builds the %s worker URL from the local %s entry', async (label, entry) => {
    const { setupMonaco } = await import('./setup');
    await setupMonaco();

    const worker = environment().getWorker('x', label);

    const url = worker.url as URL;
    expect(url).toBeInstanceOf(URL);
    // A package-specifier URL also contains the entry's name; only the local
    // entry lives under lib/monaco.
    expect(url.href).toMatch(new RegExp(`/lib/monaco/${entry}`));
    expect(url.href).not.toMatch(/monaco-editor|monaco-yaml/);
    expect(worker.options).toEqual({ type: 'module' });
  });

  it('loads and configures Monaco once for concurrent and repeated calls', async () => {
    const { setupMonaco } = await import('./setup');
    const first = setupMonaco();
    const second = setupMonaco();
    await Promise.all([first, second]);
    await setupMonaco();

    expect(second).toBe(first);
    expect(config).toHaveBeenCalledTimes(1);
  });

  it('keeps the first environment when called again', async () => {
    const { setupMonaco } = await import('./setup');
    await setupMonaco();
    const first = environment();
    await setupMonaco();
    expect(environment()).toBe(first);
  });

  it('clears a rejected load so the next call retries', async () => {
    vi.doMock('monaco-editor', () => {
      throw new Error('chunk load failed');
    });
    const { setupMonaco } = await import('./setup');

    await expect(setupMonaco()).rejects.toThrow();

    // The rejected promise is not cached: a later call loads again.
    vi.doMock('monaco-editor', () => monacoModule.value);
    await expect(setupMonaco()).resolves.toBeUndefined();
    expect(config).toHaveBeenCalledWith({ monaco: monacoModule.value });
  });

  it('does nothing without a window', async () => {
    vi.stubGlobal('window', undefined);
    const { setupMonaco } = await import('./setup');

    await expect(setupMonaco()).resolves.toBeUndefined();

    expect(config).not.toHaveBeenCalled();
  });
});
