/**
 * The console's Content-Security-Policy: one strict policy for every route, with a per-request
 * nonce. `connect-src` is open unless the operator pins API origins. See `console/CLAUDE.md`.
 */

import { splitCommaList } from '@/lib/utils/list';

export interface CspOptions {
  nonce: string;
  /**
   * Operator-configured backend origins the console may connect to. When
   * non-empty, `connect-src` is pinned to `'self'` + these; when empty,
   * `connect-src` stays open (`https:`/`http:`) so an operator can point the
   * console at an ad-hoc backend at login.
   */
  allowedApiOrigins: string[];
}

export function buildCsp({ nonce, allowedApiOrigins }: CspOptions): string {
  const connectSrc =
    allowedApiOrigins.length > 0
      ? `connect-src 'self' ${allowedApiOrigins.join(' ')}`
      : // No allowlist: the operator may log into any backend they type, so the
        // set of connect targets is unbounded and cannot be enumerated here.
        "connect-src 'self' https: http:";

  return [
    "default-src 'self'",
    `script-src 'self' 'nonce-${nonce}' 'strict-dynamic'`,
    // Tailwind/Next inject inline <style>; nonce-ing styles is fragile and style
    // injection is far lower risk than script injection.
    "style-src 'self' 'unsafe-inline'",
    "img-src 'self' data: https:",
    "font-src 'self' data:",
    connectSrc,
    "worker-src 'self'",
    "object-src 'none'",
    "base-uri 'self'",
    "frame-ancestors 'none'",
    "form-action 'self'",
  ].join('; ');
}

/** Parses `CONSOLE_ALLOWED_API_ORIGINS` (comma-separated) into a clean list. */
export function parseAllowedApiOrigins(raw: string | undefined): string[] {
  return splitCommaList(raw);
}
