import { describe, it, expect, vi, beforeEach } from 'vitest';
import { screen, act, waitFor } from '@testing-library/react';

const getAuthMe = vi.fn();
const login = vi.fn();
const logout = vi.fn();
const getHeartbeat = vi.fn();

// Hoisted so the vi.mock factory (also hoisted) can reference it and tests can
// construct instances that `instanceof` matches inside auth-context.
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
    login: (u: string, p: string) => login(u, p),
    logout: () => logout(),
    getHeartbeat: () => getHeartbeat(),
  },
  HomerApiError,
}));

// Own file: `authProbeDisabled` and the mode branch are read at module load from this mock.
vi.mock('@/lib/config/app-config', () => ({
  appConfig: {
    auth: { enabled: true, mode: 'bearer', tokenKey: 'homer_auth_token' },
  },
}));

import { LoginOutcomeProbe, renderWithProvider } from './auth-test-utils';
import { useAuth } from './auth-context';
import { resetInstancesStoreForTests } from '@/lib/instances/store';

/** Shows the internal unreported flag and the keys of the public user. */
function UnreportedProbe() {
  const { user, principalUnreported } = useAuth();
  return (
    <div>
      <div data-testid="unreported">{String(principalUnreported)}</div>
      <div data-testid="user-keys">{user ? Object.keys(user).join(',') : ''}</div>
    </div>
  );
}

// Bearer tokens are namespaced per instance; the seeded default instance's key.
const TOKEN_KEY = 'homer_auth_token::default';

