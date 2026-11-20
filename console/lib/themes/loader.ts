import { splitCommaList } from '@/lib/utils/list';

/**
 * A theme name becomes a class on `<html>` and the value `next-themes` stores,
 * so it is restricted to the same shape a built-in name has.
 */
const THEME_NAME_PATTERN = /^[a-z0-9-]+$/;

/**
 * A theme stylesheet path is root-relative and ends in `.css`: the console
 * loads themes from its **own origin only**, off files the operator put on disk.
 */
const THEME_PATH_PATTERN = /^\/[A-Za-z0-9._-]+(\/[A-Za-z0-9._-]+)*\.css$/;

/** The themes the console ships. `system` is a follower, not a theme file. */
export const BUILT_IN_THEME_NAMES = ['light', 'dark', 'terminal', 'win95'] as const;

/** Names an entry may not reuse — the four above, plus `next-themes`' follower. */
export const BUILT_IN_THEMES = ['light', 'dark', 'terminal', 'win95', 'system'] as const;

/** One `CONSOLE_THEMES` entry: a theme name and the stylesheet defining it. */
export interface ThemeEntry {
  name: string;
  path: string;
}

/**
 * Why a `CONSOLE_THEMES` entry (`name:/path.css`, split at the first colon) is
 * not usable, or `null`. Only same-origin stylesheets pass, including no
 * protocol-relative `//host` path, no `..` and no built-in name. See ADR-0069.
 */
export function checkThemeEntry(entry: string): string | null {
  const colon = entry.indexOf(':');
  const name = colon === -1 ? '' : entry.slice(0, colon);
  const path = colon === -1 ? '' : entry.slice(colon + 1);
  return themeNameProblem(name) ?? themePathProblem(path);
}

function themeNameProblem(name: string): string | null {
  if (!THEME_NAME_PATTERN.test(name)) {
    return `name must match ${String(THEME_NAME_PATTERN)} and be followed by ":<path>"`;
  }
  if ((BUILT_IN_THEMES as readonly string[]).includes(name)) {
    return `name ${name} is a built-in theme`;
  }
  return null;
}

function themePathProblem(path: string): string | null {
  if (/^[a-z][a-z0-9+.-]*:/i.test(path) || path.startsWith('//')) {
    return 'must be a same-origin path like /themes/name.css, not a URL';
  }
  if (path.includes('..')) return 'must not contain ".."';
  if (!THEME_PATH_PATTERN.test(path)) {
    return `path must match ${String(THEME_PATH_PATTERN)}`;
  }
  return null;
}

/** Parses an entry that already passed {@link checkThemeEntry}. */
export function toThemeEntry(entry: string): ThemeEntry {
  const colon = entry.indexOf(':');
  return { name: entry.slice(0, colon), path: entry.slice(colon + 1) };
}

export interface ThemeEntryParse {
  entries: ThemeEntry[];
  /** One message per dropped entry. The caller logs these; parsing stays pure. */
  rejected: string[];
}

/**
 * Validates `name:/path.css` entries into the ones the console can load. Drops
 * are reported, not thrown, and the first entry wins a duplicate name.
 */
export function resolveThemeEntries(entries: readonly string[]): ThemeEntryParse {
  const resolved: ThemeEntry[] = [];
  const rejected: string[] = [];
  const seen = new Set<string>();

  for (const entry of entries) {
    const reason = checkThemeEntry(entry);
    if (reason !== null) {
      rejected.push(`${entry}: ${reason}`);
      continue;
    }
    const parsed = toThemeEntry(entry);
    if (seen.has(parsed.name)) {
      rejected.push(`${entry}: name ${parsed.name} is already defined`);
      continue;
    }
    seen.add(parsed.name);
    resolved.push(parsed);
  }

  return { entries: resolved, rejected };
}

/**
 * {@link resolveThemeEntries} over the raw `CONSOLE_THEMES` env value, split with
 * `splitCommaList` rather than the plugins' parser so the grammars stay
 * independent. See ADR-0069.
 */
export function parseThemeEntries(raw: string | undefined | null): ThemeEntryParse {
  return resolveThemeEntries(splitCommaList(raw));
}

/** The two `CONSOLE_THEMES` names a `CONSOLE_THEME_MODES` value pairs up. */
export interface ThemeModes {
  light: string;
  dark: string;
}

export interface ThemeModesParse {
  /** The pair, or `null` when the setting is absent or was ignored. */
  modes: ThemeModes | null;
  /** Why the setting was ignored, or `null` when it was absent or accepted. */
  rejected: string | null;
}

/** Folds one `<mode>:<name>` segment into `named`; returns why it is malformed, or null. */
function addModeSegment(segment: string, named: Partial<ThemeModes>): string | null {
  const colon = segment.indexOf(':');
  if (colon === -1) return `"${segment}" is not "<light|dark>:<name>"`;
  const mode = segment.slice(0, colon).trim();
  const name = segment.slice(colon + 1).trim();
  if (mode !== 'light' && mode !== 'dark') return `"${mode}" is not "light" or "dark"`;
  if (named[mode] !== undefined) return `"${mode}" is named more than once`;
  if (name.length === 0) return `"${mode}" names no theme`;
  named[mode] = name;
  return null;
}

/** Why the collected pair cannot be used — a mode missing, or a name not loaded — or null. */
function pairRejection(named: Partial<ThemeModes>, names: readonly string[]): string | null {
  if (named.light === undefined || named.dark === undefined) {
    return 'needs both "light:<name>" and "dark:<name>"';
  }
  for (const name of [named.light, named.dark]) {
    if (!names.includes(name)) return `"${name}" is not a CONSOLE_THEMES entry`;
  }
  return null;
}

/**
 * Parses `CONSOLE_THEME_MODES` (`light:<name>,dark:<name>`) into the pair that
 * hides the built-ins; both names must be in `names`. All-or-nothing, and a
 * blank value is not a rejection. See ADR-0069.
 */
export function parseThemeModes(
  raw: string | undefined | null,
  names: readonly string[],
): ThemeModesParse {
  const segments = splitCommaList(raw);
  if (segments.length === 0) return { modes: null, rejected: null };

  const deny = (reason: string): ThemeModesParse => ({ modes: null, rejected: `${raw}: ${reason}` });
  const named: Partial<ThemeModes> = {};

  for (const segment of segments) {
    const reason = addModeSegment(segment, named);
    if (reason !== null) return deny(reason);
  }

  const pairReason = pairRejection(named, names);
  if (pairReason !== null) return deny(pairReason);
  const pair = named as ThemeModes;

  return { modes: { light: pair.light, dark: pair.dark }, rejected: null };
}

/**
 * The paths among `entries` absent from the document's loaded stylesheets, so a
 * theme file that 404s becomes a visible reason. Each path is matched as a
 * resolved URL, never by suffix. See ADR-0069.
 */
export function missingThemeStylesheets(
  entries: ThemeEntry[],
  loadedHrefs: readonly string[],
  base: string = typeof location !== 'undefined' ? location.href : 'http://localhost:3000/',
): string[] {
  const loaded = new Set(loadedHrefs);
  const resolvesToLoaded = (path: string) => {
    if (loaded.has(path)) return true;
    try {
      return loaded.has(new URL(path, base).href);
    } catch {
      return false;
    }
  };
  const missing: string[] = [];
  for (const { path } of entries) {
    if (!missing.includes(path) && !resolvesToLoaded(path)) missing.push(path);
  }
  return missing;
}
