#!/usr/bin/env node
/**
 * Size report (check `size-console`, ADR-0082): files over MAX_FILE_LINES and functions over
 * MAX_FUNCTION_LINES in console production code, blank lines and comments not counted — the backend's
 * limits. Its own ESLint run: only these two rules run, so they never reach `npm run lint` or the
 * `eslint-warnings` check. Writes `reports/quality/size-console/`.
 *
 * Both caps ratchet (ADR-0082): a cap more than 5% above the current maximum is a finding; lower it to
 * the maximum the finding names.
 */
import process from 'node:process';
import { pathToFileURL } from 'node:url';
import { ESLint } from 'eslint';

import { CONSOLE_DIR, printReport, repoPath, writeReports } from './quality-report.mjs';

const CHECK = 'size-console';
const MAX_FILE_LINES = 500;
const MAX_FUNCTION_LINES = 60;

// Shipped code: what coverage and CRAP score, plus the DTO types. Tests and the shadcn primitives are not.
const PRODUCTION_FILES = ['app/**/*.{ts,tsx}', 'components/**/*.{ts,tsx}', 'lib/**/*.{ts,tsx}', 'types/**/*.ts', 'middleware.ts'];
const NOT_PRODUCTION = ['**/*.test.*', 'components/ui/**', '**/*.d.ts'];

const COUNTING = { skipBlankLines: true, skipComments: true };
const sizeRules = (maxFileLines, maxFunctionLines) => ({
  'max-lines': ['warn', { max: maxFileLines, ...COUNTING }],
  'max-lines-per-function': ['warn', { max: maxFunctionLines, ...COUNTING }],
});
const SIZE_RULES = sizeRules(MAX_FILE_LINES, MAX_FUNCTION_LINES);
// Every file and function over one line reports its count, which is how the current maximum is read.
const PROBE_RULES = sizeRules(1, 1);

const CONSTANTS = { 'max-lines': 'MAX_FILE_LINES', 'max-lines-per-function': 'MAX_FUNCTION_LINES' };
const CAPS = { 'max-lines': MAX_FILE_LINES, 'max-lines-per-function': MAX_FUNCTION_LINES };

/** Slack a cap may sit above the current maximum: 5% of the cap, rounded up, at least 1. */
export function slack(cap) {
  return Math.max(1, Math.ceil(cap / 20));
}

/** The value to lower `cap` to (the current maximum) when it sits more than the slack above it, else null. */
export function staleCap(cap, currentMax) {
  return cap - currentMax > slack(cap) ? currentMax : null;
}

/** The largest size per rule, read from the "(N)" in the messages of a probe run with 1-line caps. */
export function currentMaxima(results) {
  const maxima = { 'max-lines': 0, 'max-lines-per-function': 0 };
  for (const result of results) {
    for (const message of result.messages) {
      const count = Number(/\((\d+)\)/.exec(message.message)?.[1]);
      if (message.ruleId in maxima && count > maxima[message.ruleId]) maxima[message.ruleId] = count;
    }
  }
  if (maxima['max-lines'] === 0) throw new Error('size-console: the probe run measured no file');
  return maxima;
}

/** One finding per stale cap, naming the constant, its file, the current maximum and the value to lower it to. */
export function staleCapFindings(maxima, caps = CAPS) {
  return Object.keys(caps).flatMap((rule) => {
    const lowerTo = staleCap(caps[rule], maxima[rule]);
    if (lowerTo === null) return [];
    const message = `${rule}: ${CONSTANTS[rule]} (console/scripts/size.mjs) is ${caps[rule]} but the current maximum is ${maxima[rule]}; lower it to ${lowerTo}`;
    return [{ path: 'console/scripts/size.mjs', line: 1, message, stableKey: `${rule}: stale ${CONSTANTS[rule]}`, severity: 'minor' }];
  });
}

/** One finding per size-rule message; the fingerprint key leaves out the line count, which moves with every edit. */
export function sizeFindings(results, consoleDir = CONSOLE_DIR) {
  return results.flatMap((result) =>
    result.messages
      .filter((message) => message.ruleId in SIZE_RULES)
      .map((message) => ({
        path: repoPath(result.filePath, consoleDir),
        line: message.line ?? 1,
        message: `${message.ruleId}: ${message.message}`,
        stableKey: `${message.ruleId}: ${message.message.replace(/ \(\d+\)/, '')}`,
        severity: 'minor',
      })),
  );
}

/** Messages that are not size findings mean the run itself is broken (a parse error, a bad directive). */
export function otherMessages(results) {
  return results.flatMap((result) =>
    result.messages.filter((message) => !(message.ruleId in SIZE_RULES)).map((message) => `${result.filePath}:${message.line}: ${message.message}`),
  );
}

async function main() {
  const eslint = new ESLint({
    cwd: CONSOLE_DIR,
    overrideConfig: { files: PRODUCTION_FILES, ignores: NOT_PRODUCTION, rules: SIZE_RULES },
    ruleFilter: ({ ruleId }) => ruleId in SIZE_RULES,
  });
  const results = await eslint.lintFiles(PRODUCTION_FILES);
  const broken = otherMessages(results);
  if (broken.length > 0) throw new Error(`size-console: unexpected ESLint messages:\n${broken.join('\n')}`);
  const probe = await new ESLint({
    cwd: CONSOLE_DIR,
    overrideConfig: { files: PRODUCTION_FILES, ignores: NOT_PRODUCTION, rules: PROBE_RULES },
    ruleFilter: ({ ruleId }) => ruleId in SIZE_RULES,
  }).lintFiles(PRODUCTION_FILES);
  const brokenProbe = otherMessages(probe);
  if (brokenProbe.length > 0) throw new Error(`size-console: unexpected ESLint messages in the probe run:\n${brokenProbe.join('\n')}`);
  const maxima = currentMaxima(probe);
  const sizeOnly = sizeFindings(results);
  const findings = [...sizeOnly, ...staleCapFindings(maxima)];
  const count = (rule) => sizeOnly.filter((f) => f.message.startsWith(`${rule}:`)).length;
  const extra = {
    files: count('max-lines'),
    functions: count('max-lines-per-function'),
    maxFileLines: MAX_FILE_LINES,
    maxFunctionLines: MAX_FUNCTION_LINES,
    currentMaxFileLines: maxima['max-lines'],
    currentMaxFunctionLines: maxima['max-lines-per-function'],
  };
  const detail = `(${extra.files} file(s) > ${MAX_FILE_LINES} lines, ${extra.functions} function(s) > ${MAX_FUNCTION_LINES} lines)`;
  const report = writeReports(CHECK, { title: 'Console file and function size', findings, detail, extra });
  printReport(CHECK, report);
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().catch((error) => {
    console.error(error.stack ?? error.message);
    process.exit(1);
  });
}
