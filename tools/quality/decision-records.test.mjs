import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { test } from 'node:test';
import { COLUMNS, check, tableAt, citationsIn, gradleVersions, main, parseRegistry, parseRisks, reads, resolveSource, rowFindings } from './decision-records.mjs';

const HEADER = `| ${COLUMNS.join(' | ')} |\n|---|---|---|---|---|---|\n`;
const registry = (...rows) => `# Workaround registry\n\n${HEADER}${rows.map((r) => `| ${r.join(' | ')} |\n`).join('')}`;
const risks = (...ids) =>
  `# Security\n\n## Accepted risks\n\nIntro.\n\n| ID | Finding | Where | Decision | Why accepted |\n|---|---|---|---|---|\n${ids.map((id) => `| ${id} | f | w | d | y |\n`).join('')}\n## Next\n`;
const REPO = {
  'app/gradle/libs.versions.toml': '[versions]\nktor = "3.5.0"\n# a comment\nokhttp = "5.4.0"\n\n[libraries]\nfake = "9.9.9"\n',
  'console/package.json': JSON.stringify({ dependencies: { next: '^16.1.0' }, devDependencies: { vitest: '~4.0.2', eslint: '9.1.0', range: '>=1', wild: '1.x', local: 'workspace:*' } }),
  'clients/python/requirements.lock': 'aiosqlite==0.22.1 \\\n    --hash=sha256:00\nlanggraph-checkpoint==3.0.1 \\\ntomli==2.2.1;python_version < "3.11"\nuvloop==0.21.0 ; sys_platform != "win32"\n',
  'app/Dockerfile': 'FROM eclipse-temurin:25-jdk-alpine-3.23@sha256:aa AS builder\nFROM slim AS runner\n',
  'console/Dockerfile': 'FROM node:24-alpine@sha256:bb AS deps\nFROM node:24-alpine@sha256:bb AS runner\n',
  'app/e2e/Dockerfile': 'FROM eclipse-temurin:25-jre-alpine-3.23@sha256:cc\n',
  '.github/workflows/ci.yml': 'jobs:\n  a:\n    env:\n      SHELLCHECK_VERSION: 0.11.0\n      QUOTED_VERSION: "1.2.3"  # pinned\n      DOCS_VERSION: next\n',
  '.github/workflows/nightly.yml': 'env:\n  SHELLCHECK_VERSION: 0.11.0\n  DOCS_VERSION: ${{ github.ref_name }}\n',
};
const repo = (extra = {}) => {
  const all = { ...REPO, ...extra };
  return { read: (file) => all[file] ?? null, files: Object.keys(all) };
};
const row = (overrides = {}) => ({ line: 5, id: 'W-001', upstream: 'KTOR-9621', source: 'gradle:ktor', version: '3.5.0', exit: 'x', where: [], ...overrides });

test('each source form resolves to the version it names', () => {
  const context = repo();
  assert.deepEqual(resolveSource('gradle:ktor', context), { version: '3.5.0' });
  assert.deepEqual(resolveSource('npm:next', context), { version: '16.1.0' });
  assert.deepEqual(resolveSource('npm:vitest', context), { version: '4.0.2' });
  assert.deepEqual(resolveSource('npm:eslint', context), { version: '9.1.0' });
  assert.deepEqual(resolveSource('pip:aiosqlite', context), { version: '0.22.1' });
  assert.deepEqual(resolveSource('pip:Langgraph_Checkpoint', context), { version: '3.0.1' });
  assert.deepEqual(resolveSource('pip:tomli', context), { version: '2.2.1' }, 'a marker with no space before the ;');
  assert.deepEqual(resolveSource('pip:uvloop', context), { version: '0.21.0' });
  assert.deepEqual(resolveSource('image:node', context), { version: '24-alpine' });
  assert.deepEqual(resolveSource('ci:SHELLCHECK_VERSION', context), { version: '0.11.0' });
  assert.deepEqual(resolveSource('ci:QUOTED_VERSION', context), { version: '1.2.3' });
});

test('gradleVersions reads only the [versions] table', () => {
  assert.deepEqual(gradleVersions(REPO['app/gradle/libs.versions.toml']), { ktor: '3.5.0', okhttp: '5.4.0' });
});

