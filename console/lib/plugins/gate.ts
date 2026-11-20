import type { Capabilities } from '@/lib/auth/use-can';
import type { PluginRequirement } from './types';

/**
 * The single plugin access gate, shared by the `PluginsProvider` pre-fetch
 * filter and the plugin route; no other call site may reimplement it. Denial
 * must look like a nonexistent plugin (ADR-0047 rule 3, ADR-0059).
 */
export function canUsePlugin(
  requires: PluginRequirement | undefined,
  { isAdmin, can }: Pick<Capabilities, 'isAdmin' | 'can'>,
): boolean {
  if (requires === undefined) return true;
  if (requires === 'admin') return isAdmin;
  return can(requires);
}
