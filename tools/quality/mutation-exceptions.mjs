/**
 * Accepted mutation-test exceptions (ADR-0082): a reviewed JSON file per stack mapping an exception key, with no line number, to the reason its surviving mutant is accepted; not a baseline.
 * Key forms and the `{ "reason", "count" }` entry of an `<inline>` key: docs/quality-checks.md. Only undetected mutants are offered to `applyExceptions`.
 * An entry matching no mutant in a full run is itself a finding.
 */
import fs from 'node:fs';
import path from 'node:path';

export const BACKEND_EXCEPTIONS = 'tools/quality/mutation-exceptions/backend.json';
export const CONSOLE_EXCEPTIONS = 'tools/quality/mutation-exceptions/console.json';

const INLINE = '<inline>';
const nonEmpty = (text) => typeof text === 'string' && text.trim() !== '';
const isObject = (value) => value !== null && typeof value === 'object' && !Array.isArray(value);

// An `<inline>` key matches every inlined mutant of its description in the method, so its entry also
// pins how many it covers: `{ "reason": "…", "count": N }`.
function checkInline(file, key, entry) {
  if (!isObject(entry) || !nonEmpty(entry.reason) || Object.keys(entry).some((field) => field !== 'reason' && field !== 'count')) {
    throw new Error(`${file}: an <inline> exception needs { "reason": "…", "count": N }: ${key}`);
  }
  if (!Number.isInteger(entry.count) || entry.count < 1) throw new Error(`${file}: an <inline> exception needs a whole count of at least 1: ${key}`);
}

/**
 * The exceptions of `file`: key → reason, or key → `{ reason, count }` for a key ending in `<inline>`
 * (and only there); `{}` for a missing file. Throws naming the key of an entry not in its form.
 */
export function loadExceptions(file) {
  if (!fs.existsSync(file)) return {};
  const exceptions = JSON.parse(fs.readFileSync(file, 'utf8'));
  if (!isObject(exceptions)) throw new Error(`${file}: expected a JSON object of key → reason`);
  for (const [key, entry] of Object.entries(exceptions)) {
    if (key.endsWith(INLINE)) checkInline(file, key, entry);
    else if (!nonEmpty(entry)) throw new Error(`${file}: accepted exception needs a non-empty reason: ${key}`);
  }
  return exceptions;
}

const reasonOf = (entry) => (isObject(entry) ? entry.reason : entry);

/**
 * Splits `mutants` by `keyOf` into `kept` and `accepted` (`{ mutant, key, reason }`). Only for a `full` run, since a changed-lines run sees too few mutants:
 * `stale` lists the keys matching no mutant, `miscounted` (`{ key, matched, expected }`) the entries whose `count` differs from their matches, all still accepted.
 */
export function applyExceptions(mutants, keyOf, exceptions, { full }) {
  const kept = [];
  const accepted = [];
  const matched = new Map();
  for (const mutant of mutants) {
    const key = keyOf(mutant);
    if (Object.hasOwn(exceptions, key)) {
      matched.set(key, (matched.get(key) ?? 0) + 1);
      accepted.push({ mutant, key, reason: reasonOf(exceptions[key]) });
    } else {
      kept.push(mutant);
    }
  }
  if (!full) return { kept, accepted, stale: [], miscounted: [] };
  const stale = Object.keys(exceptions).filter((key) => !matched.has(key));
  const miscounted = Object.entries(exceptions)
    .filter(([key, entry]) => isObject(entry) && matched.has(key) && matched.get(key) !== entry.count)
    .map(([key, entry]) => ({ key, matched: matched.get(key), expected: entry.count }));
  return { kept, accepted, stale, miscounted };
}

/** One finding per stale key, on the exceptions file. */
export const staleFindings = (stale, file) => stale.map((key) => ({ path: file, line: 1, message: `stale accepted exception: ${key}`, severity: 'minor' }));

/** One finding per miscounted entry (`applyExceptions`), on the exceptions file. */
export const countFindings = (miscounted, file) =>
  miscounted.map(({ key, matched, expected }) => ({ path: file, line: 1, message: `accepted exception matched ${matched} mutants, expected ${expected}: ${key}`, severity: 'minor' }));

/** The accepted exceptions as report lines, `<key>  — <reason>`, one per line. */
export const formatAccepted = (accepted) => accepted.map(({ key, reason }) => `${key}  — ${reason.replace(/\s+/g, ' ').trim()}\n`).join('');

/** Writes `<check>-accepted.txt` into `dir`. */
export function writeAccepted(dir, check, accepted) {
  fs.mkdirSync(dir, { recursive: true });
  fs.writeFileSync(path.join(dir, `${check}-accepted.txt`), formatAccepted(accepted));
}
