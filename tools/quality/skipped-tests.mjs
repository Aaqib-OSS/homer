/**
 * skipped-tests (ADR-0117): each test the CI run skipped, from the JUnit XML of the backend modules, Vitest and pytest.
 * A stack whose report is missing or holds no test is a finding too, so the check never passes on no input.
 * Usage (repository root, the CI reports unpacked): node tools/quality/skipped-tests.mjs [out dir]
 */
import fs from 'node:fs';
import path from 'node:path';
import { runMain, writeCheck } from './lib.mjs';

const TITLE = 'Tests skipped in the CI run';

/**
 * The report of each stack: a directory of JUnit XML files, or one file. The :e2e report is homer-e2e's,
 * which runs the suite against a live stack; homer-app-tests runs it without one and does not pack it.
 */
export const REPORTS = [
  { stack: ':app', path: 'app/app/build/test-results/test' },
  { stack: ':architecture', path: 'app/architecture/build/test-results/test' },
  { stack: ':homer-runner', path: 'app/homer-runner/build/test-results/test' },
  { stack: ':agentdebug', path: 'app/agentdebug/build/test-results/test' },
  { stack: 'buildSrc', path: 'app/buildSrc/build/test-results/test' },
  { stack: ':e2e', path: 'app/e2e/build/test-results/test' },
  { stack: 'console (Vitest)', path: 'console/reports/quality/junit/vitest.xml' },
  { stack: 'Python client (pytest)', path: 'clients/python/report.xml' },
];

const ENTITIES = { lt: '<', gt: '>', amp: '&', quot: '"', apos: "'" };

/** `text` with the XML entity and character references resolved; an invalid code point stays as written. */
export function unescapeXml(text) {
  return text.replace(/&(#x[0-9a-fA-F]+|#\d+|lt|gt|amp|quot|apos);/g, (ref, name) => {
    if (name[0] !== '#') return ENTITIES[name];
    const code = name[1] === 'x' ? Number.parseInt(name.slice(2), 16) : Number(name.slice(1));
    return code <= 0x10ffff ? String.fromCodePoint(code) : ref;
  });
}

const ATTRS = String.raw`((?:\s+[^\s=/>]+\s*=\s*(?:"[^"]*"|'[^']*'))*)\s*`;
const SUITE = new RegExp(String.raw`<testsuite${ATTRS}(?:/>|>([\s\S]*?)</testsuite>)`, 'g');
const TESTCASE = new RegExp(String.raw`<testcase${ATTRS}(?:/>|>([\s\S]*?)</testcase>)`, 'g');
const SKIPPED = new RegExp(String.raw`<skipped${ATTRS}/?>`);

/** The attributes of a tag's attribute text, values unescaped. */
export function attributes(text) {
  return Object.fromEntries([...text.matchAll(/([^\s=/>]+)\s*=\s*(?:"([^"]*)"|'([^']*)')/g)].map((m) => [m[1], unescapeXml(m[2] ?? m[3])]));
}

const UNREAD = /<!\[CDATA\[[\s\S]*?\]\]>|<!--[\s\S]*?-->/g;

/** `xml` without CDATA sections and comments, removed until none re-forms from what is left. */
export function stripUnread(xml) {
  let text = xml;
  let before;
  do {
    before = text;
    text = text.replace(UNREAD, '');
  } while (text !== before);
  return text;
}

/**
 * `{ tests, skipped }` of one JUnit XML report. A skipped `<testcase>` counts once; a suite's `skipped`
 * attribute adds only the skips no testcase of the suite names. CDATA and comments are not read.
 */
export function parseReport(xml) {
  const text = stripUnread(xml);
  let tests = 0;
  const skipped = [];
  for (const [, suiteAttrs, body = ''] of text.matchAll(SUITE)) {
    const suite = attributes(suiteAttrs);
    const cases = [...body.matchAll(TESTCASE)];
    tests += Math.max(cases.length, Number(suite.tests) || 0);
    let named = 0;
    for (const [, caseAttrs, caseBody = ''] of cases) {
      const mark = SKIPPED.exec(caseBody);
      if (!mark) continue;
      named++;
      const testcase = attributes(caseAttrs);
      skipped.push({ classname: testcase.classname ?? suite.name ?? '', name: testcase.name ?? '', message: attributes(mark[1]).message ?? '' });
    }
    const unnamed = (Number(suite.skipped) || 0) - named;
    if (unnamed > 0) skipped.push({ classname: suite.name ?? '', name: `${unnamed} test(s) the report does not name`, message: '' });
  }
  return { tests, skipped };
}

/** The `{ file, xml }` reports of `report` under `root`: its XML files when a directory, else the file itself. */
export function readReports(root, report) {
  const at = path.join(root, report);
  if (!fs.existsSync(at)) return [];
  const files = fs.statSync(at).isDirectory()
    ? fs.readdirSync(at).filter((f) => f.endsWith('.xml')).sort().map((f) => `${report}/${f}`)
    : [report];
  return files.map((file) => ({ file, xml: fs.readFileSync(path.join(root, file), 'utf8') }));
}

const oneLine = (text) => {
  const flat = text.replace(/\s+/g, ' ').trim();
  return flat.length > 200 ? `${flat.slice(0, 199)}…` : flat;
};

/** Findings and test count of one stack's reports: one finding per skipped test, or one when they hold no test. */
export function stackFindings({ stack, path: report }, reports) {
  const parsed = reports.map(({ file, xml }) => ({ file, ...parseReport(xml) }));
  const tests = parsed.reduce((sum, r) => sum + r.tests, 0);
  if (tests === 0) {
    const message = `no test report from ${stack} at ${report}: did its tests run, and did their CI job pack the report?`;
    return { tests, findings: [{ path: report, line: 1, severity: 'major', message }] };
  }
  const findings = parsed.flatMap(({ file, skipped }) =>
    skipped.map((s) => ({
      path: file,
      line: 1,
      severity: 'major',
      stableKey: `${s.classname}|${s.name}`,
      message: `${stack}: ${oneLine(`${s.classname} > ${s.name}`)} was skipped${s.message ? ` (${oneLine(s.message)})` : ''}; CI allows no skipped test (ADR-0117)`,
    })),
  );
  return { tests, findings };
}

/** Findings of every stack in `REPORTS`, read under `root`. */
export function check(root = '.') {
  const stacks = REPORTS.map((entry) => stackFindings(entry, readReports(root, entry.path)));
  return { tests: stacks.reduce((sum, s) => sum + s.tests, 0), findings: stacks.flatMap((s) => s.findings) };
}

export function main([outDir = 'reports/quality/skipped-tests']) {
  const { tests, findings } = check();
  return writeCheck(outDir, 'skipped-tests', TITLE, findings, { tests }).split('\n').slice(0, 41).join('\n') + '\n';
}

runMain(import.meta, main);
