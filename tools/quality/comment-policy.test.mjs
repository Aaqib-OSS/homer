import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { test } from 'node:test';
import { commentsOf, languageOf } from './comment-lexers.mjs';
import {
  AREAS,
  BUDGET_FILE,
  EXCLUDED,
  RULES,
  areaOf,
  blocksOf,
  countsOf,
  directiveOf,
  evaluate,
  isReasonText,
  main,
  parseBudgets,
  projectVersion,
  scanFile,
  scanned,
  staleBudget,
} from './comment-policy.mjs';

const VERSION = '1.43.2';
const rulesOf = (file, source, version = VERSION) => scanFile(file, source, version).map((o) => `${o.rule}@${o.line}`);
const only = (rule, file, source, version) => scanFile(file, source, version ?? VERSION).filter((o) => o.rule === rule);
const blocks = (file, source) => blocksOf(source, commentsOf(languageOf(file), source), languageOf(file));
const budgets = (value) => Object.fromEntries(RULES.map((rule) => [rule, Object.fromEntries(AREAS.map((area) => [area, value]))]));

test('areaOf puts every path into one budget area', () => {
  const cases = {
    'app/app/src/main/kotlin/A.kt': 'backend-main',
    'app/app/src/main/resources/scripts/homer-ask.sh': 'backend-main',
    'app/buildSrc/src/test/kotlin/T.kt': 'backend-main',
    'app/app/build.gradle.kts': 'backend-main',
    'app/config/detekt/detekt.yml': 'backend-main',
    'app/app/src/test/kotlin/ATest.kt': 'backend-test',
    'app/e2e/fixtures/agents/x/graph.py': 'backend-test',
    'app/architecture/build.gradle.kts': 'backend-test',
    'console/lib/a.ts': 'console',
    'example/agents/langgraph_demo/approval_flow.py': 'console',
    'clients/python/homer_langgraph/gates.py': 'python',
    '.github/workflows/ci.yml': 'infra',
    'app/Dockerfile': 'infra',
    'console/Dockerfile': 'infra',
    'docker-compose.yml': 'infra',
    'app/entrypoint.sh': 'infra',
    '.editorconfig': 'infra',
    'tools/quality/gate.mjs': 'tools',
    'site/scripts/md-links.mjs': 'tools',
  };
  for (const [file, area] of Object.entries(cases)) assert.equal(areaOf(file), area, file);
});

test('scanned reads hand-written fixtures and leaves out captured or generated files and kinds with no lexer', () => {
  for (const file of ['app/app/src/main/kotlin/A.kt', 'app/e2e/fixtures/agents/x/graph.py', 'app/e2e/fixtures/agents/echo/agent.yaml', 'app/architecture/src/test/resources/passthrough/Fixtures.kt', '.env.example', 'console/app/globals.css']) {
    assert.ok(scanned(file), file);
  }
  for (const file of [
    'plugins/homer-authoring/x.ts',
    'app/app/src/test/resources/claude/cli-2.1-stream.jsonl',
    'app/app/src/test/resources/llm/codex/success.jsonl',
    'app/gradle/wrapper/gradle-wrapper.properties',
    'clients/python/requirements.lock',
    'console/node_modules/a/index.js',
    'app/app/build/generated/A.kt',
    'README.md',
    'package-lock.json',
  ]) {
    assert.ok(!scanned(file), file);
  }
});

test('every file of the repository is either read or named in EXCLUDED', () => {
  const root = execFileSync('git', ['rev-parse', '--show-toplevel'], { cwd: import.meta.dirname, encoding: 'utf8' }).trim();
  const files = execFileSync('git', ['ls-files'], { cwd: root, encoding: 'utf8' }).trim().split('\n');
  assert.ok(files.includes('CHANGELOG.md'));
  const unnamed = files.filter((f) => !scanned(f) && !EXCLUDED.some(({ pattern }) => pattern.test(f)));
  assert.deepEqual(unnamed, [], 'add a lexer for these files, or name them in EXCLUDED with a reason');
});

test('a block is a run of comment-only line comments; a blank line, a trailing comment or another kind ends it', () => {
  const source = ['// one', '// two', '', '// three', 'val x = 1 // trailing', '// four', '/**', ' * Doc.', ' *', ' * More.', ' */'].join('\n');
  assert.deepEqual(
    blocks('A.kt', source).map((b) => [b.line, b.kind, b.lines.length]),
    [
      [1, 'line', 2],
      [4, 'line', 1],
      [5, 'line', 1],
      [6, 'line', 1],
      [7, 'doc', 2],
    ],
  );
});

