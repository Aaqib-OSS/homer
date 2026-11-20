/** Module-scoped bus: the API client and SSE notify a 401, `useAuthExpiredHandler` performs logout and redirect. */
const target = new EventTarget();

const AUTH_EXPIRED_EVENT = 'auth-expired';

export type AuthExpiredReason = 'http-401' | 'sse-401' | 'manual';

export function notifyAuthExpired(reason: AuthExpiredReason): void {
  target.dispatchEvent(new CustomEvent(AUTH_EXPIRED_EVENT, { detail: { reason } }));
}

export function onAuthExpired(
  handler: (reason: AuthExpiredReason) => void,
): () => void {
  const listener = (e: Event) => {
    const reason = (e as CustomEvent).detail?.reason ?? 'manual';
    handler(reason);
  };
  target.addEventListener(AUTH_EXPIRED_EVENT, listener);
  return () => target.removeEventListener(AUTH_EXPIRED_EVENT, listener);
}