describe('AuthProvider (bearer mode)', () => {
  beforeEach(() => {
    getAuthMe.mockReset();
    login.mockReset();
    logout.mockReset();
    getHeartbeat.mockReset();
    resetInstancesStoreForTests();
  });

  it('lands anon when no bearer token is stored (probeAuthBearer throws)', async () => {
    renderWithProvider();
    await waitFor(() =>
      expect(screen.getByTestId('status')).toHaveTextContent('anon'),
    );
    expect(screen.getByTestId('username')).toHaveTextContent('');
    expect(getAuthMe).not.toHaveBeenCalled();
    expect(getHeartbeat).not.toHaveBeenCalled();
  });

  it('lands authed as the principal /auth/me reports for a stored token', async () => {
    localStorage.setItem(TOKEN_KEY, 'stored-token');
    getAuthMe.mockResolvedValue({ username: 'ci', role: null, grants: [{ agents: ['echo'], actions: ['run'] }] });

    renderWithProvider();

    expect(screen.getByTestId('status')).toHaveTextContent('loading');
    await waitFor(() =>
      expect(screen.getByTestId('status')).toHaveTextContent('authed'),
    );
    expect(screen.getByTestId('username')).toHaveTextContent('ci');
    expect(screen.getByTestId('role')).toHaveTextContent('');
    expect(screen.getByTestId('grant-count')).toHaveTextContent('1');
    expect(getHeartbeat).not.toHaveBeenCalled();
  });

  it.each([404, 401])(
    'falls back to no role and no grants when an older server answers /auth/me with %i',
    async (status) => {
      localStorage.setItem(TOKEN_KEY, 'stored-token');
      getAuthMe.mockRejectedValue(new HomerApiError('old server', status));
      getHeartbeat.mockResolvedValue(undefined);

      renderWithProvider();

      await waitFor(() =>
        expect(screen.getByTestId('status')).toHaveTextContent('authed'),
      );
      expect(getHeartbeat).toHaveBeenCalled();
      expect(screen.getByTestId('role')).toHaveTextContent('');
      expect(screen.getByTestId('grant-count')).toHaveTextContent('0');
    },
  );

  it('flags the fallback principal as unreported without putting the flag on the public user', async () => {
    localStorage.setItem(TOKEN_KEY, 'stored-token');
    getAuthMe.mockRejectedValue(new HomerApiError('Not Found', 404));
    getHeartbeat.mockResolvedValue(undefined);

    renderWithProvider(<UnreportedProbe />);

    await waitFor(() =>
      expect(screen.getByTestId('unreported')).toHaveTextContent('true'),
    );
    expect(screen.getByTestId('user-keys')).toHaveTextContent('username,role,grants');
  });

  it('does not flag a principal /auth/me reported', async () => {
    localStorage.setItem(TOKEN_KEY, 'stored-token');
    getAuthMe.mockResolvedValue({ username: 'ci', role: null, grants: [] });

    renderWithProvider(<UnreportedProbe />);

    await waitFor(() =>
      expect(screen.getByTestId('user-keys')).toHaveTextContent('username,role,grants'),
    );
    expect(screen.getByTestId('unreported')).toHaveTextContent('false');
  });

  it('lands anon when /auth/me cannot answer and /heartbeat rejects the token', async () => {
    localStorage.setItem(TOKEN_KEY, 'stale-token');
    getAuthMe.mockRejectedValue(new HomerApiError('Unauthorized', 401));
    getHeartbeat.mockRejectedValue(new HomerApiError('Unauthorized', 401));

    renderWithProvider();

    await waitFor(() =>
      expect(screen.getByTestId('status')).toHaveTextContent('anon'),
    );
  });

  it('lands anon without asking /heartbeat when /auth/me fails for another reason', async () => {
    localStorage.setItem(TOKEN_KEY, 'stored-token');
    getAuthMe.mockRejectedValue(new HomerApiError('Internal Server Error', 500));

    renderWithProvider();

    await waitFor(() =>
      expect(screen.getByTestId('status')).toHaveTextContent('anon'),
    );
    expect(getHeartbeat).not.toHaveBeenCalled();
  });

  it('login stores the password as the token, reads /auth/me, and lands authed as that principal', async () => {
    getAuthMe.mockImplementation(() =>
      localStorage.getItem(TOKEN_KEY) === 'pw'
        ? Promise.resolve({ username: 'ops', role: 'admin', grants: [] })
        : Promise.reject(new HomerApiError('Unauthorized', 401)),
    );
    getHeartbeat.mockRejectedValue(new HomerApiError('Unauthorized', 401));

    renderWithProvider();
    await waitFor(() =>
      expect(screen.getByTestId('status')).toHaveTextContent('anon'),
    );

    await act(async () => {
      screen.getByText('do-login').click();
    });

    await waitFor(() =>
      expect(screen.getByTestId('status')).toHaveTextContent('authed'),
    );
    // login() ignores the username and stores the password argument as the token.
    expect(localStorage.getItem(TOKEN_KEY)).toBe('pw');
    expect(login).not.toHaveBeenCalled();
    expect(screen.getByTestId('username')).toHaveTextContent('ops');
    expect(screen.getByTestId('role')).toHaveTextContent('admin');
  });

  it('login removes the token and stays anon when /heartbeat rejects', async () => {
    // First probe (mount) and the post-login probe both reject.
    getAuthMe.mockRejectedValue(new HomerApiError('Not Found', 404));
    getHeartbeat.mockRejectedValue(new Error('401'));

    let loginResult: string | undefined;
    renderWithProvider(
      <LoginOutcomeProbe
        password="tok"
        onResult={(r) => {
          loginResult = r;
        }}
      />,
    );

    await waitFor(() =>
      expect(screen.getByTestId('status')).toHaveTextContent('anon'),
    );

    await act(async () => {
      screen.getByText('do-login').click();
    });

    await waitFor(() =>
      expect(screen.getByTestId('status')).toHaveTextContent('anon'),
    );
    expect(localStorage.getItem(TOKEN_KEY)).toBeNull();
    // A plain (non-HomerApiError) rejection classifies as 'error', not a
    // credential rejection — the form maps this to "could not reach the server".
    expect(loginResult).toBe('error');
  });

  it.each([
    ['rate-limited', () => new HomerApiError('Too Many Requests', 429)],
    ['invalid-credentials', () => new HomerApiError('Unauthorized', 401)],
  ])('login reports %s when /auth/me rejects the token', async (expected, failure) => {
    // Same classification as cookie mode: a throttled backend must not read as a
    // bad token, or the user retries harder against the limiter.
    getAuthMe.mockRejectedValue(failure());
    getHeartbeat.mockRejectedValue(failure());

    let loginResult: string | undefined;
    renderWithProvider(
      <LoginOutcomeProbe
        password="dummy-token"
        onResult={(r) => {
          loginResult = r;
        }}
      />,
    );
    await waitFor(() =>
      expect(screen.getByTestId('status')).toHaveTextContent('anon'),
    );

    await act(async () => {
      screen.getByText('do-login').click();
    });

    await waitFor(() => expect(loginResult).toBe(expected));
    // A rejected token is not kept, and the console stays signed out.
    expect(localStorage.getItem(TOKEN_KEY)).toBeNull();
    expect(screen.getByTestId('status')).toHaveTextContent('anon');
  });

  it('logout removes the token and lands anon without calling homerApi.logout', async () => {
    localStorage.setItem(TOKEN_KEY, 'stored-token');
    getAuthMe.mockResolvedValue({ username: 'ops', role: 'admin', grants: [] });

    renderWithProvider();
    await waitFor(() =>
      expect(screen.getByTestId('status')).toHaveTextContent('authed'),
    );

    await act(async () => {
      screen.getByText('do-logout').click();
    });

    await waitFor(() =>
      expect(screen.getByTestId('status')).toHaveTextContent('anon'),
    );
    expect(localStorage.getItem(TOKEN_KEY)).toBeNull();
    // Bearer logout is purely local; the cookie-mode endpoint is not hit.
    expect(logout).not.toHaveBeenCalled();
    expect(screen.getByTestId('logout-outcome')).toHaveTextContent('ok');
  });
});