test('length: a constraint comment holds 2 content lines, a contract 3; delimiters, blanks and directives do not count', () => {
  assert.deepEqual(rulesOf('A.kt', '// a\n// b\nval x = 1'), []);
  assert.deepEqual(rulesOf('A.kt', '// a\n// b\n// c\nval x = 1'), ['length@1']);
  assert.deepEqual(rulesOf('A.kt', '/**\n * a\n *\n * b\n * c\n */\nfun f() {}'), []);
  assert.deepEqual(rulesOf('A.kt', '/** a\n * b\n * c\n * d */\nfun f() {}'), ['length@1']);
  assert.deepEqual(rulesOf('a.py', '"""a\n\nb\nc\n"""\nx = 1'), []);
  assert.deepEqual(rulesOf('a.py', '"""a\nb\nc\nd"""\nx = 1'), ['length@1']);
  assert.deepEqual(rulesOf('run.sh', '# why one\n# why two\n# shellcheck disable=SC2086\necho $x'), ['suppression@3']);
});

test('citation: only ADR, CWE, W and AR ids; URLs, issue numbers, issue keys and review rounds are refused', () => {
  assert.deepEqual(rulesOf('a.ts', '// See ADR-0082, CWE-88, W-001 and AR-12; UTF-8 and SHA-256 are no keys.'), []);
  assert.deepEqual(rulesOf('a.ts', '// the `http://` scheme is no URL'), []);
  for (const comment of ['// fixed in #123', '// see https://example.com/a', '// upstream KTOR-9621', '// GH-310 tracks it', '// from the fix-round']) {
    assert.deepEqual(rulesOf('a.ts', comment), ['citation@1'], comment);
  }
  assert.deepEqual(rulesOf('a.ts', '// audit P1 #14'), ['citation@1', 'narration@1']);
});

test('shortcut-word: allowed only in a block that also cites an ADR, W or AR id or carries a removeIn marker', () => {
  assert.deepEqual(rulesOf('a.ts', '// A workaround for the parser.\nx()'), ['shortcut-word@1']);
  assert.deepEqual(rulesOf('a.ts', '// A workaround for the parser,\n// see W-012.\nx()'), []);
  assert.deepEqual(rulesOf('a.ts', '// Polls until done, ADR-0082.\nx()'), []);
  assert.deepEqual(rulesOf('a.ts', '// see W-012.\nx()\n// a hack\ny()'), ['shortcut-word@3']);
  assert.deepEqual(rulesOf('a.ts', '// for now\n// FOR NOW\nx()').length, 2);
  assert.deepEqual(rulesOf('a.ts', '// the compatible form, untilted\nx()'), []);
  assert.deepEqual(rulesOf('a.ts', '// Holds the lock until the request ends.\nx()'), []);
  assert.deepEqual(rulesOf('a.ts', '// Retries until KTOR-9621 is fixed.\nx()'), ['citation@1', 'shortcut-word@1']);
  assert.deepEqual(rulesOf('a.ts', '// Pinned until the base image carries the fix.\nx()'), ['shortcut-word@1']);
  assert.deepEqual(rulesOf('a.ts', '// Reads the map instead of the list.\nx()'), []);
  assert.deepEqual(rulesOf('a.ts', '// This deviates from the spec.\n// Kept for the time being.\nx()'), ['shortcut-word@1', 'shortcut-word@2']);
  assert.deepEqual(rulesOf('A.kt', '// removeIn 2.0.0: legacy config key\nval a = 1'), []);
  assert.deepEqual(rulesOf('a.sh', '# legacy config key\n# removeIn 2.0.0\necho'), []);
  assert.deepEqual(rulesOf('A.kt', '// removeIn 2.0.0\nval a = 1\n// legacy config key\nval b = 1'), ['shortcut-word@3']);
});

test('todo: an upper-case TODO, FIXME or XXX is never allowed; the lower-case word is', () => {
  assert.deepEqual(rulesOf('a.py', '# TODO: later\nx = 1'), ['todo@1']);
  assert.deepEqual(rulesOf('a.py', '# FIXME\n# XXX\nx = 1'), ['todo@1', 'todo@2']);
  assert.deepEqual(rulesOf('a.py', '# a todo list\nx = 1'), []);
});

test('narration: past-implementation phrases, but a purpose "used to" with another verb stays', () => {
  for (const comment of ['// the previous implementation', '// previously a map', '// used to hold the lock', '// audit #3 found it']) {
    assert.ok(rulesOf('A.kt', comment).includes('narration@1'), comment);
  }
  assert.deepEqual(rulesOf('A.kt', '// used to scope the reads'), []);
});

test('isReasonText needs three real characters and no bare to-do marker', () => {
  assert.ok(isReasonText(' why it stays '));
  assert.ok(!isReasonText(' -- '));
  assert.ok(!isReasonText('TODO'));
  assert.ok(!isReasonText('fixme:'));
  assert.ok(!isReasonText(' ok */'));
});

