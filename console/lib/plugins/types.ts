import type { homerApi } from '@/lib/api/client';
import type { AuthUser } from '@/lib/auth/auth-context';
import type { Action } from '@/types/homer';

/**
 * What the console hands a plugin at mount time: its entire view of the host,
 * since a plugin cannot import from the console. Changing it breaks deployed
 * plugins.
 */
export interface PluginContext {
  /** Authenticated Homer API client, already bound to the active instance. */
  api: typeof homerApi;
  user: AuthUser | null;
  /** Active `next-themes` theme name, so a plugin can branch on it if it must. */
  theme: string | undefined;
  /**
   * Path *below* the plugin's route: `''` at the root, `/a` on `/myfleet/a`.
   * The plugin is remounted on every sub-path change (ADR-0059), so state that
   * must survive navigation belongs in module scope or `sessionStorage`.
   */
  path: string;
  /**
   * Client-side navigation relative to the plugin's route: `navigate('/a')` goes
   * to `<route>/a`, `navigate('')` to the root. An `<a href>` would reload the page.
   */
  navigate(path: string): void;
}

/** Default export a remote plugin module must provide. */
export interface PluginManifest {
  /**
   * Mount point, e.g. `/myfleet`; must match {@link ROUTE_PATTERN}. Everything
   * below it reaches the plugin as {@link PluginContext.path}.
   */
  route: string;
  /** Sidebar label. */
  title: string;
  /**
   * Renders the plugin into `el`; the returned function, if any, runs on unmount.
   * It is called again on every sub-path change and twice under StrictMode, so it
   * must be idempotent and its cleanup must release what it took.
   */
  mount(el: HTMLElement, ctx: PluginContext): void | (() => void);
}

/** Capability a caller must hold to see and open a plugin. */
export type PluginRequirement = 'admin' | Action;

export interface LoadedPlugin extends PluginManifest {
  /** Module path this manifest came from; shown in load-failure diagnostics. */
  sourceUrl: string;
  /**
   * Requirement from the plugin's `CONSOLE_PLUGINS` entry, not its manifest
   * (ADR-0059). Absent = any authenticated caller.
   */
  requires?: PluginRequirement;
}
