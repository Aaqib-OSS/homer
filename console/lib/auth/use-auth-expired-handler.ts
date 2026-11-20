'use client';

import { useEffect, useRef, type RefObject } from 'react';
import { useRouter, usePathname } from 'next/navigation';
import { useQueryClient } from '@tanstack/react-query';
import { onAuthExpired } from './auth-expired-bus';
import { loginHref } from './return-path';
import { activeMode, getActiveToken, removeActiveToken } from '@/lib/instances/store';
import type { AuthStatus } from './auth-context';

/** Whether the 401 ended a session: a stored bearer token, or a cookie session that was once `authed` (`wasAuthed`). */
function sessionExisted(wasAuthed: boolean): boolean {
  return wasAuthed || (activeMode() === 'bearer' && getActiveToken() !== null);
}

/**
 * On auth-expired, once per mount: clears the bearer token and every cached per-user query (none may
 * reach the next sign-in), then redirects to /login with `next`, `expired=1` only if a session existed.
 * Returns the latch ref, `true` once recovery fired, so callers issue no competing navigation.
 */
export function useAuthExpiredHandler(status: AuthStatus): RefObject<boolean> {
  const router = useRouter();
  const pathname = usePathname();
  const queryClient = useQueryClient();
  const handled = useRef(false);
  // Latched: the ending 401 lands before the context re-renders to 'anon'.
  const wasAuthed = useRef(false);

  useEffect(() => {
    if (status === 'authed') wasAuthed.current = true;
  }, [status]);

  useEffect(() => {
    return onAuthExpired(() => {
      // clear() refetches mounted queries whose 401s re-fire this event before /login commits.
      if (handled.current) return;
      if (pathname?.startsWith('/login')) return;
      handled.current = true;
      // Read the credential before clearing it; denied storage counts as no session.
      let hadSession = false;
      try {
        hadSession = sessionExisted(wasAuthed.current);
      } catch {
        /* SSR or denied storage: no session */
      }
      try {
        removeActiveToken();
      } catch {
        /* SSR or denied storage; ignore */
      }
      queryClient.clear();
      router.replace(loginHref(pathname, hadSession));
    });
  }, [pathname, queryClient, router]);

  return handled;
}