test('directiveOf reads each suppression directive and the reason beside it', () => {
  assert.deepEqual(directiveOf(' eslint-disable-next-line no-x -- the API needs it', false), { name: 'eslint-disable', reasoned: true });
  assert.deepEqual(directiveOf(' eslint-disable-next-line no-x, no-y', false), { name: 'eslint-disable', reasoned: false });
  assert.deepEqual(directiveOf(' @ts-expect-error jsdom ships no Worker', false), { name: '@ts-expect-error', reasoned: true });
  assert.deepEqual(directiveOf(' @ts-ignore', false), { name: '@ts-ignore', reasoned: false });
  assert.deepEqual(directiveOf(' noqa: BLE001', true), { name: 'noqa', reasoned: false });
  assert.deepEqual(directiveOf(' noqa: BLE001 - any failure is reported', true), { name: 'noqa', reasoned: true });
  assert.deepEqual(directiveOf(' long line on purpose  # noqa: E501', true), { name: 'noqa', reasoned: true });
  assert.deepEqual(directiveOf(' type: ignore[attr-defined]', true), { name: 'type: ignore', reasoned: false });
  assert.deepEqual(directiveOf(' shellcheck disable=SC2086 # split on purpose', true), { name: 'shellcheck disable', reasoned: true });
  assert.deepEqual(directiveOf(' hadolint ignore=DL3008', true), { name: 'hadolint ignore', reasoned: false });
  assert.deepEqual(directiveOf(' zizmor: ignore[self-repository] the workflow is ours', true), { name: 'zizmor: ignore', reasoned: true });
  assert.equal(directiveOf(' every eslint-disable states its reason', false), null);
});

test('suppression-reason: on its line, or a comment-only line directly above', () => {
  assert.deepEqual(rulesOf('a.ts', '// eslint-disable-next-line no-x -- why\nx()'), ['suppression@1']);
  assert.deepEqual(rulesOf('a.ts', '// the API needs it\n// eslint-disable-next-line no-x\nx()'), ['suppression@2']);
  assert.deepEqual(rulesOf('a.ts', '// eslint-disable-next-line no-x\nx()'), ['suppression@1', 'suppression-reason@1']);
  assert.deepEqual(rulesOf('a.ts', 'y() // a note on code\n// eslint-disable-next-line no-x\nx()'), ['suppression@2', 'suppression-reason@2']);
  assert.deepEqual(rulesOf('a.ts', '// eslint-disable-next-line no-a -- why\n// eslint-disable-next-line no-x\nx()'), ['suppression@1', 'suppression@2', 'suppression-reason@2']);
  assert.deepEqual(rulesOf('a.ts', 'const s = "// eslint-disable-next-line";'), []);
  assert.deepEqual(rulesOf('Dockerfile', '# the image needs root\n# hadolint ignore=DL3002\nUSER root'), ['suppression@2']);
});

test('a Kotlin @Suppress is code: its reason is on its line, inside it or on a // line above; in a string it is nothing', () => {
  const reasons = (source) => only('suppression-reason', 'A.kt', source).length;
  assert.equal(only('suppression', 'A.kt', '@Suppress("A", "B") // why\nfun f() {}').length, 1);
  assert.equal(reasons('@Suppress("A") // why\nfun f() {}'), 0);
  assert.equal(reasons('// why\n@Suppress("A")\nfun f() {}'), 0);
  assert.equal(reasons('@Suppress(\n    "A", // why\n)\nfun f() {}'), 0);
  assert.equal(reasons('@file:Suppress("A")\npackage x'), 1);
  assert.equal(reasons('@Suppress("A")\nfun f() {}'), 1);
  assert.equal(reasons('/** A doc. */\n@Suppress("A")\nfun f() {}'), 1);
  assert.equal(reasons('@Suppress("A") // TODO\nfun f() {}'), 1);
  assert.equal(reasons('@Suppress("A(")\nfun f() {} // next line\'s comment'), 1);
  assert.equal(only('suppression', 'A.kt', 'val s = "@Suppress("\n// @Suppress("A")').length, 0);
});

test('remove-in: a marker fails once the project version reaches it', () => {
  const sources = {
    'a.sh': '# removeIn 1.44.0\necho',
    'a.ts': '/** @removeIn 1.44.0 */\nexport const x = 1;',
    'A.kt': '// removeIn 1.44.0\nfun f() {}',
    'a.yml': 'a: 1 # removeIn 1.44.0\n',
  };
  for (const [file, source] of Object.entries(sources)) {
    assert.equal(only('remove-in', file, source, '1.43.9').length, 0, `${file} below`);
    assert.equal(only('remove-in', file, source, '1.44.0').length, 1, `${file} at`);
    assert.equal(only('remove-in', file, source, '2.0.0').length, 1, `${file} above`);
  }
  assert.equal(only('remove-in', 'A.kt', 'val s = "// removeIn 1.0.0"', '2.0.0').length, 0, 'a string is no marker');
  assert.equal(only('remove-in', 'A.kt', '@RemoveIn("1.0.0")\nfun f() {}', '2.0.0').length, 0, 'there is no annotation form');
});

