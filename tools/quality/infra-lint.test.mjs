import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { test } from 'node:test';
import { findings, lint, listFiles, main, parseActionlint, parseHadolint, parseShellcheck, parseZizmor, targets } from './infra-lint.mjs';

// Outputs captured from shellcheck 0.11.0, actionlint 1.7.12, zizmor 1.32.0 and hadolint 2.15.1.
const SHELLCHECK = JSON.stringify({
  comments: [
    { file: 'a.sh', line: 2, column: 6, level: 'info', code: 2086, message: 'Double quote to prevent globbing and word splitting.' },
    { file: 'b.sh', line: 4, column: 1, level: 'error', code: 1072, message: 'Unexpected keyword/token.' },
  ],
});
const ACTIONLINT = JSON.stringify([
  { message: '"github.event.head_commit.message" is potentially untrusted.', filepath: '.github/workflows/x.yml', line: 7, column: 23, kind: 'expression' },
]);
const zizmorResult = (ident, severity, locations) => ({ ident, desc: `${ident} description`, determinations: { confidence: 'High', severity, persona: 'Regular' }, locations });
const location = (kind, row, annotation) => ({
  symbolic: { key: { Local: { verbatim_path: './.github/workflows/x.yml' } }, annotation, kind },
  concrete: { location: { start_point: { row, column: 8 } } },
});
const ZIZMOR = JSON.stringify([
  zizmorResult('template-injection', 'High', [location('Hidden', 6), location('Primary', 6, 'may expand into attacker-controllable code'), location('Related', 2)]),
  zizmorResult('artipacked', 'Medium', [location('Primary', 5, 'does not set persist-credentials: false')]),
]);
const HADOLINT = JSON.stringify([
  { code: 'DL3006', column: 1, file: 'app/Dockerfile', level: 'warning', line: 1, message: 'Always tag the version of an image explicitly' },
  { code: 'SC2155', column: 1, file: 'app/Dockerfile', level: 'error', line: 9, message: 'Declare and assign separately to avoid masking return values.' },
]);

test('each tool reads only its own tracked files', () => {
  const files = [
    'app/entrypoint.sh',
    'app/gradlew',
    'tools/x.sh.md',
    '.github/workflows/ci.yml',
    '.github/workflows/nested/x.yml',
    '.github/ISSUE_TEMPLATE/bug.yml',
    'app/Dockerfile',
    'console/Dockerfile',
    'docker/Dockerfile.dev',
    'docker/runner.Dockerfile',
    'app/app/src/test/kotlin/homer/app/fixtures/DockerfileStages.kt',
  ];
  assert.deepEqual(targets(files), {
    shell: ['app/entrypoint.sh'],
    workflows: ['.github/workflows/ci.yml'],
    dockerfiles: ['app/Dockerfile', 'console/Dockerfile', 'docker/Dockerfile.dev', 'docker/runner.Dockerfile'],
  });
});

test('shellcheck: an error is major, anything else minor, each named by its SC code', () => {
  assert.deepEqual(parseShellcheck(SHELLCHECK), [
    { path: 'a.sh', line: 2, severity: 'minor', message: 'shellcheck SC2086: Double quote to prevent globbing and word splitting.' },
    { path: 'b.sh', line: 4, severity: 'major', message: 'shellcheck SC1072: Unexpected keyword/token.' },
  ]);
});

test('actionlint: every finding is major, named by its kind', () => {
  assert.deepEqual(parseActionlint(ACTIONLINT), [
    { path: '.github/workflows/x.yml', line: 7, severity: 'major', message: 'actionlint expression: "github.event.head_commit.message" is potentially untrusted.' },
  ]);
});

test('zizmor: one finding at the primary location, 1-based, high severity major', () => {
  assert.deepEqual(parseZizmor(ZIZMOR), [
    {
      path: '.github/workflows/x.yml',
      line: 7,
      severity: 'major',
      message: 'zizmor template-injection: template-injection description (may expand into attacker-controllable code)',
    },
    { path: '.github/workflows/x.yml', line: 6, severity: 'minor', message: 'zizmor artipacked: artipacked description (does not set persist-credentials: false)' },
  ]);
});

test('hadolint: its own rules and its ShellCheck findings', () => {
  assert.deepEqual(
    parseHadolint(HADOLINT).map((f) => [f.line, f.severity, f.message.split(':')[0]]),
    [
      [1, 'minor', 'hadolint DL3006'],
      [9, 'major', 'hadolint SC2155'],
    ],
  );
});

test('clean output from every tool is no finding', () => {
  assert.deepEqual([parseShellcheck('{"comments":[]}'), parseActionlint('[]'), parseZizmor('[]'), parseHadolint('[]')].flat(), []);
});

const fakeSpawn = (outputs, calls = []) => (tool, args) => {
  calls.push([tool, ...args]);
  return { status: outputs[tool].status, stdout: outputs[tool].stdout, stderr: outputs[tool].stderr ?? '' };
};

test('every tool runs on its files and the findings come back sorted by path and line', () => {
  const calls = [];
  const spawn = fakeSpawn(
    {
      shellcheck: { status: 1, stdout: SHELLCHECK },
      actionlint: { status: 1, stdout: ACTIONLINT },
      zizmor: { status: 0, stdout: ZIZMOR },
      hadolint: { status: 0, stdout: HADOLINT },
    },
    calls,
  );
  const found = findings(['a.sh', '.github/workflows/x.yml', 'app/Dockerfile'], spawn);
  assert.deepEqual(calls.map((c) => [c[0], c.at(-1)]), [
    ['shellcheck', './a.sh'],
    ['actionlint', './.github/workflows/x.yml'],
    ['zizmor', './.github/workflows/x.yml'],
    ['hadolint', './app/Dockerfile'],
  ]);
  assert.ok(calls.find((c) => c[0] === 'zizmor').includes('--offline'), 'zizmor must not depend on the network');
  assert.deepEqual(
    found.map((f) => `${f.path}:${f.line}`),
    ['.github/workflows/x.yml:6', '.github/workflows/x.yml:7', '.github/workflows/x.yml:7', 'a.sh:2', 'app/Dockerfile:1', 'app/Dockerfile:9', 'b.sh:4'],
  );
});

