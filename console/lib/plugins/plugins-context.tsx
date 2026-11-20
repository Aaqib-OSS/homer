'use client';

import { createContext, useContext, useEffect, useState } from 'react';
import { appConfig } from '@/lib/config/app-config';
import { useCan } from '@/lib/auth/use-can';
import { canUsePlugin } from './gate';
import { checkPluginEntry, loadPlugins, toPluginEntry } from './loader';
import type { LoadedPlugin } from './types';

export interface PluginsState {
  plugins: LoadedPlugin[];
  /**
   * True until the configured plugin modules have settled; the plugin route
   * waits on it before rendering not-found.
   */
  loading: boolean;
}

const PluginsContext = createContext<PluginsState>({ plugins: [], loading: false });

export function PluginsProvider({ children }: { children: React.ReactNode }) {
  // Starts loading even with no entries: the server sees none, so deriving this
  // from the entry count would break hydration.
  const [state, setState] = useState<PluginsState>({ plugins: [], loading: true });
  const { isAdmin, can } = useCan();

  useEffect(() => {
    let cancelled = false;

    // Filter before the import so a denied caller never fetches the module; the
    // provider mounts after the auth probe settles (ADR-0059).
    const entries = appConfig.plugins.entries
      .filter((entry) => {
        const reason = checkPluginEntry(entry);
        if (reason !== null) console.error(`[plugins] ignoring ${entry}: ${reason}`);
        return reason === null;
      })
      .map(toPluginEntry)
      .filter((entry) => canUsePlugin(entry.requires, { isAdmin, can }));

    void loadPlugins(entries)
      .then((plugins) => {
        if (!cancelled) setState({ plugins, loading: false });
      })
      // Per-entry failures are contained in loadPlugins; this keeps an unforeseen
      // rejection from leaving the dashboard loading.
      .catch((e: unknown) => {
        console.error('[plugins] loader rejected unexpectedly:', e);
        if (!cancelled) setState({ plugins: [], loading: false });
      });

    return () => {
      cancelled = true;
    };
  }, [isAdmin, can]);

  return <PluginsContext.Provider value={state}>{children}</PluginsContext.Provider>;
}

export function usePlugins(): PluginsState {
  return useContext(PluginsContext);
}
