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

/** Outcome of a `login` attempt; `rate-limited` (429) is kept apart from `invalid-credentials` so the UI says wait, not retry. */
export type LoginOutcome = 'ok' | 'invalid-credentials' | 'rate-limited' | 'error';

/**
 * Outcome of a `logout`. The console signs out locally either way; `server-unconfirmed` means the
 * server did not confirm it, so the session cookie may still be valid.
 */
export type LogoutOutcome = 'ok' | 'server-unconfirmed';

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
  /**
   * `cookie` mode posts the credentials and resolves once `/auth/me` answers; `bearer` mode stores
   * `password` as the token (`username` ignored); `disabled` is a no-op. Never rejects: see {@link LoginOutcome}.
   */
  login: (username: string, password: string) => Promise<LoginOutcome>;
  logout: () => Promise<LogoutOutcome>;
  /**
   * Sign out of the *current* active instance and switch to `instanceId` in one
   * step. Unlike `logout()` + `switchInstance()`, the override is never pinned
   * to `anon` mid-operation — the dashboard gate redirects on that status.
   */
  signOutAndSwitch: (instanceId: string) => Promise<void>;
  /** Points the active id at `instanceId`, drops the previous instance's cached data and re-probes auth. */
  switchInstance: (instanceId: string) => void;
}

const AuthContext = createContext<AuthContextType | undefined>(undefined);

const AUTH_QUERY_KEY = ['auth', 'me'] as const;
const authProbeDisabled = !appConfig.auth.enabled || appConfig.auth.mode === 'disabled';

/** Cookie-mode probe: `/auth/me` answers the session's principal, or 401. */
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
 * Synchronous status set on login / logout so mounted subscribers flip without React Query's commit
 * (the dashboard layout otherwise sticks on Loading); `markAuthed` also seeds the cache for a remounted provider.
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

/** Ends the active instance's session where it lives: the server's cookie session, or the stored token. */
async function endSession(): Promise<LogoutOutcome> {
  if (activeMode() !== 'cookie') {
    removeActiveToken();
    return 'ok';
  }
  try {
    await homerApi.logout();
    return 'ok';
  } catch {
    return 'server-unconfirmed';
  }
}

async function logoutActive({ queryClient, setOverride }: AuthSetters): Promise<LogoutOutcome> {
  const outcome = await endSession();
  queryClient.clear();
  setOverride({ kind: 'anon' });
  return outcome;
}

/** login / logout / signOutAndSwitch / switchInstance, and the auth-expired listener, over the given override setter. */
function useAuthActions(
  queryClient: QueryClient,
  setOverride: (override: AuthOverride) => void,
): Pick<AuthContextType, 'login' | 'logout' | 'signOutAndSwitch' | 'switchInstance'> {
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
      queryClient.clear();
      setOverride(null);
    },
    [queryClient, setOverride],
  );

  // Sign-out-and-switch for removing the active instance: the override drops
  // straight to null, never through `anon`, so the gate reads loading instead.
  const signOutAndSwitch = useCallback(
    async (instanceId: string) => {
      if (!authProbeDisabled) {
        await endSession();
      }
      setActiveInstance(instanceId);
      queryClient.clear();
      setOverride(null);
    },
    [queryClient, setOverride],
  );

  // A 401 elsewhere fires the auth-expired bus; pin the override to 'anon' so no stale 'authed' shows.
  useEffect(() => {
    return onAuthExpired(() => setOverride({ kind: 'anon' }));
  }, [setOverride]);

  const logout = useCallback(async (): Promise<LogoutOutcome> => {
    if (authProbeDisabled) {
      return 'ok';
    }
    return logoutActive({ queryClient, setOverride });
  }, [queryClient, setOverride]);

  return { login, logout, signOutAndSwitch, switchInstance };
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
  const { login, logout, signOutAndSwitch, switchInstance } = useAuthActions(
    queryClient,
    setOverride,
  );

  return (
    <AuthContext.Provider
      value={{
        status,
        user,
        principalUnreported: probed?.unreported === true,
        login,
        logout,
        signOutAndSwitch,
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
