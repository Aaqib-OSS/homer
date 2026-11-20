'use client';

import React, { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import { type QueryClient, type UseQueryResult, useQuery, useQueryClient } from '@tanstack/react-query';
import { appConfig } from '@/lib/config/app-config';
import { homerApi, HomerApiError } from '@/lib/api/client';
import {
  activeMode,
  getActiveToken,
  removeActiveToken,
  setActiveInstance,
  setActiveToken,
} from '@/lib/instances/store';
import { onAuthExpired } from '@/lib/auth/auth-expired-bus';
import type { Grant, MeResponse, Role } from '@/types/homer';

export type AuthStatus = 'loading' | 'authed' | 'anon';

/**
 * Outcome of a `login` attempt. `rate-limited` is distinguished from
 * `invalid-credentials` so the UI can tell the user to wait rather than
 * mislabel a 429 as a bad password — mislabeling drives users to retry
 * harder, which drains the login limiter's bucket and prolongs the lockout.
 */
export type LoginOutcome = 'ok' | 'invalid-credentials' | 'rate-limited' | 'error';

function classifyLoginFailure(e: unknown): Exclude<LoginOutcome, 'ok'> {
  if (e instanceof HomerApiError) {
    if (e.status === 429) return 'rate-limited';
    if (e.status === 401) return 'invalid-credentials';
  }
  return 'error';
}

export interface AuthUser {
  username: string;
  role: Role;
  grants: Grant[];
}

/** An {@link AuthUser} as the probe found it; `unreported` stays inside the auth layer, never in `user`. */
type ProbedUser = AuthUser & { unreported?: true };

interface AuthContextType {
  status: AuthStatus;
  user?: AuthUser;
  /** True when an older server named no principal for the bearer token: `user.username` is a placeholder. */
  principalUnreported: boolean;
  /** Legacy boolean shim — true while `status === 'authed'`. */
  isAuthenticated: boolean;
  /** Legacy boolean shim — true while `status === 'loading'`. */
  isLoading: boolean;
  /**
   * In `cookie` mode: posts username + password to `/api/v1/auth/login` and
   * waits for the resulting `homer_session` cookie + `/me` refetch before
   * resolving. In `bearer` mode: ignores `username`, stores `password` as
   * the bearer token, reads the token's principal from `/auth/me`. In
   * `disabled` mode: no-op.
   *
   * Returns `'ok'` on success, `'invalid-credentials'` on rejection,
   * `'rate-limited'` when the login limiter returns 429, or `'error'` for
   * anything else (network/timeout/server error).
   */
  login: (username: string, password: string) => Promise<LoginOutcome>;
  logout: () => Promise<void>;
  /**
   * Switch the active Homer instance: point the active id at `instanceId`,
   * drop the previous instance's cached data, and re-probe auth against the
   * new instance. Cookie sessions are per-origin in the browser, so switching
   * between two already-authed cookie instances needs no re-login.
   */
  switchInstance: (instanceId: string) => void;
}

const AuthContext = createContext<AuthContextType | undefined>(undefined);

const AUTH_QUERY_KEY = ['auth', 'me'] as const;
const authProbeDisabled = !appConfig.auth.enabled || appConfig.auth.mode === 'disabled';

/**
 * Probe used to populate the auth-me query cache.
 *  - cookie mode: GET `/api/v1/auth/me` — server returns `{username, role, grants}`
 *    on a valid session cookie, 401 on any other.
 *  - bearer mode: the stored token's principal, from {@link bearerPrincipal}.
 *  - disabled mode: never invoked (query is disabled).
 */
async function probeAuthCookie(): Promise<AuthUser> {
  const me: MeResponse = await homerApi.getAuthMe();
  return { username: me.username, role: me.role, grants: me.grants };
}

/**
 * The active token's principal from `/auth/me`. An older server that cannot answer it for a token
 * (404, or 401 under a combined scheme) is only asked `/heartbeat`, and the token gets no role
 * and no grants: the console never assumes a capability the server did not report (ADR-0059).
 */
async function bearerPrincipal(): Promise<ProbedUser> {
  try {
    const me: MeResponse = await homerApi.getAuthMe();
    return { username: me.username, role: me.role, grants: me.grants };
  } catch (e) {
    if (!(e instanceof HomerApiError) || (e.status !== 401 && e.status !== 404)) throw e;
  }
  await homerApi.getHeartbeat();
  return { username: 'bearer', role: null, grants: [], unreported: true };
}

async function probeAuthBearer(): Promise<ProbedUser> {
  const token = getActiveToken();
  if (!token) throw new Error('no bearer token');
  return bearerPrincipal();
}

async function probeAuth(): Promise<ProbedUser> {
  if (activeMode() === 'cookie') {
    return probeAuthCookie();
  }
  return probeAuthBearer();
}

/**
 * Synchronous override the provider sets on login / logout so the UI
 * status flips immediately, independent of React Query's re-render
 * scheduling. seeding `queryClient.setQueryData` alone wasn't enough:
 * in production builds the dashboard layout could mount on the
 * `router.push('/processes')` tick before the AuthProvider's
 * query-subscription re-render flushed, read `query.isPending === true`,
 * and lock onto its `<div>Loading...</div>` branch. The override
 * decouples the user-facing status from React Query's commit timing.
 *
 * `'authed'` carries the user so we don't have to wait for the query's
 * data to be re-read either.
 */
type AuthOverride =
  | { kind: 'authed'; user: ProbedUser }
  | { kind: 'anon' }
  | null;

type AuthState = { status: AuthStatus; user: ProbedUser | undefined };
type AuthProbe = Pick<UseQueryResult<ProbedUser>, 'isPending' | 'isError' | 'data'>;

/** The status the override pins, ahead of anything the probe says. */
function overrideState(override: NonNullable<AuthOverride>): AuthState {
  return override.kind === 'authed'
    ? { status: 'authed', user: override.user }
    : { status: 'anon', user: undefined };
}

/** The status the auth-me probe reports: loading, rejected (anon) or the principal. */
function probeState(query: AuthProbe): AuthState {
  if (query.isPending) return { status: 'loading', user: undefined };
  if (query.isError) return { status: 'anon', user: undefined };
  return { status: 'authed', user: query.data };
}

/** Auth disabled wins, then the login / logout override, then the probe. */
function resolveAuthState(override: AuthOverride, query: AuthProbe): AuthState {
  if (authProbeDisabled) return { status: 'authed', user: undefined };
  if (override) return overrideState(override);
  return probeState(query);
}

interface AuthSetters {
  queryClient: QueryClient;
  setOverride: (override: AuthOverride) => void;
}

function markAuthed({ queryClient, setOverride }: AuthSetters, principal: ProbedUser): void {
  // Seed the cache so a fresh useQuery mount (e.g. AuthProvider
  // remount under route transition) reads success on first render,
  // and set the synchronous override so existing subscribers flip
  // to 'authed' without waiting for the query observer's re-render.
  queryClient.setQueryData(AUTH_QUERY_KEY, principal);
  setOverride({ kind: 'authed', user: principal });
}

async function loginCookie(setters: AuthSetters, username: string, password: string): Promise<LoginOutcome> {
  try {
    await homerApi.login(username, password);
  } catch (e) {
    return classifyLoginFailure(e);
  }
  let me: MeResponse;
  try {
    me = await homerApi.getAuthMe();
  } catch (e) {
    return classifyLoginFailure(e);
  }
  markAuthed(setters, { username: me.username, role: me.role, grants: me.grants });
  return 'ok';
}

async function loginBearer(setters: AuthSetters, password: string): Promise<LoginOutcome> {
  // bearer (legacy): username is ignored; password is the token.
  try {
    setActiveToken(password);
    markAuthed(setters, await bearerPrincipal());
    return 'ok';
  } catch (e) {
    removeActiveToken();
    setters.setOverride({ kind: 'anon' });
    return classifyLoginFailure(e);
  }
}

async function logoutActive({ queryClient, setOverride }: AuthSetters): Promise<void> {
  if (activeMode() === 'cookie') {
    try {
      await homerApi.logout();
    } catch {
      // Server may already consider us logged out; treat as success.
    }
  } else {
    removeActiveToken();
  }
  queryClient.clear();
  setOverride({ kind: 'anon' });
}

/** login / logout / switchInstance, and the auth-expired listener, over the given override setter. */
function useAuthActions(
  queryClient: QueryClient,
  setOverride: (override: AuthOverride) => void,
): Pick<AuthContextType, 'login' | 'logout' | 'switchInstance'> {
  const login = useCallback(
    async (username: string, password: string): Promise<LoginOutcome> => {
      if (authProbeDisabled) {
        return 'ok';
      }
      const setters = { queryClient, setOverride };
      if (activeMode() === 'cookie') {
        return loginCookie(setters, username, password);
      }
      return loginBearer(setters, password);
    },
    [queryClient, setOverride],
  );

  const switchInstance = useCallback(
    (instanceId: string) => {
      setActiveInstance(instanceId);
      // Drop all data cached for the previous instance, then re-probe against
      // the new active instance (clearing the override lets the auth-me query
      // refetch from its now-empty cache).
      queryClient.clear();
      setOverride(null);
    },
    [queryClient, setOverride],
  );

  // If a 401 on any other request signals the session expired, the
  // auth-expired bus fires. Clear the override so the next render
  // doesn't show a stale `'authed'` status.
  useEffect(() => {
    return onAuthExpired(() => setOverride({ kind: 'anon' }));
  }, [setOverride]);

  const logout = useCallback(async () => {
    if (authProbeDisabled) {
      return;
    }
    await logoutActive({ queryClient, setOverride });
  }, [queryClient, setOverride]);

  return { login, logout, switchInstance };
}

/** [probed] without the auth layer's own `unreported` flag, which never reaches plugins (ADR-0059). */
function publicUser(probed: ProbedUser | undefined): AuthUser | undefined {
  if (!probed) return undefined;
  const { username, role, grants } = probed;
  return { username, role, grants };
}

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const queryClient = useQueryClient();
  const [override, setOverride] = useState<AuthOverride>(null);

  const query = useQuery({
    queryKey: AUTH_QUERY_KEY,
    queryFn: probeAuth,
    enabled: !authProbeDisabled,
    retry: false,
    staleTime: Infinity,
    refetchOnWindowFocus: false,
  });

  const { status, user: probed } = resolveAuthState(override, query);
  const user = useMemo(() => publicUser(probed), [probed]);
  const { login, logout, switchInstance } = useAuthActions(queryClient, setOverride);

  return (
    <AuthContext.Provider
      value={{
        status,
        user,
        principalUnreported: probed?.unreported === true,
        isAuthenticated: status === 'authed',
        isLoading: status === 'loading',
        login,
        logout,
        switchInstance,
      }}
    >
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth() {
  const context = useContext(AuthContext);
  if (context === undefined) {
    throw new Error('useAuth must be used within an AuthProvider');
  }
  return context;
}
