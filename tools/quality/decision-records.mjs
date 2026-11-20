/**
 * decision-records (ADR-0117): every `W-NNN` row of the workaround registry watches a version that has not moved and
 * is cited where it says, every accepted risk in SECURITY.md has an `AR-NN` id, and every id a comment cites resolves.
 * Usage (repository root): node tools/quality/decision-records.mjs [out dir]
 */
import fs from 'node:fs';
import path from 'node:path';
import { DOCKERFILE, commentsOf, languageOf } from './comment-lexers.mjs';
import { lineIndex, scanned } from './comment-policy.mjs';
import { listFiles } from './infra-lint.mjs';
import { escapeRegExp, runMain, writeCheck } from './lib.mjs';

export const REGISTRY = 'docs/workarounds.md';
export const RISKS = 'SECURITY.md';
const TITLE = 'Workaround and accepted-risk records';
export const COLUMNS = ['ID', 'Upstream', 'Source', 'Version', 'Exit condition', 'Where'];
const GRADLE = 'app/gradle/libs.versions.toml';
const NPM = 'console/package.json';
const PIP = 'clients/python/requirements.lock';
const WORKFLOW = /^\.github\/workflows\/[^/]+\.ya?ml$/;
const CITATION = /\b(?:W|AR)-\d+\b/g;
const W_ID = /^W-\d{3}$/;
const EXACT = /^\d+(?:\.\d+)*(?:[-+][\w.+-]+)?$/;

/** Whether a change to `file` can change the check's result: the records, a watched version file, or a source it reads. */
export const reads = (file) =>
  [REGISTRY, RISKS, GRADLE, NPM, PIP].includes(file) || DOCKERFILE.test(file) || WORKFLOW.test(file) || scanned(file);

const finding = (file, line, message) => ({ path: file, line, severity: 'minor', message });
const unquote = (cell) => cell.replace(/^`(.*)`$/, '$1').trim();
const cellsOf = (line) =>
  line
    .trim()
    .replace(/^\||\|$/g, '')
    .split(/(?<!\\)\|/)
    .map((c) => c.trim());

const SEPARATOR = /^\|?(?:\s*:?-{3,}:?\s*\|)*\s*:?-{3,}:?\s*\|?$/;
const isRow = (line) => line.trim().startsWith('|');

/**
 * The table whose header is line index `at`, read up to its last `|` line before index `end`: `{ header, rows, findings }`.
 * A missing separator, a row of another width or a line that interrupts the table is a finding, never a dropped row.
 */
export function tableAt(lines, at, file, end = lines.length) {
  const header = cellsOf(lines[at]);
  const findings = [];
  const issue = (i, message) => findings.push(finding(file, i + 1, message));
  const separated = SEPARATOR.test(lines[at + 1]?.trim() ?? '') && cellsOf(lines[at + 1]).length === header.length;
  if (!separated) issue(at, 'the table has no `|---|` separator row of its width under its header');
  const first = separated ? at + 2 : at + 1;
  const last = lines.slice(0, end).findLastIndex((line, i) => i >= first && isRow(line));
  const rows = [];
  for (let i = first; i <= last; i++) {
    if (!isRow(lines[i])) {
      if (isRow(lines[i - 1])) issue(i, `${lines[i].trim() === '' ? 'a blank line' : 'a line that is no row'} interrupts the table, which Markdown ends here`);
      continue;
    }
    const cells = cellsOf(lines[i]);
    if (cells.length !== header.length) issue(i, `a row of ${cells.length} cells in a table of ${header.length} columns`);
    rows.push({ line: i + 1, cells });
  }
  return { header, rows, findings };
}

