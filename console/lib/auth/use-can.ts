import { useMemo } from 'react';
import { useAuth, type AuthUser } from './auth-context';
import { agentNameMatches } from '@/lib/utils/agent-name-matcher';
import { appConfig } from '@/lib/config/app-config';
import { activeMode } from '@/lib/instances/store';
import type { Action } from '@/types/homer';

export type ProcessLike = { agentName: string; owner?: string | null };

export interface Capabilities {
  isAdmin: boolean;
  can(action: Action, agentName?: string): boolean;
  canSeeProcess(p: ProcessLike): boolean;
  canKillProcess(p: ProcessLike): boolean;
  canRetryProcess(p: ProcessLike): boolean;
}

/**
 * How the backend stamps the caller's runs (ADR-0038): `session:{username}`, the bearer token's
 * bare name, or `none` when the server named no principal, so no run is the caller's own.
 */
export type OwnerStamp = 'session' | 'token' | 'none';

function selfOwnerId(user: AuthUser | null, stamp: OwnerStamp): string | null {
  if (!user || stamp === 'none') return null;
  return stamp === 'token' ? user.username : `session:${user.username}`;
}

/**
 * Pure capability logic, mirroring the backend Authorizer's derived rules
 * (ADR-0037/0038). `unsecured` mirrors the backend's `!enabled` short-circuit
 * that allows everything when auth is off (see auth-context.tsx's
 * `authProbeDisabled`).
 */
export function capabilitiesFor(
  user: AuthUser | null,
  unsecured = false,
  stamp: OwnerStamp = 'session',
): Capabilities {
  const isAdmin = unsecured || user?.role === 'admin';
  const can = (action: Action, agentName?: string): boolean => {
    if (isAdmin) return true;
    if (!user) return false;
    if (agentName == null) return user.grants.some((g) => g.actions.includes(action));
    return user.grants.some(
      (g) => g.actions.includes(action) && g.agents.some((pat) => agentNameMatches(pat, agentName)),
    );
  };
  const isOwner = (p: ProcessLike): boolean =>
    isAdmin || (p.owner != null && p.owner === selfOwnerId(user, stamp));
  const canSeeProcess = (p: ProcessLike): boolean =>
    isAdmin || isOwner(p) || can('read', p.agentName);
  const canKillProcess = (p: ProcessLike): boolean =>
    isAdmin || isOwner(p) || (canSeeProcess(p) && can('control', p.agentName));
  const canRetryProcess = (p: ProcessLike): boolean => canSeeProcess(p) && can('run', p.agentName);
  return { isAdmin, can, canSeeProcess, canKillProcess, canRetryProcess };
}

/** The {@link OwnerStamp} of the signed-in caller: `none` for a principal the server did not report. */
function ownerStamp(principalUnreported: boolean): OwnerStamp {
  if (principalUnreported) return 'none';
  return activeMode() === 'bearer' ? 'token' : 'session';
}

export function useCan(): Capabilities {
  const { user, principalUnreported } = useAuth();
  const unsecured = !appConfig.auth.enabled || appConfig.auth.mode === 'disabled';
  const stamp = ownerStamp(principalUnreported);
  return useMemo(() => capabilitiesFor(user ?? null, unsecured, stamp), [user, unsecured, stamp]);
}