test('a file name never reaches a tool as an option', () => {
  const calls = [];
  const spawn = fakeSpawn({ shellcheck: { status: 0, stdout: '{"comments":[]}' }, hadolint: { status: 0, stdout: '[]' } }, calls);
  findings(['--norc.sh', '-o.Dockerfile'], spawn);
  assert.deepEqual(calls.map((c) => c.at(-1)), ['./--norc.sh', './-o.Dockerfile']);
});

test('a tool with no file to read does not run', () => {
  const calls = [];
  assert.deepEqual(findings(['README.md'], fakeSpawn({}, calls)), []);
  assert.deepEqual(calls, []);
});

test('a missing tool, an error exit or unreadable output fails the check instead of reading as clean', () => {
  const parse = () => [];
  assert.throws(() => lint('hadolint', [], [0], parse, () => ({ error: Object.assign(new Error('spawn'), { code: 'ENOENT' }) })), /hadolint did not run \(ENOENT\)/);
  assert.throws(() => lint('shellcheck', [], [0, 1], parse, () => ({ status: 2, stdout: '', stderr: 'bad option' })), /shellcheck exited 2: bad option/);
  assert.throws(() => lint('actionlint', [], [0, 1], parseActionlint, () => ({ status: 1, stdout: 'not json', stderr: '' })), /actionlint wrote output this check cannot read/);
  assert.throws(() => lint('zizmor', [], [0], parseZizmor, () => ({ status: 14, stdout: '[]', stderr: '' })), /zizmor exited 14/);
});

/** A scratch repository: tracked a.sh, a workflow and a Dockerfile; gone.sh tracked but deleted; new.sh untracked; build/ ignored. */
function repository() {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'infra-lint-'));
  const git = (...args) => execFileSync('git', ['-c', 'user.name=t', '-c', 'user.email=t@t', ...args], { cwd: root, encoding: 'utf8' });
  const write = (file, text) => {
    fs.mkdirSync(path.dirname(path.join(root, file)), { recursive: true });
    fs.writeFileSync(path.join(root, file), text);
  };
  git('init', '-q');
  write('.gitignore', 'build/\n');
  write('a.sh', '#!/bin/sh\necho $1\n');
  write('gone.sh', '#!/bin/sh\n');
  write('.github/workflows/ci.yml', 'on: push\n');
  write('app/Dockerfile', 'FROM alpine\n');
  git('add', '.');
  git('commit', '-q', '-m', 'base');
  fs.rmSync(path.join(root, 'gone.sh'));
  write('new.sh', '#!/bin/sh\n');
  write('build/out.sh', '#!/bin/sh\n');
  return root;
}

test('listFiles: tracked and untracked files, not ignored ones or those the working tree deleted', () => {
  const root = repository();
  try {
    assert.deepEqual(listFiles(root).sort(), ['.github/workflows/ci.yml', '.gitignore', 'a.sh', 'app/Dockerfile', 'new.sh']);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test('main runs every tool in the repository and writes the three report files with the counts it read', () => {
  const root = repository();
  const out = path.join(root, 'build', 'report');
  const calls = [];
  const spawn = (tool, args, options) => {
    calls.push({ tool, args, cwd: options.cwd });
    const stdout = { shellcheck: SHELLCHECK.replace('"b.sh"', '"a.sh"'), actionlint: '[]', zizmor: '[]', hadolint: '[]' }[tool];
    return { status: tool === 'shellcheck' ? 1 : 0, stdout, stderr: '' };
  };
  try {
    assert.match(main([out], { cwd: root, spawn }), /^Shell, workflow and Dockerfile lint: 2 finding\(s\)\n/);
    assert.deepEqual(calls.map((c) => c.tool), ['shellcheck', 'actionlint', 'zizmor', 'hadolint']);
    assert.ok(calls.every((c) => c.cwd === root), 'every tool runs in the repository');
    assert.deepEqual(calls[0].args.filter((a) => a.endsWith('.sh')).sort(), ['./a.sh', './new.sh'], 'no deleted or ignored script');
    assert.equal(fs.readFileSync(path.join(out, 'infra-lint-summary.txt'), 'utf8'), 'findings=2\nshell-scripts=2\nworkflows=1\ndockerfiles=1\n');
    assert.match(fs.readFileSync(path.join(out, 'infra-lint-report.txt'), 'utf8'), /^a\.sh:2  shellcheck SC2086: /m);
    const quality = JSON.parse(fs.readFileSync(path.join(out, 'gl-code-quality-infra-lint.json'), 'utf8'));
    assert.deepEqual(
      quality.map((q) => [q.check_name, q.location.path, q.location.lines.begin, q.severity]),
      [
        ['infra-lint', 'a.sh', 2, 'minor'],
        ['infra-lint', 'a.sh', 4, 'major'],
      ],
    );
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test('main writes no report when a tool fails', () => {
  const root = repository();
  const out = path.join(root, 'build', 'report');
  try {
    assert.throws(() => main([out], { cwd: root, spawn: () => ({ error: Object.assign(new Error('spawn'), { code: 'ENOENT' }) }) }), /shellcheck did not run/);
    assert.ok(!fs.existsSync(path.join(out, 'infra-lint-summary.txt')), 'a missing report is what fails the gate');
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});
