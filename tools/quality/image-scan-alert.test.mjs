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

// Runs the script with a `gh` stub on PATH: `issue list` prints `openIssues` as JSON, every other
// call is recorded, one line of NUL-separated arguments per call.
function run(openIssues) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'image-scan-alert-'));
  const calls = path.join(dir, 'calls');
  fs.writeFileSync(path.join(dir, 'issues.json'), JSON.stringify(openIssues));
  fs.writeFileSync(
    path.join(dir, 'gh'),
    `#!/bin/sh\nif [ "$1 $2" = "issue list" ]; then cat "${dir}/issues.json"; exit 0; fi\nprintf '%s\\0' "$@" >>"${calls}"\necho >>"${calls}"\n`,
    { mode: 0o755 },
  );
  const result = spawnSync('/bin/sh', [SCRIPT], {
    env: { PATH: `${dir}:/usr/bin:/bin`, RUN_URL, GH_TOKEN: 'x', GH_REPO: 'livesport/homer' },
    encoding: 'utf8',
  });
  assert.equal(result.status, 0, result.stderr);
  const recorded = fs.existsSync(calls) ? fs.readFileSync(calls, 'utf8') : '';
  return recorded.split('\n').filter(Boolean).map((line) => line.split('\0').filter(Boolean));
}

test('with no open issue it opens one, naming the run and no package', () => {
  const [call, ...rest] = run([]);
  assert.deepEqual(rest, []);
  assert.deepEqual(call.slice(0, 4), ['issue', 'create', '--title', TITLE]);
  assert.equal(call[4], '--body');
  assert.match(call[5], new RegExp(`The \`image-scan\` job of ${RUN_URL} failed`));
  assert.doesNotMatch(call[5], /@/);
});

test('with the issue open it comments on that issue and opens none', () => {
  const calls = run([{ number: 7, title: `${TITLE} again` }, { number: 12, title: TITLE }]);
  assert.deepEqual(calls, [['issue', 'comment', '12', '--body', `Still failing: ${RUN_URL}`]]);
});

test('an open issue whose title only contains the phrase does not count', () => {
  const calls = run([{ number: 7, title: `Re: ${TITLE}` }]);
  assert.deepEqual(calls.map((c) => c.slice(0, 2)), [['issue', 'create']]);
});
