/**
 * A check's report files as Markdown for a job summary: summary values as a table, findings and accepted exceptions in
 * collapsed lists, every value HTML-escaped, since findings quote source text and the summary renders HTML.
 * Usage (repository root): node tools/quality/step-summary.mjs <dir> <check> <heading> >> "$GITHUB_STEP_SUMMARY"
 */
import fs from 'node:fs';
import path from 'node:path';
import { parseSummary, runMain } from './lib.mjs';

export const MAX_FINDINGS = 200;

export const escapeHtml = (text) => text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');

const cell = (text) => escapeHtml(text).replace(/\|/g, '&#124;').replace(/\r?\n/g, ' ');

/** The Markdown section for one check; `summary`, `report` and `accepted` are the file texts, undefined when missing. */
export function renderSummary(heading, summary, report, accepted) {
  const out = [`### ${cell(heading)}`, ''];
  if (summary === undefined) return `${[...out, 'No report: the run wrote none, see the job log.'].join('\n')}\n`;
  const values = Object.entries(parseSummary(summary));
  if (values.length > 0) out.push(`| ${values.map(([key]) => cell(key)).join(' | ')} |`, `|${' --- |'.repeat(values.length)}`, `| ${values.map(([, value]) => cell(value)).join(' | ')} |`);
  const findings = (report ?? '').trimEnd().split('\n').slice(1).filter(Boolean);
  if (findings.length > 0) {
    const shown = findings.slice(0, MAX_FINDINGS).map(escapeHtml);
    if (findings.length > MAX_FINDINGS) shown.push(`… ${findings.length - MAX_FINDINGS} more in the job artifact`);
    out.push('', `<details><summary>${findings.length} finding(s)</summary>`, '', `<pre>${shown.join('\n')}</pre>`, '', '</details>');
  }
  const acceptedLines = (accepted ?? '').trimEnd().split('\n').filter(Boolean);
  if (acceptedLines.length > 0) {
    const shown = acceptedLines.slice(0, MAX_FINDINGS).map(escapeHtml);
    if (acceptedLines.length > MAX_FINDINGS) shown.push(`… ${acceptedLines.length - MAX_FINDINGS} more in the job artifact`);
    out.push('', `<details><summary>${acceptedLines.length} accepted exception(s)</summary>`, '', `<pre>${shown.join('\n')}</pre>`, '', '</details>');
  }
  return `${out.join('\n')}\n`;
}

const readIfExists = (file) => (fs.existsSync(file) ? fs.readFileSync(file, 'utf8') : undefined);

export function main([dir, check, heading]) {
  if (!dir || !check || !heading) throw new Error('usage: step-summary.mjs <dir> <check> <heading>');
  return renderSummary(heading, readIfExists(path.join(dir, `${check}-summary.txt`)), readIfExists(path.join(dir, `${check}-report.txt`)), readIfExists(path.join(dir, `${check}-accepted.txt`)));
}

runMain(import.meta, main);