test('gradleVersions reads a rich version by its strictly, else its require', () => {
  const catalog =
    '[versions]\nokhttp = { strictly = "5.4.0" }\nokio = { require = "3.17.0", prefer = "3.17.1" }\n' +
    'both = { require = "1.0", strictly = "2.0" }\nloose = { prefer = "1.0" }\nspaced={strictly="4.0"}\n\n[libraries]\nx = { strictly = "9" }\n';
  assert.deepEqual(gradleVersions(catalog), { okhttp: '5.4.0', okio: '3.17.0', both: '2.0', spaced: '4.0' });
  const context = repo({ 'app/gradle/libs.versions.toml': catalog });
  assert.deepEqual(resolveSource('gradle:okhttp', context), { version: '5.4.0' });
  assert.ok(resolveSource('gradle:loose', context).error, 'a rich version with no strictly or require names no version');
});

test('a source that names nothing, or several versions, or an unknown form does not resolve', () => {
  const context = repo();
  for (const source of ['gradle:fake', 'gradle:__proto__', 'npm:react', 'npm:__proto__', 'npm:toString', 'pip:aiosqlite-extra', 'image:alpine', 'ci:NOPE_VERSION', 'maven:ktor', 'ktor']) {
    assert.ok(resolveSource(source, context).error, source);
  }
  assert.match(resolveSource('image:eclipse-temurin', context).error, /ambiguous: 25-jdk-alpine-3\.23, 25-jre-alpine-3\.23/);
  assert.match(resolveSource('npm:next', repo({ 'console/package.json': '{' })).error, /does not parse/);
  assert.ok(resolveSource('npm:next', repo({ 'console/package.json': 'null' })).error);
});

test('an npm spec that is no exact version is a finding, not the version', () => {
  for (const [name, spec] of [['range', '>=1'], ['wild', '1.x'], ['local', 'workspace:*']]) {
    assert.deepEqual(resolveSource(`npm:${name}`, repo()), { error: `console/package.json pins ${name} to no exact version: "${spec}"` });
  }
});

test('a ci value is read to the end of its line, so an ambiguity prints each value whole', () => {
  assert.deepEqual(resolveSource('ci:DOCS_VERSION', repo()), { error: 'ambiguous: next, ${{ github.ref_name }} in .github/workflows' });
});

test('a row whose watched version moved fails and asks for the exit condition to be re-checked', () => {
  const [moved] = rowFindings(row({ version: '3.4.0' }), repo());
  assert.equal(moved.path, 'docs/workarounds.md');
  assert.equal(moved.line, 5);
  assert.match(moved.message, /W-001: gradle:ktor is now 3\.5\.0, not 3\.4\.0: re-check the exit condition/);
  assert.deepEqual(rowFindings(row(), repo()), []);
});

test('an unresolvable source fails as a finding, never a throw', () => {
  const [finding] = rowFindings(row({ source: 'gradle:missing' }), repo());
  assert.match(finding.message, /W-001: source gradle:missing does not resolve/);
});

test('a Where file must exist and carry the id', () => {
  const context = repo({ 'app/A.kt': '// Pinned (W-001).\n', 'app/B.kt': '// Pinned (W-0011).\n' });
  assert.deepEqual(rowFindings(row({ where: ['app/A.kt'] }), context), []);
  const messages = rowFindings(row({ where: ['app/A.kt', 'app/B.kt', 'app/Gone.kt'] }), context).map((f) => f.message);
  assert.deepEqual(messages, ['W-001: app/B.kt does not cite W-001', 'W-001: Where names app/Gone.kt, which is not in the repository']);
});

