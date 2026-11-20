#!/usr/bin/env node
/**
 * Size report (check `size-console`, ADR-0082): files and functions over their line caps, plus the
 * `jscpd:ignore-start` budget. Every cap, `MAX_PARAMS` / `MAX_PROPS` included, ratchets: one over
 * 5% above its current maximum is a finding naming the value to lower it to.
 */
import { readFileSync } from 'node:fs';
import { matchesGlob, relative } from 'node:path';
import process from 'node:process';
import { pathToFileURL } from 'node:url';
import { ESLint } from 'eslint';

import { MAX_PARAMS, MAX_PROPS, SOURCE_FILES, TEST_FILES } from '../eslint.config.mjs';
import { CONSOLE_DIR, printReport, repoPath, writeReports } from './quality-report.mjs';

const CHECK = 'size-console';
const MAX_FILE_LINES = 500;
const MAX_FUNCTION_LINES = 60;

// Count on 2026-11-17; lower the budget when the count falls, never raise it to fit a new block.
const JSCPD_IGNORE_BUDGET = 0;

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

// The lint caps' own probe: every function with a parameter and every component with a prop reports its count.
export const LINT_PROBE_RULES = { 'max-params': ['warn', 0], 'homer/max-props': ['warn', 0] };

/** Each cap's constant and the file that holds it. */
const CAP_HOMES = {
  'max-lines': ['MAX_FILE_LINES', 'console/scripts/size.mjs'],
  'max-lines-per-function': ['MAX_FUNCTION_LINES', 'console/scripts/size.mjs'],
  'max-params': ['MAX_PARAMS', 'console/eslint.config.mjs'],
  'homer/max-props': ['MAX_PROPS', 'console/eslint.config.mjs'],
};
const CAPS = { 'max-lines': MAX_FILE_LINES, 'max-lines-per-function': MAX_FUNCTION_LINES, 'max-params': MAX_PARAMS, 'homer/max-props': MAX_PROPS };

/** Slack a cap may sit above the current maximum: 5% of the cap, rounded up, at least 1. */
export function slack(cap) {
  return Math.max(1, Math.ceil(cap / 20));
}

/** The value to lower `cap` to (the current maximum) when it sits more than the slack above it, else null. */
export function staleCap(cap, currentMax) {
  return cap - currentMax > slack(cap) ? currentMax : null;
}

/** The largest count per rule, read from the "(N)" in the messages of a probe run with near-zero caps; every rule must measure something. */
export function currentMaxima(results, rules = Object.keys(SIZE_RULES)) {
  const maxima = Object.fromEntries(rules.map((rule) => [rule, 0]));
  for (const result of results) {
    for (const message of result.messages) {
      if (!(message.ruleId in maxima)) continue;
      const count = Number(/\((\d+)\)/.exec(message.message)?.[1]);
      if (!Number.isInteger(count)) throw new Error(`size-console: unreadable probe message: ${message.message}`);
      if (count > maxima[message.ruleId]) maxima[message.ruleId] = count;
    }
  }
  const unmeasured = rules.filter((rule) => maxima[rule] === 0);
  if (unmeasured.length > 0) throw new Error(`size-console: the probe run measured nothing for ${unmeasured.join(', ')}`);
  return maxima;
}

