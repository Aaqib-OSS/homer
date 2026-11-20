/** The console's strict, per-request-nonce CSP; `connect-src` stays open unless the operator pins API origins (`console/CLAUDE.md`). */

import { splitCommaList } from '@/lib/utils/list';

export interface CspOptions {
  nonce: string;
  /** Backend origins `connect-src` is pinned to with `'self'`; empty leaves it open for ad-hoc backends at login. */
  allowedApiOrigins: string[];
}

export function buildCsp({ nonce, allowedApiOrigins }: CspOptions): string {
  const connectSrc =
    allowedApiOrigins.length > 0
      ? `connect-src 'self' ${allowedApiOrigins.join(' ')}`
      : // No allowlist: login can target any backend, so connect targets cannot be enumerated.
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