test('parseRegistry reads the rows, unquotes cells and splits Where; a malformed id, an empty cell or a duplicate fails', () => {
  const text = registry(
    ['`W-001`', '[KTOR-9621](https://example.test)', '`gradle:ktor`', '`3.5.0`', 'ships', '`app/A.kt`, app/B.kt'],
    ['W-1', 'x', 'gradle:ktor', '3.5.0', 'ships', 'app/A.kt'],
    ['W-002', 'x', 'gradle:ktor', '3.5.0', '', 'app/A.kt'],
    ['W-001', 'x', 'gradle:ktor', '3.5.0', 'ships', 'app/A.kt'],
  );
  const { rows, findings } = parseRegistry(text);
  assert.deepEqual(rows[0], { line: 5, id: 'W-001', upstream: '[KTOR-9621](https://example.test)', source: 'gradle:ktor', version: '3.5.0', exit: 'ships', where: ['app/A.kt', 'app/B.kt'] });
  assert.deepEqual(
    findings.map((f) => `${f.line} ${f.message}`),
    ['6 "W-1" is no W-NNN id (three digits)', '7 W-002: empty Exit condition; every column is required', '8 W-001 is used twice: an id is unique and never reused'],
  );
  assert.deepEqual(parseRegistry(registry()).rows, []);
  assert.match(parseRegistry('# no table\n').findings[0].message, /no table with the columns ID \| Upstream/);
  assert.match(parseRegistry(null).findings[0].message, /docs\/workarounds\.md is missing/);
});

test('a Where cell of several backticked paths, or of quoted and unquoted paths mixed, splits into each path', () => {
  const where = (cell) => parseRegistry(registry(['W-001', 'x', 'gradle:ktor', '3.5.0', 'ships', cell])).rows[0].where;
  assert.deepEqual(where('`app/A.kt`, `app/B.kt`'), ['app/A.kt', 'app/B.kt']);
  assert.deepEqual(where('`app/A.kt`,app/B.kt, `console/c.ts`'), ['app/A.kt', 'app/B.kt', 'console/c.ts']);
});

const ROW = '| W-001 | x | gradle:ktor | 3.5.0 | ships | app/A.kt |';
const registryLines = (...lines) => `# Workaround registry\n\n| ${COLUMNS.join(' | ')} |\n${lines.join('\n')}\n`;
const shapeOf = (text) => {
  const { rows, findings } = parseRegistry(text);
  return { rows: rows.map((r) => `${r.line} ${r.id}`), findings: findings.map((f) => `${f.line} ${f.message}`) };
};

test('a registry table without its separator row is a finding, and its first row is still read', () => {
  assert.deepEqual(shapeOf(registryLines(ROW, ROW.replace('W-001', 'W-002'))), {
    rows: ['4 W-001', '5 W-002'],
    findings: ['3 the table has no `|---|` separator row of its width under its header'],
  });
  assert.deepEqual(shapeOf(registryLines('|---|---|', ROW)).findings, [
    '3 the table has no `|---|` separator row of its width under its header',
    '4 a row of 2 cells in a table of 6 columns',
    '4 "---" is no W-NNN id (three digits)',
    '4 ---: empty Source, Version, Exit condition, Where; every column is required',
  ]);
});

test('a blank or stray line inside a table is a finding, and the rows after it are still read', () => {
  const sep = '|---|---|---|---|---|---|';
  assert.deepEqual(shapeOf(registryLines(sep, ROW, '', '', ROW.replace('W-001', 'W-2'))), {
    rows: ['5 W-001', '8 W-2'],
    findings: ['6 a blank line interrupts the table, which Markdown ends here', '8 "W-2" is no W-NNN id (three digits)'],
  });
  assert.deepEqual(shapeOf(registryLines(sep, ROW, 'Some prose.', ROW.replace('W-001', 'W-002'))).findings, ['6 a line that is no row interrupts the table, which Markdown ends here']);
  assert.deepEqual(shapeOf(`${registryLines(sep, ROW)}\n## Later\n\n| a | b |\n`).findings, [], 'a table under the next heading is another table');
});

test('a row shorter or longer than the header is a finding, never a row read short or cut', () => {
  const sep = '|---|---|---|---|---|---|';
  assert.deepEqual(shapeOf(registryLines(sep, '| W-001 | x | gradle:ktor | 3.5.0 | ships |')).findings, [
    '5 a row of 5 cells in a table of 6 columns',
    '5 W-001: empty Where; every column is required',
  ]);
  assert.deepEqual(shapeOf(registryLines(sep, `${ROW} extra |`)).findings, ['5 a row of 7 cells in a table of 6 columns']);
});