/** One finding per stale cap, naming the constant, its file, the current maximum and the value to lower it to. */
export function staleCapFindings(maxima, caps = CAPS) {
  return Object.keys(caps).flatMap((rule) => {
    const lowerTo = staleCap(caps[rule], maxima[rule]);
    if (lowerTo === null) return [];
    const [constant, file] = CAP_HOMES[rule];
    const message = `${rule}: ${constant} (${file}) is ${caps[rule]} but the current maximum is ${maxima[rule]}; lower it to ${lowerTo}`;
    return [{ path: file, line: 1, message, stableKey: `${rule}: stale ${constant}`, severity: 'minor' }];
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

const JSCPD_IGNORE = /jscpd:ignore-start/g;
const SUPPRESSION_FILE = 'console/scripts/size.mjs';

/** Every match of `pattern` in each file's text: { path, line, text }. A directive inside a string literal counts too. */
export function occurrencesOf(files, pattern) {
  return files.flatMap(({ path, text }) =>
    text.split('\n').flatMap((line, index) => [...line.matchAll(pattern)].map(() => ({ path, line: index + 1, text: line.trim() }))),
  );
}

function budgetFindings(label, constant, budget, found) {
  const finding = (path, line, message, stableKey) => ({ path, line, message, stableKey, severity: 'minor' });
  if (found.length > budget) {
    return found.map((o) => finding(o.path, o.line, `${label}: ${found.length} occurrence(s), budget ${budget} (${constant}, ${SUPPRESSION_FILE}): ${o.text}`, `${constant}: over budget: ${o.path}: ${o.text}`));
  }
  const lowerTo = staleCap(budget, found.length);
  if (lowerTo === null) return [];
  const message = `${label}: ${constant} (${SUPPRESSION_FILE}) is ${budget} but the count is ${found.length}; lower it to ${lowerTo}`;
  return [finding(SUPPRESSION_FILE, 1, message, `${constant}: stale`)];
}

/** Findings of the `jscpd:ignore-start` budget over `files` ({ path, text }): over budget or stale. */
export function suppressionFindings(files, budget = JSCPD_IGNORE_BUDGET) {
  return budgetFindings('jscpd:ignore-start in console production code', 'JSCPD_IGNORE_BUDGET', budget, occurrencesOf(files, JSCPD_IGNORE));
}

/** The linted production files with their text: `lintFiles` also returns the files `NOT_PRODUCTION` leaves without rules, so they are dropped here. */
export function productionFiles(results, consoleDir = CONSOLE_DIR) {
  return results
    .filter((result) => !NOT_PRODUCTION.some((pattern) => matchesGlob(relative(consoleDir, result.filePath), pattern)))
    .map((result) => ({ path: repoPath(result.filePath, consoleDir), text: readFileSync(result.filePath, 'utf8') }));
}

/** The lint caps' probe: an ESLint run over the files the caps cover (the config's own, so `homer/max-props` resolves) at caps of 0. */
export function lintCapProbe() {
  return new ESLint({
    cwd: CONSOLE_DIR,
    overrideConfig: { files: SOURCE_FILES, ignores: TEST_FILES, rules: LINT_PROBE_RULES },
    ruleFilter: ({ ruleId }) => ruleId in LINT_PROBE_RULES,
  });
}

/** Messages from no rule in `rules` mean the run itself is broken (a parse error, a bad directive). */
export function otherMessages(results, rules = SIZE_RULES) {
  return results.flatMap((result) =>
    result.messages.filter((message) => !(message.ruleId in rules)).map((message) => `${result.filePath}:${message.line}: ${message.message}`),
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
  const lintProbe = await lintCapProbe().lintFiles(['.']);
  const brokenLintProbe = otherMessages(lintProbe, LINT_PROBE_RULES);
  if (brokenLintProbe.length > 0) throw new Error(`size-console: unexpected ESLint messages in the lint-cap probe run:\n${brokenLintProbe.join('\n')}`);
  const maxima = { ...currentMaxima(probe), ...currentMaxima(lintProbe, Object.keys(LINT_PROBE_RULES)) };
  const sizeOnly = sizeFindings(results);
  const files = productionFiles(results);
  const findings = [...sizeOnly, ...staleCapFindings(maxima), ...suppressionFindings(files)];
  const count = (rule) => sizeOnly.filter((f) => f.message.startsWith(`${rule}:`)).length;
  const extra = {
    files: count('max-lines'),
    functions: count('max-lines-per-function'),
    maxFileLines: MAX_FILE_LINES,
    maxFunctionLines: MAX_FUNCTION_LINES,
    currentMaxFileLines: maxima['max-lines'],
    currentMaxFunctionLines: maxima['max-lines-per-function'],
    maxParams: MAX_PARAMS,
    maxProps: MAX_PROPS,
    currentMaxParams: maxima['max-params'],
    currentMaxProps: maxima['homer/max-props'],
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
