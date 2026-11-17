#!/usr/bin/env node
/**
 * Size report (check `size-console`, ADR-0082): files over MAX_FILE_LINES and functions over
 * MAX_FUNCTION_LINES in console production code, blank lines and comments not counted — the backend's
 * limits. Its own ESLint run: only these two rules run, so they never reach `npm run lint` or the
 * `eslint-warnings` check. Writes `reports/quality/size-console/`.
 *
 * It also budgets the suppressions in the same files (GH #310): `eslint-disable*` directives and `jscpd:ignore-start` blocks, each against its own
 * budget constant below, ratcheted like the caps, and every `eslint-disable*` states its reason. The backend's counterpart is `SuppressionBudgetTest`.
 *
 * Both caps ratchet (ADR-0082): a cap more than 5% of itself (rounded up, at least 1) above the current maximum is a finding; lower it to
 * the maximum the finding names.
 */
import { readFileSync } from 'node:fs';
import { matchesGlob, relative } from 'node:path';
import process from 'node:process';
import { pathToFileURL } from 'node:url';
import { ESLint } from 'eslint';

import { CONSOLE_DIR, printReport, repoPath, writeReports } from './quality-report.mjs';

const CHECK = 'size-console';
const MAX_FILE_LINES = 500;
const MAX_FUNCTION_LINES = 60;

// Counts on 2026-11-17 (GH #310); lower a budget when its count falls, never raise one to fit a new suppression.
const ESLINT_DISABLE_BUDGET = 15;
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
      if (!(message.ruleId in maxima)) continue;
      const count = Number(/\((\d+)\)/.exec(message.message)?.[1]);
      if (!Number.isInteger(count)) throw new Error(`size-console: unreadable probe message: ${message.message}`);
      if (count > maxima[message.ruleId]) maxima[message.ruleId] = count;
    }
  }
  if (maxima['max-lines'] === 0 || maxima['max-lines-per-function'] === 0) {
    throw new Error('size-console: the probe run measured no file or no function');
  }
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

const ESLINT_DISABLE = /\beslint-disable(?:-next-line|-line)?\b/g;
const JSCPD_IGNORE = /jscpd:ignore-start/g;
const SUPPRESSION_FILE = 'console/scripts/size.mjs';

/** A reason has real words: at least 3 non-space characters once a closing marker is dropped, and not a bare TODO / FIXME. */
export function isReasonText(raw) {
  const text = raw.trim().replace(/\}$/, '').trim().replace(/\*\/$/, '').trim();
  return text.replace(/\s/g, '').length >= 3 && !/^(todo|fixme)\W*$/i.test(text);
}

/**
 * The 0-based lines whose first non-space text starts a `//` comment, found by lexing `text`: a line inside a
 * template literal (`${…}` nesting included), a string or a block comment is never one. Regex literals and JSX
 * text are not lexed, so a `//` there still reads as a comment.
 */
export function lineCommentLines(text) {
  const found = new Set();
  const templateBraces = [];
  let braces = 0;
  let line = 0;
  let lineHasCode = false;
  let inTemplate = false;
  let i = 0;
  const skipUntil = (close, stopAtNewline) => {
    for (i += 1; i < text.length && !text.startsWith(close, i); i += 1) {
      if (text[i] === '\\') i += 1;
      else if (text[i] === '\n') {
        if (stopAtNewline) return;
        line += 1;
      }
    }
    i += close.length - 1;
  };
  for (; i < text.length; i += 1) {
    const c = text[i];
    if (c === '\n') {
      line += 1;
      lineHasCode = false;
    } else if (inTemplate) {
      if (c === '\\') {
        if (text[i + 1] === '\n') line += 1;
        i += 1;
      } else if (c === '`') inTemplate = false;
      else if (c === '$' && text[i + 1] === '{') {
        templateBraces.push(braces);
        braces += 1;
        inTemplate = false;
        i += 1;
      }
    } else if (text.startsWith('//', i)) {
      if (!lineHasCode) found.add(line);
      skipUntil('\n', true);
      i -= 1;
    } else if (text.startsWith('/*', i)) {
      lineHasCode = true;
      skipUntil('*/', false);
    } else if (c === "'" || c === '"') {
      lineHasCode = true;
      skipUntil(c, true);
    } else if (c === '`') {
      lineHasCode = true;
      inTemplate = true;
    } else if (c === '{') {
      lineHasCode = true;
      braces += 1;
    } else if (c === '}') {
      lineHasCode = true;
      braces -= 1;
      if (templateBraces.length > 0 && templateBraces[templateBraces.length - 1] === braces) {
        templateBraces.pop();
        inTemplate = true;
      }
    } else if (!/\s/.test(c)) lineHasCode = true;
  }
  return found;
}

/** Whether the directive ending at `column` of line `index` states why: ESLint's ` -- reason` on its line, or a lexed `//` comment line directly above. */
export function hasReason(lines, index, column, commentLines) {
  const afterDashes = lines[index].slice(column).split(' -- ')[1];
  if (afterDashes !== undefined && isReasonText(afterDashes)) return true;
  return index > 0 && commentLines.has(index - 1) && isReasonText(lines[index - 1].trim().slice(2));
}

/** Every match of `pattern` in each file's text: { path, line, text, reasoned }. A directive inside a string literal counts too. */
export function occurrencesOf(files, pattern) {
  return files.flatMap(({ path, text }) => {
    const lines = text.split('\n');
    const commentLines = lineCommentLines(text);
    return lines.flatMap((line, index) =>
      [...line.matchAll(pattern)].map((match) => ({
        path,
        line: index + 1,
        text: line.trim(),
        reasoned: hasReason(lines, index, match.index + match[0].length, commentLines),
      })),
    );
  });
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

/** Findings of the suppression budgets over `files` ({ path, text }): over budget, stale budget, and an `eslint-disable` without a reason. */
export function suppressionFindings(files, budgets = { eslintDisable: ESLINT_DISABLE_BUDGET, jscpdIgnore: JSCPD_IGNORE_BUDGET }) {
  const eslint = occurrencesOf(files, ESLINT_DISABLE);
  const jscpd = occurrencesOf(files, JSCPD_IGNORE);
  const unreasoned = eslint
    .filter((o) => !o.reasoned)
    .map((o) => ({
      path: o.path,
      line: o.line,
      message: `eslint-disable without a reason (' -- reason' or a // comment on the line above): ${o.text}`,
      stableKey: `eslint-disable: no reason: ${o.path}: ${o.text}`,
      severity: 'minor',
    }));
  return [
    ...budgetFindings('eslint-disable in console production code', 'ESLINT_DISABLE_BUDGET', budgets.eslintDisable, eslint),
    ...budgetFindings('jscpd:ignore-start in console production code', 'JSCPD_IGNORE_BUDGET', budgets.jscpdIgnore, jscpd),
    ...unreasoned,
  ];
}

/** The linted production files with their text: `lintFiles` also returns the files `NOT_PRODUCTION` leaves without rules, so they are dropped here. */
export function productionFiles(results, consoleDir = CONSOLE_DIR) {
  return results
    .filter((result) => !NOT_PRODUCTION.some((pattern) => matchesGlob(relative(consoleDir, result.filePath), pattern)))
    .map((result) => ({ path: repoPath(result.filePath, consoleDir), text: readFileSync(result.filePath, 'utf8') }));
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