test('tableAt reads to the last row before its end and flags only the first line of an interruption', () => {
  const lines = ['| a | b |', '|---|---|', '| 1 | 2 |', '', '', '| 3 | 4 |', '', 'after'];
  const table = tableAt(lines, 0, 'f.md');
  assert.deepEqual(table.rows.map((r) => r.line), [3, 6]);
  assert.deepEqual(table.findings.map((f) => `${f.path}:${f.line}`), ['f.md:4']);
  assert.deepEqual(tableAt(lines, 0, 'f.md', 4).findings, []);
});

test('a malformed id is reported and never reaches a regular expression', () => {
  const files = { 'docs/workarounds.md': registry(['W-(1', 'x', 'gradle:ktor', '3.5.0', 'ships', 'app/A.kt']), 'SECURITY.md': risks(), 'app/A.kt': 'val a = 1\n' };
  const { findings } = check(repo(files));
  assert.deepEqual(findings.map((f) => f.message), ['"W-(1" is no W-NNN id (three digits)']);
});

test('parseRisks wants an ID column and a unique AR-NN id on every row', () => {
  assert.deepEqual(parseRisks(risks('AR-01', 'AR-02')), { rows: [{ line: 9, id: 'AR-01' }, { line: 10, id: 'AR-02' }], findings: [] });
  const { findings } = parseRisks(risks('AR-01', '', 'AR-1', 'AR-01'));
  assert.deepEqual(
    findings.map((f) => `${f.path}:${f.line} ${f.message}`),
    [
      'SECURITY.md:10 accepted risk without an AR-NN id (two digits): ""',
      'SECURITY.md:11 accepted risk without an AR-NN id (two digits): "AR-1"',
      'SECURITY.md:12 AR-01 is used twice: an id is unique and never reused',
    ],
  );
  const noId = '## Accepted risks\n\n| Finding | Where |\n|---|---|\n| f | w |\n';
  assert.match(parseRisks(noId).findings[0].message, /must start with an ID column/);
  const noIdBroken = '## Accepted risks\n\n| Finding | Where |\n| f | w |\n';
  assert.deepEqual(parseRisks(noIdBroken).findings.map((f) => /must start with an ID column/.test(f.message)).sort(), [false, true], 'shape findings survive a missing ID column');
  assert.match(parseRisks('# Security\n').findings[0].message, /no accepted-risks table/);
});

test('an interrupted accepted-risks table is a finding, and its later rows are still checked', () => {
  const text = risks('AR-01').replace('| AR-01 | f | w | d | y |\n', '| AR-01 | f | w | d | y |\n\n| AR-1 | f | w | d | y |\n');
  const { rows, findings } = parseRisks(text);
  assert.deepEqual(rows.map((r) => r.id), ['AR-01', 'AR-1']);
  assert.deepEqual(findings.map((f) => `${f.line} ${f.message}`), [
    '10 a blank line interrupts the table, which Markdown ends here',
    '11 accepted risk without an AR-NN id (two digits): "AR-1"',
  ]);
  const unseparated = '## Accepted risks\n\n| ID | Finding |\n| AR-01 | f |\n';
  assert.deepEqual(parseRisks(unseparated).findings.map((f) => f.line), [3]);
  assert.deepEqual(parseRisks(unseparated).rows, [{ line: 4, id: 'AR-01' }]);
});

test('citationsIn reads ids in comments only, never in strings or code', () => {
  const source = 'const a = "W-009 AR-09";\n// Pinned (W-001), see AR-02.\nconst b = `AR-07`; /* W-003 */\n';
  assert.deepEqual(citationsIn('a.ts', source), [
    { id: 'W-001', line: 2 },
    { id: 'AR-02', line: 2 },
    { id: 'W-003', line: 3 },
  ]);
  assert.deepEqual(citationsIn('a.py', 'x = "W-001"  # AR-03\n'), [{ id: 'AR-03', line: 1 }]);
});

