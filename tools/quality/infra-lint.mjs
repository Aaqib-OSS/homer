/**
 * infra-lint: shellcheck over every `*.sh`, actionlint and zizmor over the workflows, hadolint over every Dockerfile, of the files git tracks or does not ignore (ADR-0082).
 * Usage (repository root): node tools/quality/infra-lint.mjs [out dir]. The four tools must be on the PATH; zizmor runs `--offline`, needing no network or token.
 * Exceptions are inline (`# shellcheck disable=`, `# zizmor: ignore[…]`, `# hadolint ignore=`) or in `.hadolint.yaml`, each with its reason.
 */
import { execFileSync, spawnSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { repoRelative, runMain, writeCheck } from './lib.mjs';

const SHELL = /\.sh$/;
const WORKFLOW = /^\.github\/workflows\/[^/]+\.ya?ml$/;
const DOCKERFILE = /(?:^|\/)(?:Dockerfile(?:\.[^/]+)?|[^/]+\.Dockerfile)$/;

/** The files each tool reads, from the repository's file list. */
export function targets(files) {
  return {
    shell: files.filter((f) => SHELL.test(f)),
    workflows: files.filter((f) => WORKFLOW.test(f)),
    dockerfiles: files.filter((f) => DOCKERFILE.test(f)),
  };
}

const finding = (tool, path, line, rule, message, major) => ({
  path: repoRelative(process.cwd(), path),
  line: line || 1,
  severity: major ? 'major' : 'minor',
  message: `${tool} ${rule}: ${message}`,
});

// Configuration the tools read besides the files they lint.
const CONFIG = new Set(['.hadolint.yaml']);

/** Whether the check reads `file`: a file one of the tools lints, or their configuration. */
export const reads = (file) => CONFIG.has(file) || [SHELL, WORKFLOW, DOCKERFILE].some((pattern) => pattern.test(file));

/** shellcheck `-f json1` output. */
export const parseShellcheck = (json) =>
  JSON.parse(json).comments.map((c) => finding('shellcheck', c.file, c.line, `SC${c.code}`, c.message, c.level === 'error'));

/** actionlint `-format '{{json .}}'` output. */
export const parseActionlint = (json) => JSON.parse(json).map((e) => finding('actionlint', e.filepath, e.line, e.kind, e.message, true));

/** zizmor `--format=json-v1` output: one finding at each result's primary location. */
export const parseZizmor = (json) =>
  JSON.parse(json).map((f) => {
    const at = f.locations.find((l) => l.symbolic.kind === 'Primary') ?? f.locations[0];
    const file = at.symbolic.key.Local?.verbatim_path ?? at.symbolic.key.Local?.given_path ?? '.';
    const note = at.symbolic.annotation ? ` (${at.symbolic.annotation})` : '';
    return finding('zizmor', file.replace(/^\.\//, ''), at.concrete.location.start_point.row + 1, f.ident, `${f.desc}${note}`, f.determinations.severity === 'High');
  });

/** hadolint `-f json` output; its ShellCheck findings carry an `SC` code like shellcheck's own. */
export const parseHadolint = (json) =>
  JSON.parse(json).map((c) => finding('hadolint', c.file, c.line, c.code, c.message, c.level === 'error'));

/** Runs `tool` in `cwd` and parses its stdout; an exit code outside `ok` or output that does not parse is a real error. */
export function lint(tool, args, ok, parse, spawn = spawnSync, cwd = process.cwd()) {
  const result = spawn(tool, args, { cwd, encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 });
  if (result.error) throw new Error(`${tool} did not run (${result.error.code ?? result.error.message}); install it, see docs/quality-checks.md`);
  if (!ok.includes(result.status)) throw new Error(`${tool} exited ${result.status}: ${result.stderr.trim()}`);
  try {
    return parse(result.stdout);
  } catch (error) {
    throw new Error(`${tool} wrote output this check cannot read (${error.message}): ${result.stdout.slice(0, 200)}`);
  }
}

// A path is branch content and may read as an option (`--norc.sh`), so each one starts with `./` (CWE-88).
const asPaths = (files) => files.map((f) => `./${f}`);

/** Every finding of the four tools over `files` (relative to `cwd`); a tool with no file to read does not run. */
export function findings(files, spawn = spawnSync, cwd = process.cwd()) {
  const { shell, workflows, dockerfiles } = targets(files);
  const run = (tool, args, ok, parse) => lint(tool, args, ok, parse, spawn, cwd);
  return [
    ...(shell.length ? run('shellcheck', ['-f', 'json1', ...asPaths(shell)], [0, 1], parseShellcheck) : []),
    ...(workflows.length ? run('actionlint', ['-no-color', '-format', '{{json .}}', ...asPaths(workflows)], [0, 1], parseActionlint) : []),
    ...(workflows.length ? run('zizmor', ['--offline', '--no-exit-codes', '--format=json-v1', ...asPaths(workflows)], [0], parseZizmor) : []),
    ...(dockerfiles.length ? run('hadolint', ['--no-fail', '--no-color', '-f', 'json', ...asPaths(dockerfiles)], [0], parseHadolint) : []),
  ].sort((a, b) => a.path.localeCompare(b.path) || a.line - b.line);
}

/** The files of the repository at `cwd` git tracks or does not ignore, without the ones the working tree deleted. */
export function listFiles(cwd = process.cwd()) {
  return execFileSync('git', ['ls-files', '-z', '--cached', '--others', '--exclude-standard'], { cwd, encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 })
    .split('\0')
    .filter((f) => f && fs.existsSync(path.join(cwd, f)));
}

/** Writes the check's three files into `outDir` for the repository at `cwd`, with the count of files each tool read. */
export function main([outDir = 'reports/quality/infra-lint'], { cwd = process.cwd(), spawn = spawnSync } = {}) {
  const files = listFiles(cwd);
  const { shell, workflows, dockerfiles } = targets(files);
  const extra = { 'shell-scripts': shell.length, workflows: workflows.length, dockerfiles: dockerfiles.length };
  return writeCheck(outDir, 'infra-lint', 'Shell, workflow and Dockerfile lint', findings(files, spawn, cwd), extra);
}

runMain(import.meta, main);
