import { spawnSync } from 'node:child_process';
import { chmodSync, mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { afterEach, describe, expect, it } from 'vitest';

const DEV_SH = path.join(import.meta.dirname, 'dev.sh');
const MARKER = 'FAKE_NPM_ENV ';

let dir;

afterEach(() => rmSync(dir, { recursive: true, force: true }));

// Runs dev.sh in a temp directory holding `envLocal` as .env.local; a fake `npm`
// on PATH prints the environment the script hands to `npm run dev`.
function runDevSh(envLocal) {
  dir = mkdtempSync(path.join(tmpdir(), 'dev-sh-'));
  mkdirSync(path.join(dir, 'node_modules'));
  mkdirSync(path.join(dir, 'bin'));
  const npm = path.join(dir, 'bin', 'npm');
  writeFileSync(npm, `#!/usr/bin/env node\nconsole.log(${JSON.stringify(MARKER)} + JSON.stringify(process.env));\n`);
  chmodSync(npm, 0o755);
  writeFileSync(path.join(dir, '.env.local'), envLocal);
  const run = spawnSync('bash', [DEV_SH], {
    cwd: dir,
    encoding: 'utf8',
    env: { PATH: `${path.join(dir, 'bin')}${path.delimiter}${process.env.PATH}`, HOME: dir },
  });
  const line = run.stdout.split('\n').find((l) => l.startsWith(MARKER));
  return { status: run.status, env: line ? JSON.parse(line.slice(MARKER.length)) : undefined };
}

describe('dev.sh .env.local loading', () => {
  it('exports every value exactly as written, honouring shell quoting', () => {
    const { status, env } = runDevSh(
      [
        '# a comment',
        '',
        '  # an indented comment',
        'SPACED="two  words"',
        "SINGLE='$HOME stays literal'",
        'EQUALS=a=b=c',
        'THEMES=brand-dark:/themes/brand-dark.css,corp:/themes/corp.css',
        'GLOB=*',
        'URL=http://localhost:8080 # trailing comment',
        '',
      ].join('\n'),
    );

    expect(status).toBe(0);
    expect(env).toMatchObject({
      SPACED: 'two  words',
      SINGLE: '$HOME stays literal',
      EQUALS: 'a=b=c',
      THEMES: 'brand-dark:/themes/brand-dark.css,corp:/themes/corp.css',
      GLOB: '*',
      URL: 'http://localhost:8080',
    });
  });

  it('expands unquoted and double-quoted $ references, as a shell does', () => {
    const { status, env } = runDevSh(
      [
        'PORT=3001',
        'HOST=localhost:8080',
        'API=$HOST/api',
        'GREETING="port ${PORT:-3000}"',
        'ESCAPED=\\$HOME',
        "QUOTED='pa$$word'",
        'PID=$$',
        '',
      ].join('\n'),
    );

    expect(status).toBe(0);
    expect(env).toMatchObject({
      PORT: '3001',
      API: 'localhost:8080/api',
      GREETING: 'port 3001',
      ESCAPED: '$HOME',
      QUOTED: 'pa$$word',
    });
    expect(env.PID).toMatch(/^\d+$/);
  });

  it('stops before starting the dev server when a line is not valid shell', () => {
    const { status, env } = runDevSh('UNQUOTED=two words\n');

    expect(status).not.toBe(0);
    expect(env).toBeUndefined();
  });
});
