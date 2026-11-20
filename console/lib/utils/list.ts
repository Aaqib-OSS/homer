/**
 * Splits a comma-separated value into trimmed, non-empty items in order; absent or blank gives `[]`.
 * Lexing only: callers validate the items, and a setting needing quoting owns its own split.
 */
export function splitCommaList(raw: string | undefined | null): string[] {
  if (!raw) return [];
  return raw
    .split(',')
    .map((s) => s.trim())
    .filter((s) => s.length > 0);
}
