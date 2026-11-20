'use client';

import { useEffect, useRef } from 'react';
import { useRouter } from 'next/navigation';
import { useTheme } from 'next-themes';
import { homerApi } from '@/lib/api/client';
import { useAuth } from '@/lib/auth/auth-context';
import type { LoadedPlugin } from '@/lib/plugins/types';

/** `''` or a `/`-prefixed path; anything else is normalized. */
function normalizeSubPath(path: string): string {
  if (path === '' || path === '/') return '';
  return path.startsWith('/') ? path : `/${path}`;
}

/**
 * Renders a runtime-loaded plugin, which owns its React root, into a DOM node and calls its
 * cleanup on unmount. `subPath` is a dep of the mount effect: the App Router remounts this
 * subtree on a sub-path change, and omitting it would leave a stale path.
 */
export function PluginHost({ plugin, subPath }: { plugin: LoadedPlugin; subPath: string }) {
  const hostRef = useRef<HTMLDivElement>(null);
  const { user } = useAuth();
  const { resolvedTheme } = useTheme();
  const router = useRouter();

  // User, theme and router live in a ref so a theme toggle or an auth refresh
  // does not remount the plugin. Sub-path deliberately does not — see above.
  const liveRef = useRef({ user: user ?? null, theme: resolvedTheme, router });

  // Declared before the mount effect so the values are fresh by the time it
  // runs; on first render the useRef initializer has already supplied them.
  useEffect(() => {
    liveRef.current = { user: user ?? null, theme: resolvedTheme, router };
  }, [user, resolvedTheme, router]);

  useEffect(() => {
    const el = hostRef.current;
    if (!el) return;

    let cleanup: void | (() => void);
    try {
      cleanup = plugin.mount(el, {
        api: homerApi,
        user: liveRef.current.user,
        theme: liveRef.current.theme,
        path: subPath,
        navigate: (path) => {
          liveRef.current.router.push(`${plugin.route}${normalizeSubPath(path)}`);
        },
      });
    } catch (e) {
      console.error(`[plugins] ${plugin.sourceUrl} threw during mount:`, e);
      // Reported into the host element, not via setState: a synchronous setState here
      // is a cascading render.
      el.replaceChildren(failureNode(plugin.title));
    }

    return () => {
      try {
        cleanup?.();
      } catch (e) {
        console.error(`[plugins] ${plugin.sourceUrl} threw during unmount:`, e);
      }
      // A plugin that ignored its cleanup contract would otherwise leak its DOM
      // into the next route.
      el.replaceChildren();
    };
  }, [plugin, subPath]);

  return (
    <div className="p-6">
      <div ref={hostRef} data-testid="plugin-host" />
    </div>
  );
}

function failureNode(title: string): HTMLElement {
  const p = document.createElement('p');
  p.setAttribute('role', 'alert');
  p.className = 'text-destructive';
  p.textContent = `Plugin ${title} failed to render.`;
  return p;
}
