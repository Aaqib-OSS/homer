/**
 * The post-sign-in return path from the attacker-craftable `/login?next=…`: only a same-origin,
 * path-only value is accepted (ADR-0047 rule 2, amendment 2026-11-20), re-checked at the login form.
 */

/** Where sign-in lands when there is no acceptable return path. */
export const DEFAULT_RETURN_PATH = '/processes';

/** Longest accepted return path; a longer value is dropped, not truncated. */
const MAX_RETURN_PATH_LENGTH = 2048;

// Fixed base: the rule is same origin as this base, so the check needs no browser origin (SSR, tests).
const PARSE_BASE = 'http://return-path.invalid';

/**
 * Whether `value` is a relative console path: exactly one leading `/`, no
 * backslash, no control character and no `?`/`#`, so an `endpoint=` or `mode=`
 * parameter cannot ride along into the destination.
 */
function isRelativePath(value: string): boolean {
  if (!value.startsWith('/') || value.startsWith('//') || value.startsWith('/\\')) {
    return false;
  }
  return !/[\u0000-\u001f\u007f?\\#]/.test(value);
}

/** The decoded form of a path, or `null` when it is not valid percent-encoding. */
function decodePath(raw: string): string | null {
  try {
    return decodeURIComponent(raw);
  } catch {
    return null;
  }
}

/**
 * Whether both the raw and the decoded form are a relative console path that is
 * not `/login` itself — decoding first is what rejects `/%2F%2Fevil.test`.
 */
function isSafeShape(raw: string, decoded: string): boolean {
  return isRelativePath(raw) && isRelativePath(decoded) && !decoded.startsWith('/login');
}

/** Whether `raw` parses back to itself as a path on {@link PARSE_BASE}. */
function isSameOriginPath(raw: string): boolean {
  const parsed = new URL(raw, PARSE_BASE);
  return parsed.origin === PARSE_BASE && parsed.pathname === raw;
}

/**
 * `raw` when it is a same-origin relative console path, else
 * {@link DEFAULT_RETURN_PATH}. Rejects absolute and protocol-relative URLs, any
 * scheme, `..`, `/login` (a redirect loop) and percent-encoded forms of those.
 */
export function safeReturnPath(raw: string | null | undefined): string {
  if (!raw || raw.length > MAX_RETURN_PATH_LENGTH) return DEFAULT_RETURN_PATH;

  const decoded = decodePath(raw);
  if (decoded === null || !isSafeShape(raw, decoded)) return DEFAULT_RETURN_PATH;
  return isSameOriginPath(raw) ? raw : DEFAULT_RETURN_PATH;
}

/**
 * The `/login` URL to send a reader to: the requested `pathname` rides along as
 * `next`, and `expired` is added only when a session actually existed and
 * expired — a first visit gets the neutral prompt instead.
 */
export function loginHref(pathname: string | null | undefined, expired: boolean): string {
  const params = new URLSearchParams({ next: safeReturnPath(pathname) });
  if (expired) params.set('expired', '1');
  return `/login?${params.toString()}`;
}