test('projectVersion is the newest released heading, never [Unreleased]', () => {
  assert.equal(projectVersion('# Changelog\n\n## [Unreleased]\n\n## [1.43.2] - 2026-11-18\n\n## [1.43.1] - 2026-11-18\n'), '1.43.2');
  assert.throws(() => projectVersion('## [Unreleased]\n'), /no released/);
});

const occurrences = (n, rule = 'todo', area = 'console') => Array.from({ length: n }, (_, k) => ({ rule, area, path: `console/${k}.ts`, line: k + 1, message: `TODO ${k}` }));

test('a count over its budget lists every occurrence of that rule and area', () => {
  const findings = evaluate(occurrences(3), { ...budgets(0), todo: { ...budgets(0).todo, console: 2 } });
  assert.equal(findings.length, 3);
  assert.match(findings[0].message, /^todo: TODO 0 \(console: 3 over the budget of 2 in tools\/quality\/comment-policy-budgets\.json\)$/);
});

test('a count at its budget, or below it within the slack, passes', () => {
  assert.deepEqual(evaluate(occurrences(2), { ...budgets(0), todo: { ...budgets(0).todo, console: 2 } }), []);
  assert.deepEqual(evaluate(occurrences(1), { ...budgets(0), todo: { ...budgets(0).todo, console: 2 } }), []);
});

test('a budget more than its slack above the count is stale, at the budget file line', () => {
  const text = JSON.stringify({ length: { console: 40 }, todo: { 'backend-main': 0, console: 30 } }, null, 2);
  const findings = evaluate(occurrences(20), { ...budgets(0), todo: { ...budgets(0).todo, console: 30 } }, text);
  assert.deepEqual(findings, [
    { path: BUDGET_FILE, line: 7, severity: 'minor', message: 'todo.console: the budget is 30 but the count is 20; lower it to 20', stableKey: 'todo.console: stale' },
  ]);
  assert.equal(staleBudget(51, 51), null);
  assert.equal(staleBudget(0, 0), null);
  assert.equal(staleBudget(14, 13), null);
  assert.equal(staleBudget(14, 12), 12);
});

test('parseBudgets wants every rule and area, and no unknown key besides $-notes', () => {
  const complete = budgets(0);
  assert.deepEqual(parseBudgets(JSON.stringify({ $comment: 'x', ...complete })), { $comment: 'x', ...complete });
  assert.throws(() => parseBudgets(JSON.stringify({ ...complete, length: { console: 1 } })), /length\.backend-main/);
  assert.throws(() => parseBudgets(JSON.stringify({ ...complete, nope: {} })), /unknown rule nope/);
  assert.throws(() => parseBudgets(JSON.stringify({ ...complete, todo: { ...complete.todo, docs: 0 } })), /todo\.docs/);
  assert.throws(() => parseBudgets(JSON.stringify({ ...complete, todo: { ...complete.todo, console: -1 } })), /todo\.console/);
});

test('countsOf fills every rule and area', () => {
  const counts = countsOf(occurrences(2));
  assert.equal(counts.todo.console, 2);
  assert.equal(counts.length.python, 0);
  assert.deepEqual(Object.keys(counts), RULES);
});

test('main writes the report, and --counts prints the counts as the budget file holds them', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'comment-policy-'));
  try {
    const write = (file, text) => {
      fs.mkdirSync(path.dirname(path.join(dir, file)), { recursive: true });
      fs.writeFileSync(path.join(dir, file), text);
    };
    write('CHANGELOG.md', '## [Unreleased]\n## [1.0.0]\n');
    write('console/a.ts', '// TODO: later\n// removeIn 1.0.0\nexport const a = 1;\n');
    write('docs/x.md', '<!-- TODO -->\n');
    const files = ['CHANGELOG.md', 'console/a.ts', 'docs/x.md'];
    const counts = JSON.parse(main(['--counts'], { cwd: dir, files }));
    assert.equal(counts.todo.console, 1);
    assert.equal(counts['remove-in'].console, 1);
    write(BUDGET_FILE, JSON.stringify({ ...counts, todo: { ...counts.todo, console: 0 } }));
    const out = path.join(dir, 'out');
    const report = main([out], { cwd: dir, files });
    assert.match(report, /^Comment policy: contracts and constraints only: 1 finding\(s\)\nconsole\/a\.ts:1  todo: TODO in a comment/);
    assert.match(fs.readFileSync(path.join(out, 'comment-policy-summary.txt'), 'utf8'), /^findings=1\nfiles=1\nlength=0\n.*todo=1\n/s);
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});
