import { useMemo } from 'react';
import { useAuth, type AuthUser } from './auth-context';
import { agentNameMatches } from '@/lib/utils/agent-name-matcher';
import { appConfig } from '@/lib/config/app-config';
import { activeMode, type InstanceMode } from '@/lib/instances/store';
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
 * The owner the backend stamps on the caller's runs (ADR-0038): `session:{username}` for a
 * session, the token's own name for a bearer token.
 */
function selfOwnerId(user: AuthUser | null, mode: InstanceMode): string | null {
  if (!user) return null;
  return mode === 'bearer' ? user.username : `session:${user.username}`;
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
  mode: InstanceMode = 'cookie',
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
    isAdmin || (p.owner != null && p.owner === selfOwnerId(user, mode));
  const canSeeProcess = (p: ProcessLike): boolean =>
    isAdmin || isOwner(p) || can('read', p.agentName);
  const canKillProcess = (p: ProcessLike): boolean =>
    isAdmin || isOwner(p) || (canSeeProcess(p) && can('control', p.agentName));
  const canRetryProcess = (p: ProcessLike): boolean => canSeeProcess(p) && can('run', p.agentName);
  return { isAdmin, can, canSeeProcess, canKillProcess, canRetryProcess };
}

export function useCan(): Capabilities {
  const { user } = useAuth();
  const unsecured = !appConfig.auth.enabled || appConfig.auth.mode === 'disabled';
  const mode = activeMode();
  return useMemo(() => capabilitiesFor(user ?? null, unsecured, mode), [user, unsecured, mode]);
}