/** The registry's rows as `{ line, id, upstream, source, version, exit, where }`, and the findings of its shape. */
export function parseRegistry(text) {
  if (text === null) return { rows: [], findings: [finding(REGISTRY, 1, `${REGISTRY} is missing`)] };
  const lines = text.split('\n');
  const at = lines.findIndex((l) => cellsOf(l).join('|') === COLUMNS.join('|'));
  if (at < 0) return { rows: [], findings: [finding(REGISTRY, 1, `no table with the columns ${COLUMNS.join(' | ')}`)] };
  const heading = lines.findIndex((l, i) => i > at && /^#{1,6} /.test(l));
  const table = tableAt(lines, at, REGISTRY, heading < 0 ? lines.length : heading);
  const rows = table.rows.map(({ line, cells }) => {
    const [id = '', upstream = '', source = '', version = '', exit = ''] = cells.slice(0, 5).map(unquote);
    const where = (cells[5] ?? '').split(',').map((part) => unquote(part.trim())).filter(Boolean);
    return { line, id, upstream, source, version, exit, where };
  });
  const findings = [...table.findings];
  for (const row of rows) {
    const empty = COLUMNS.filter((_, k) => [row.id, row.upstream, row.source, row.version, row.exit, row.where.join()][k] === '');
    if (!W_ID.test(row.id)) findings.push(finding(REGISTRY, row.line, `"${row.id}" is no W-NNN id (three digits)`));
    if (empty.length > 0) findings.push(finding(REGISTRY, row.line, `${row.id}: empty ${empty.join(', ')}; every column is required`));
  }
  return { rows, findings: [...findings, ...duplicates(REGISTRY, rows)] };
}

function duplicates(file, rows) {
  const repeated = rows.filter((row, k) => row.id !== '' && rows.findIndex((r) => r.id === row.id) < k);
  return repeated.map((r) => finding(file, r.line, `${r.id} is used twice: an id is unique and never reused`));
}

/** The accepted-risk rows of SECURITY.md as `{ line, id }`, and the findings of their ids. */
export function parseRisks(text) {
  const lines = (text ?? '').split('\n');
  const start = lines.findIndex((l) => /^## Accepted risks\s*$/.test(l));
  const end = lines.findIndex((l, i) => i > start && /^## /.test(l));
  const at = start < 0 ? -1 : lines.findIndex((l, i) => i > start && (end < 0 || i < end) && l.trim().startsWith('|'));
  if (at < 0) return { rows: [], findings: [finding(RISKS, 1, 'no accepted-risks table under "## Accepted risks"')] };
  const table = tableAt(lines, at, RISKS, end < 0 ? lines.length : end);
  if (table.header[0] !== 'ID') return { rows: [], findings: [...table.findings, finding(RISKS, at + 1, 'the accepted-risks table must start with an ID column')] };
  const rows = table.rows.map(({ line, cells }) => ({ line, id: unquote(cells[0]) }));
  const malformed = rows.filter((r) => !/^AR-\d{2}$/.test(r.id)).map((r) => finding(RISKS, r.line, `accepted risk without an AR-NN id (two digits): "${r.id}"`));
  return { rows, findings: [...table.findings, ...malformed, ...duplicates(RISKS, rows)] };
}

/**
 * `[versions]` of a Gradle version catalog: a plain `key = "x"`, or a rich version's `strictly`, else its `require`.
 * A rich version with neither (only `prefer` or `reject`) names no version.
 */
export function gradleVersions(catalog) {
  const section = catalog.split('[versions]')[1]?.split('\n[')[0] ?? '';
  const rich = (table, field) => table.match(new RegExp(`(?:^|,)\\s*${field}\\s*=\\s*"([^"]+)"`))?.[1];
  const entries = [...section.matchAll(/^([A-Za-z0-9_.-]+)\s*=\s*(?:"([^"]+)"|\{([^}\n]*)\})/gm)].map((m) => [
    m[1],
    m[2] ?? rich(m[3], 'strictly') ?? rich(m[3], 'require'),
  ]);
  return Object.fromEntries(entries.filter(([, version]) => version !== undefined));
}

const pipName = (name) => name.toLowerCase().replace(/[-_.]+/g, '-');
const own = (object, key) => (object && Object.hasOwn(object, key) && typeof object[key] === 'string' ? object[key] : undefined);

// One version from the values found, or why none is.
function single(values, where) {
  const distinct = [...new Set(values)];
  if (distinct.length === 1) return { version: distinct[0] };
  return { error: distinct.length === 0 ? `not found in ${where}` : `ambiguous: ${distinct.join(', ')} in ${where}` };
}

const RESOLVERS = {
  gradle: (key, { read }) => single([own(gradleVersions(read(GRADLE) ?? ''), key)].filter(Boolean), `[versions] of ${GRADLE}`),
  npm(name, { read }) {
    let manifest;
    try {
      manifest = JSON.parse(read(NPM) ?? '');
    } catch {
      return { error: `${NPM} does not parse` };
    }
    const spec = own(manifest?.dependencies, name) ?? own(manifest?.devDependencies, name);
    if (spec === undefined) return single([], `${NPM} dependencies`);
    const version = spec.replace(/^[\^~]/, '');
    return EXACT.test(version) ? { version } : { error: `${NPM} pins ${name} to no exact version: "${spec}"` };
  },
  pip: (name, { read }) =>
    single(
      [...(read(PIP) ?? '').matchAll(/^([A-Za-z0-9_.-]+)==([^\s;\\]+)/gm)].filter((m) => pipName(m[1]) === pipName(name)).map((m) => m[2]),
      PIP,
    ),
  image(name, { read, files }) {
    const from = new RegExp(`^FROM\\s+(?:--platform=\\S+\\s+)?${escapeRegExp(name)}:([^@\\s]+)@sha256:`, 'gm');
    const tags = files.filter((f) => DOCKERFILE.test(f)).flatMap((f) => [...(read(f) ?? '').matchAll(from)].map((m) => m[1]));
    return single(tags, 'the FROM lines of the Dockerfiles');
  },
  ci(env, { read, files }) {
    const line = new RegExp(`^\\s*${escapeRegExp(env)}:[ \\t]*(.*?)[ \\t]*$`, 'gm');
    const value = (raw) => raw.replace(/\s+#.*$/, '').replace(/^(["'])(.*)\1$/, '$2');
    const values = files.filter((f) => WORKFLOW.test(f)).flatMap((f) => [...(read(f) ?? '').matchAll(line)].map((m) => value(m[1])));
    return single(values, '.github/workflows');
  },
};

/** The current version a `Source` cell names: `{ version }`, or `{ error }` when it does not resolve to one. */
export function resolveSource(source, context) {
  const [, kind, name] = /^([a-z]+):(.+)$/.exec(source) ?? [];
  if (!Object.hasOwn(RESOLVERS, kind ?? '')) return { error: `unknown source form; use gradle:, npm:, pip:, image: or ci:` };
  return RESOLVERS[kind](name, context);
}

/** Findings of one registry row: its source moved or does not resolve, or a `Where` file does not carry its id. */
export function rowFindings(row, context) {
  const found = [];
  const { version, error } = resolveSource(row.source, context);
  if (error) found.push(finding(REGISTRY, row.line, `${row.id}: source ${row.source} does not resolve (${error})`));
  else if (version !== row.version) {
    const message = `${row.id}: ${row.source} is now ${version}, not ${row.version}: re-check the exit condition, then update the row or remove the workaround`;
    found.push(finding(REGISTRY, row.line, message));
  }
  if (!W_ID.test(row.id)) return found;
  for (const file of row.where) {
    const text = context.files.includes(file) ? context.read(file) : null;
    if (text === null) found.push(finding(REGISTRY, row.line, `${row.id}: Where names ${file}, which is not in the repository`));
    else if (!new RegExp(`\\b${escapeRegExp(row.id)}\\b`).test(text)) found.push(finding(REGISTRY, row.line, `${row.id}: ${file} does not cite ${row.id}`));
  }
  return found;
}

/** Every `W-`/`AR-` id cited in a comment of `source`: `{ id, line }`; a string or code that holds one is not read. */
export function citationsIn(file, source) {
  const lineAt = lineIndex(source);
  return commentsOf(languageOf(file), source).flatMap((c) =>
    [...source.slice(c.bodyStart, c.bodyEnd).matchAll(CITATION)].map((m) => ({ id: m[0], line: lineAt(c.bodyStart + m.index) })),
  );
}

/** Findings of the whole check, from `read(file)` (null when missing) and the repository's `files`. */
export function check({ read, files }) {
  const registry = parseRegistry(read(REGISTRY));
  const risks = parseRisks(read(RISKS));
  const known = new Set([...registry.rows, ...risks.rows].map((r) => r.id));
  const context = { read, files };
  const cited = files.filter(scanned).flatMap((file) => citationsIn(file, read(file) ?? '').map((c) => ({ ...c, file })));
  const unresolved = cited
    .filter((c) => !known.has(c.id))
    .map((c) => finding(c.file, c.line, `cites ${c.id}, which ${c.id.startsWith('W-') ? `has no row in ${REGISTRY}` : `is no accepted-risk id in ${RISKS}`}`));
  const findings = [...registry.findings, ...registry.rows.flatMap((r) => rowFindings(r, context)), ...risks.findings, ...unresolved];
  return { findings, extra: { workarounds: registry.rows.length, 'accepted-risks': risks.rows.length, citations: cited.length } };
}

export function main([outDir = 'reports/quality/decision-records'], { cwd = process.cwd(), files = undefined } = {}) {
  const read = (file) => {
    const full = path.join(cwd, file);
    return fs.existsSync(full) ? fs.readFileSync(full, 'utf8') : null;
  };
  const { findings, extra } = check({ read, files: [...(files ?? listFiles(cwd))].sort() });
  return writeCheck(outDir, 'decision-records', TITLE, findings, extra);
}

runMain(import.meta, main);
