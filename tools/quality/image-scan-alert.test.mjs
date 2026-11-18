import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { test } from 'node:test';

const SCRIPT = path.join(path.dirname(fileURLToPath(import.meta.url)), 'image-scan-alert.sh');
const RUN_URL = 'https://github.com/livesport/homer/actions/runs/1';
const TITLE = 'Nightly image scan is failing';

const LIST = ['issue', 'list', '--state', 'open', '--search', `"${TITLE}" in:title`, '--json', 'number,title'];

// Runs the script with a `gh` stub on PATH that records every call, one line of NUL-separated
// arguments each; `issue list` then prints `openIssues` as JSON, or fails when it is null.
function run(openIssues) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'image-scan-alert-'));
  const calls = path.join(dir, 'calls');
  if (openIssues !== null) fs.writeFileSync(path.join(dir, 'issues.json'), JSON.stringify(openIssues));
  fs.writeFileSync(
    path.join(dir, 'gh'),
    `#!/bin/sh\nprintf '%s\\0' "$@" >>"${calls}"\necho >>"${calls}"\n` +
      `if [ "$1 $2" = "issue list" ]; then cat "${dir}/issues.json" || { echo 'HTTP 502' >&2; exit 1; }; fi\n`,
    { mode: 0o755 },
  );
  const result = spawnSync('/bin/sh', [SCRIPT], {
    env: { PATH: `${dir}:/usr/bin:/bin`, RUN_URL, GH_TOKEN: 'x', GH_REPO: 'livesport/homer' },
    encoding: 'utf8',
  });
  const recorded = fs.existsSync(calls) ? fs.readFileSync(calls, 'utf8') : '';
  return { status: result.status, stderr: result.stderr, calls: recorded.split('\n').filter(Boolean).map((line) => line.split('\0').filter(Boolean)) };
}

test('with no open issue it searches the open issues, then opens one naming the run and no package', () => {
  const { status, stderr, calls: [list, create, ...rest] } = run([]);
  assert.equal(status, 0, stderr);
  assert.deepEqual(list, LIST);
  assert.deepEqual(rest, []);
  assert.deepEqual(create.slice(0, 4), ['issue', 'create', '--title', TITLE]);
  assert.equal(create[4], '--body');
  assert.match(create[5], new RegExp(`The \`image-scan\` job of ${RUN_URL} failed`));
  assert.doesNotMatch(create[5], /@/);
});

test('with the issue open it comments on that issue and opens none', () => {
  const { status, stderr, calls } = run([{ number: 7, title: `${TITLE} again` }, { number: 12, title: TITLE }]);
  assert.equal(status, 0, stderr);
  assert.deepEqual(calls, [LIST, ['issue', 'comment', '12', '--body', `Still failing: ${RUN_URL}`]]);
});

test('an open issue whose title only contains the phrase does not count', () => {
  const { status, stderr, calls } = run([{ number: 7, title: `Re: ${TITLE}` }]);
  assert.equal(status, 0, stderr);
  assert.deepEqual(calls.map((c) => c.slice(0, 2)), [['issue', 'list'], ['issue', 'create']]);
});

test('a failed issue search fails the script and opens no issue', () => {
  const { status, calls } = run(null);
  assert.notEqual(status, 0);
  assert.deepEqual(calls, [LIST]);
});
