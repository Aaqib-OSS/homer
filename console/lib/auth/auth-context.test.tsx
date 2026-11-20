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

vi.mock('@/lib/config/app-config', () => ({
  appConfig: {
    auth: { enabled: true, mode: 'cookie', tokenKey: 'homer_auth_token' },
  },
}));

import { useAuth } from './auth-context';
import { notifyAuthExpired } from './auth-expired-bus';
import { LoginOutcomeProbe, StatusProbe, renderWithProvider } from './auth-test-utils';
import { resetInstancesStoreForTests } from '@/lib/instances/store';

describe('AuthProvider (cookie mode)', () => {
  beforeEach(() => {
    getAuthMe.mockReset();
    login.mockReset();
    logout.mockReset();
    getHeartbeat.mockReset();
    resetInstancesStoreForTests();
  });

  // A button whose login() outcome the test reads back, for the classification cases.
  function loginCapture() {
    let outcome: string | undefined;
    renderWithProvider(
      <LoginOutcomeProbe
        onResult={(r) => {
          outcome = r;
        }}
      />,
    );
    return async () => {
      await act(async () => {
        screen.getByText('do-login').click();
      });
      return outcome;
    };
  }

  it('starts in loading then transitions to authed once /auth/me resolves', async () => {
    getAuthMe.mockResolvedValue({ username: 'alice', role: 'admin', grants: [] });
    renderWithProvider();

    expect(screen.getByTestId('status')).toHaveTextContent('loading');
    await waitFor(() =>
      expect(screen.getByTestId('status')).toHaveTextContent('authed'),
    );
    expect(screen.getByTestId('username')).toHaveTextContent('alice');
  });

  it('falls through to anon when /auth/me rejects (401-like)', async () => {
    getAuthMe.mockRejectedValue(new Error('401'));
    renderWithProvider();
    await waitFor(() =>
      expect(screen.getByTestId('status')).toHaveTextContent('anon'),
    );
    expect(screen.getByTestId('username')).toHaveTextContent('');
  });

  it('login posts credentials, fetches /me, seeds the cache, and lands authed', async () => {
    let probeShouldSucceed = false;
    getAuthMe.mockImplementation(() => {
      if (probeShouldSucceed) {
        return Promise.resolve({ username: 'alice', role: 'admin', grants: [] });
      }
      return Promise.reject(new Error('401'));
    });
    login.mockImplementation(() => {
      probeShouldSucceed = true;
      return Promise.resolve(undefined);
    });

    const { client } = renderWithProvider();
    await waitFor(() =>
      expect(screen.getByTestId('status')).toHaveTextContent('anon'),
    );

    await act(async () => {
      screen.getByText('do-login').click();
    });

    await waitFor(() =>
      expect(screen.getByTestId('status')).toHaveTextContent('authed'),
    );
    expect(login).toHaveBeenCalledWith('alice', 'pw');
    expect(getAuthMe).toHaveBeenCalled();
    expect(client.getQueryData(['auth', 'me'])).toEqual({ username: 'alice', role: 'admin', grants: [] });
  });

  it('login never transitions through loading after the initial anon state', async () => {
    // After the probe resolves once, login() never bounces status back to 'loading'.
    let probeShouldSucceed = false;
    getAuthMe.mockImplementation(() => {
      if (probeShouldSucceed) {
        return Promise.resolve({ username: 'alice', role: 'admin', grants: [] });
      }
      return Promise.reject(new Error('401'));
    });
    login.mockImplementation(() => {
      probeShouldSucceed = true;
      return Promise.resolve(undefined);
    });

    const seenStatuses: string[] = [];
    function StatusRecorder() {
      const { status } = useAuth();
      seenStatuses.push(status);
      return null;
    }

    renderWithProvider(
      <>
        <StatusRecorder />
        <StatusProbe />
      </>,
    );

    await waitFor(() =>
      expect(screen.getByTestId('status')).toHaveTextContent('anon'),
    );
    // Drop the initial transient 'loading' (first render before the probe
    // settles) — we only care about post-anon behavior.
    const indexOfAnon = seenStatuses.indexOf('anon');
    expect(indexOfAnon).toBeGreaterThanOrEqual(0);
    const postAnon = seenStatuses.slice(indexOfAnon);
    expect(postAnon).not.toContain('loading');
    seenStatuses.length = 0;

    await act(async () => {
      screen.getByText('do-login').click();
    });

    await waitFor(() =>
      expect(screen.getByTestId('status')).toHaveTextContent('authed'),
    );
    // The post-login render sequence must go anon → authed without bouncing
    // through loading.
    expect(seenStatuses).not.toContain('loading');
    expect(seenStatuses).toContain('authed');
  });

  it('exposes the principal synchronously after login (no flicker through user=undefined)', async () => {
    // login() seeds both status and user in one synchronous update.
    let probeShouldSucceed = false;
    getAuthMe.mockImplementation(() => {
      if (probeShouldSucceed) {
        return Promise.resolve({ username: 'alice', role: 'admin', grants: [] });
      }
      return Promise.reject(new Error('401'));
    });
    login.mockImplementation(() => {
      probeShouldSucceed = true;
      return Promise.resolve(undefined);
    });

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
    expect(screen.getByTestId('username')).toHaveTextContent('alice');
  });

  it('clears the authed override when the auth-expired bus fires', async () => {
    // An auth-expired notification drops the override back to 'anon'.
    let probeShouldSucceed = false;
    getAuthMe.mockImplementation(() => {
      if (probeShouldSucceed) {
        return Promise.resolve({ username: 'alice', role: 'admin', grants: [] });
      }
      return Promise.reject(new Error('401'));
    });
    login.mockImplementation(() => {
      probeShouldSucceed = true;
      return Promise.resolve(undefined);
    });

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

    await act(async () => {
      notifyAuthExpired('http-401');
    });

    await waitFor(() =>
      expect(screen.getByTestId('status')).toHaveTextContent('anon'),
    );
  });

  it('logout calls homerApi.logout, clears cache, and forces anon', async () => {
    getAuthMe.mockResolvedValue({ username: 'alice', role: 'admin', grants: [] });
    logout.mockResolvedValue(undefined);

    const { client } = renderWithProvider();
    await waitFor(() =>
      expect(screen.getByTestId('status')).toHaveTextContent('authed'),
    );
    client.setQueryData(['scratch'], { keep: true });

    await act(async () => {
      screen.getByText('do-logout').click();
    });

    expect(logout).toHaveBeenCalled();
    await waitFor(() =>
      expect(screen.getByTestId('status')).toHaveTextContent('anon'),
    );
    expect(client.getQueryData(['scratch'])).toBeUndefined();
    expect(screen.getByTestId('logout-outcome')).toHaveTextContent('ok');
  });

  /** Renders an authed session with a scratch cache entry, then logs out against a failing server. */
  async function logoutAgainstFailingServer() {
    getAuthMe.mockResolvedValue({ username: 'alice', role: 'admin', grants: [] });
    logout.mockRejectedValue(new HomerApiError('Service Unavailable', 503));
    const { client } = renderWithProvider();
    await waitFor(() =>
      expect(screen.getByTestId('status')).toHaveTextContent('authed'),
    );
    client.setQueryData(['scratch'], { keep: true });
    await act(async () => {
      screen.getByText('do-logout').click();
    });
    await waitFor(() =>
      expect(screen.getByTestId('logout-outcome')).not.toBeEmptyDOMElement(),
    );
    return client;
  }

  it('logout reports server-unconfirmed when the request fails', async () => {
    await logoutAgainstFailingServer();
    expect(screen.getByTestId('logout-outcome')).toHaveTextContent('server-unconfirmed');
  });

  it('a failed logout still signs out locally', async () => {
    await logoutAgainstFailingServer();
    expect(screen.getByTestId('status')).toHaveTextContent('anon');
  });

  it('a failed logout still clears the cached data', async () => {
    const client = await logoutAgainstFailingServer();
    expect(client.getQueryData(['scratch'])).toBeUndefined();
  });

  it('login returns rate-limited when the login endpoint responds 429', async () => {
    // A 429 is its own outcome, so the form says wait, not invalid credentials.
    getAuthMe.mockRejectedValue(new Error('401')); // mount lands anon
    login.mockRejectedValue(new HomerApiError('Too Many Requests', 429));

    let outcome: string | undefined;
    renderWithProvider(
      <LoginOutcomeProbe
        onResult={(r) => {
          outcome = r;
        }}
      />,
    );
    await act(async () => {
      screen.getByText('do-login').click();
    });
    await waitFor(() => expect(outcome).toBe('rate-limited'));
  });

  it.each([
    ['invalid-credentials', () => new HomerApiError('Unauthorized', 401)],
    ['error', () => new HomerApiError('Server Error', 500)],
    ['error', () => new Error('Failed to fetch')],
  ])('login reports %s when the credential POST is rejected', async (expected, failure) => {
    // A 401 is the password being wrong; every other rejection — a 500, a network
    // failure — is the console failing to reach the server, and the form says so.
    getAuthMe.mockRejectedValue(new Error('401')); // mount lands anon
    login.mockRejectedValue(failure());

    const submit = loginCapture();
    await waitFor(() =>
      expect(screen.getByTestId('status')).toHaveTextContent('anon'),
    );

    expect(await submit()).toBe(expected);
  });

  it('reads a status only off the client\'s own error type', async () => {
    // Only HomerApiError carries a backend status; a foreign `status` field is not a 429.
    getAuthMe.mockRejectedValue(new Error('401')); // mount lands anon
    login.mockRejectedValue(Object.assign(new Error('Too Many Requests'), { status: 429 }));

    const submit = loginCapture();
    await waitFor(() =>
      expect(screen.getByTestId('status')).toHaveTextContent('anon'),
    );

    expect(await submit()).toBe('error');
  });

  it('reports the /auth/me re-probe failure, not the accepted credential POST', async () => {
    // The POST succeeds but the session probe behind it is rejected — the login
    // limiter's 429 here must still reach the form as rate-limited.
    let probes = 0;
    getAuthMe.mockImplementation(() => {
      probes += 1;
      return probes === 1
        ? Promise.reject(new Error('401'))
        : Promise.reject(new HomerApiError('Too Many Requests', 429));
    });
    login.mockResolvedValue(undefined);

    const submit = loginCapture();
    await waitFor(() =>
      expect(screen.getByTestId('status')).toHaveTextContent('anon'),
    );

    expect(await submit()).toBe('rate-limited');
    expect(login).toHaveBeenCalledWith('alice', 'pw');
    expect(screen.getByTestId('status')).toHaveTextContent('anon');
  });

  it('maps /me role and grants into the auth user', async () => {
    getAuthMe.mockResolvedValue({
      username: 'bob',
      role: null,
      grants: [{ agents: ['deploy-*'], actions: ['run', 'read'] }],
    });

    renderWithProvider();

    await waitFor(() =>
      expect(screen.getByTestId('status')).toHaveTextContent('authed'),
    );
    expect(screen.getByTestId('username')).toHaveTextContent('bob');
    expect(screen.getByTestId('role')).toHaveTextContent('');
    expect(screen.getByTestId('grant-count')).toHaveTextContent('1');
  });
});
