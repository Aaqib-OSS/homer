import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

const getAuthMe = vi.fn();
const getHeartbeat = vi.fn();
const loadPluginsMock = vi.hoisted(() => vi.fn());

const { HomerApiError } = vi.hoisted(() => {
  class HomerApiError extends Error {
    status?: number;
    constructor(message: string, status?: number) {
      super(message);
      this.status = status;
      this.name = 'HomerApiError';
    }
  }
  return { HomerApiError };
});

vi.mock('@/lib/api/client', () => ({
  homerApi: {
    getAuthMe: () => getAuthMe(),
    getHeartbeat: () => getHeartbeat(),
  },
  HomerApiError,
}));

// Bearer mode with one admin-gated and one open plugin; the mode is read at module load.
vi.mock('@/lib/config/app-config', () => ({
  appConfig: {
    auth: { enabled: true, mode: 'bearer', tokenKey: 'homer_auth_token' },
    plugins: { entries: ['/plugins/ops.js@admin', '/plugins/open.js'] },
  },
}));

vi.mock('@/lib/plugins/loader', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/lib/plugins/loader')>()),
  loadPlugins: loadPluginsMock,
}));

vi.mock('next/navigation', () => ({ usePathname: () => '/processes' }));
vi.mock('@/lib/api/queries', () => ({
  useOpenQuestionsCount: () => ({ data: 0 }),
  usePendingContinuationsCount: () => ({ data: 0 }),
}));

import { AuthProvider, useAuth } from './auth-context';
import { PluginsProvider } from '@/lib/plugins/plugins-context';
import { Sidebar } from '@/components/layout/sidebar';
import { resetInstancesStoreForTests } from '@/lib/instances/store';

const TOKEN_KEY = 'homer_auth_token::default';

/** Mounts the plugins and the sidebar once the auth probe settled, as `DashboardLayout` does. */
function Dashboard() {
  const { status } = useAuth();
  if (status !== 'authed') return <div data-testid="status">{status}</div>;
  return (
    <PluginsProvider>
      <Sidebar />
    </PluginsProvider>
  );
}

function renderDashboard() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <AuthProvider>
        <Dashboard />
      </AuthProvider>
    </QueryClientProvider>,
  );
}

/** Paths the plugin loader was asked to import. */
async function importedPaths(): Promise<string[]> {
  await waitFor(() => expect(loadPluginsMock).toHaveBeenCalled());
  return loadPluginsMock.mock.calls[0][0].map((e: { path: string }) => e.path);
}

describe('bearer-mode capabilities', () => {
  beforeEach(() => {
    getAuthMe.mockReset();
    getHeartbeat.mockReset();
    loadPluginsMock.mockReset();
    loadPluginsMock.mockResolvedValue([]);
    resetInstancesStoreForTests();
    localStorage.setItem(TOKEN_KEY, 'stored-token');
  });

  it('a non-admin token sees no admin nav and never imports an @admin plugin', async () => {
    getAuthMe.mockResolvedValue({ username: 'ci', role: null, grants: [{ agents: ['echo'], actions: ['run'] }] });
    renderDashboard();

    expect(await importedPaths()).toEqual(['/plugins/open.js']);
    expect(screen.getByRole('link', { name: /Processes/ })).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: /Costs/ })).toBeNull();
    expect(screen.queryByRole('link', { name: /Schedules/ })).toBeNull();
    expect(screen.queryByRole('link', { name: /Runners/ })).toBeNull();
  });

  it('an admin token sees the admin nav and imports the @admin plugin', async () => {
    getAuthMe.mockResolvedValue({ username: 'ops', role: 'admin', grants: [] });
    renderDashboard();

    expect(await importedPaths()).toEqual(['/plugins/ops.js', '/plugins/open.js']);
    expect(screen.getByRole('link', { name: /Costs/ })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /Schedules/ })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /Runners/ })).toBeInTheDocument();
  });

  it('a server answering /auth/me with 404 gives the token no admin capability', async () => {
    getAuthMe.mockRejectedValue(new HomerApiError('Not Found', 404));
    getHeartbeat.mockResolvedValue(undefined);
    renderDashboard();

    expect(await importedPaths()).toEqual(['/plugins/open.js']);
    expect(screen.queryByRole('link', { name: /Costs/ })).toBeNull();
    expect(screen.queryByRole('link', { name: /Runners/ })).toBeNull();
  });
});