test('check: a resolved citation passes, an unresolved W or AR citation fails at path:line', () => {
  const files = {
    'docs/workarounds.md': registry(['W-001', 'KTOR-9621', 'gradle:ktor', '3.5.0', 'ships', 'app/A.kt']),
    'SECURITY.md': risks('AR-01'),
    'app/A.kt': 'val a = 1\n// Pinned (W-001); AR-01.\n',
    'console/b.ts': 'const s = "W-404";\n// see W-002\n// and AR-09\n',
  };
  const { findings, extra } = check(repo(files));
  assert.deepEqual(
    findings.map((f) => `${f.path}:${f.line} ${f.message}`),
    ['console/b.ts:2 cites W-002, which has no row in docs/workarounds.md', 'console/b.ts:3 cites AR-09, which is no accepted-risk id in SECURITY.md'],
  );
  assert.deepEqual(extra, { workarounds: 1, 'accepted-risks': 1, citations: 4 });
});

test('image:<name>@<stage> reads the FROM line of that stage only', () => {
  const dockerfile = 'FROM jdk:1@sha256:aa AS builder\nFROM jre:2@sha256:bb AS slim\nFROM jdk:3@sha256:cc AS other\n';
  const context = repo({ 'console/Dockerfile': dockerfile });
  assert.deepEqual(resolveSource('image:jre@slim', context), { version: '2' });
  assert.deepEqual(resolveSource('image:jdk@other', context), { version: '3' });
  assert.match(resolveSource('image:jdk', context).error, /ambiguous/);
  assert.match(resolveSource('image:jdk@none', context).error, /not found/);
});

test('image: reads Dockerfile, Dockerfile.<x> and <x>.Dockerfile, never a file whose name only starts with Dockerfile', () => {
  const context = (extra) => repo({ 'console/Dockerfile': '', 'app/e2e/Dockerfile': '', ...extra });
  const from = 'FROM python:3.12-slim@sha256:dd\n';
  for (const file of ['tools/Dockerfile', 'app/Dockerfile.runner', 'clients/python/langgraph.Dockerfile']) {
    assert.deepEqual(resolveSource('image:python', context({ [file]: from })), { version: '3.12-slim' }, file);
  }
  const fixtures = { 'app/app/src/test/kotlin/homer/app/fixtures/DockerfileStages.kt': '// a fixture\nFROM python:9@sha256:ee\n', 'docs/Dockerfiles.txt': 'FROM python:8@sha256:ff\n' };
  assert.deepEqual(resolveSource('image:python', context({ 'app/Dockerfile.runner': from, ...fixtures })), { version: '3.12-slim' });
  assert.ok(!reads('docs/Dockerfiles.txt'));
  assert.ok(reads('clients/python/langgraph.Dockerfile'));
});

test('reads covers the records, every watched version file and every source the comment policy reads', () => {
  for (const file of ['docs/workarounds.md', 'SECURITY.md', 'app/gradle/libs.versions.toml', 'console/package.json', 'clients/python/requirements.lock', 'console/Dockerfile', '.github/workflows/ci.yml', 'console/lib/a.ts', 'app/app/src/main/kotlin/A.kt']) {
    assert.ok(reads(file), file);
  }
  for (const file of ['docs/x.md', 'README.md', 'console/types/a.json']) assert.ok(!reads(file), file);
});

test('main writes the three report files from the repository at cwd', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'decision-records-'));
  try {
    const files = { 'docs/workarounds.md': registry(), 'SECURITY.md': risks('AR-01'), 'a.ts': '// AR-02\n' };
    for (const [file, text] of Object.entries(files)) {
      fs.mkdirSync(path.dirname(path.join(dir, file)), { recursive: true });
      fs.writeFileSync(path.join(dir, file), text);
    }
    const out = path.join(dir, 'out');
    const list = ['docs/workarounds.md', 'SECURITY.md', 'a.ts'];
    const report = main([out], { cwd: dir, files: list });
    assert.deepEqual(list, ['docs/workarounds.md', 'SECURITY.md', 'a.ts'], 'the caller\'s list is not sorted in place');
    assert.match(report, /1 finding\(s\)\na\.ts:1 {2}cites AR-02/);
    assert.match(fs.readFileSync(path.join(out, 'decision-records-summary.txt'), 'utf8'), /findings=1\nworkarounds=0\naccepted-risks=1\ncitations=1/);
    assert.ok(fs.existsSync(path.join(out, 'gl-code-quality-decision-records.json')));
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});
