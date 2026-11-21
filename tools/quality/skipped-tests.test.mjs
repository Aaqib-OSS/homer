import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { afterEach, test } from 'node:test';
import fc from 'fast-check';
import { REPORTS, attributes, check, parseReport, readReports, stackFindings, stripUnread, unescapeXml } from './skipped-tests.mjs';

// The shapes Gradle, Vitest and pytest write, trimmed to what the check reads.
const GRADLE = `<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="homer.runner.ProcessRunnerTest" tests="3" skipped="1" failures="0" errors="0" time="1.2">
  <properties/>
  <testcase name="runs a command()" classname="homer.runner.ProcessRunnerTest" time="0.4"/>
  <testcase name="a spawned child is itself dumpable()" classname="homer.runner.ProcessRunnerTest" time="0.1">
    <skipped/>
  </testcase>
  <testcase name="prints &lt;skipped/&gt;()" classname="homer.runner.ProcessRunnerTest" time="0.1"/>
  <system-out><![CDATA[a log line that reads <skipped/> and </testcase>]]></system-out>
  <system-err><![CDATA[]]></system-err>
</testsuite>`;

const VITEST = `<?xml version="1.0" encoding="UTF-8" ?>
<testsuites name="vitest tests" tests="3" failures="0" errors="0" time="0.5">
    <testsuite name="lib/a.test.ts" timestamp="2026-11-20T00:00:00" hostname="h" tests="3" failures="0" errors="0" skipped="2" time="0.1">
        <testcase classname="lib/a.test.ts" name="parser &gt; reads a &quot;quoted&quot; value" time="0.01">
        </testcase>
        <testcase classname="lib/a.test.ts" name="parser &gt; is skipped" time="0">
            <skipped/>
        </testcase>
        <testcase classname="lib/a.test.ts" name="parser &gt; is todo" time="0">
            <skipped/>
        </testcase>
    </testsuite>
</testsuites>`;

const PYTEST = `<?xml version="1.0" encoding="utf-8"?><testsuites name="pytest tests"><testsuite name="pytest" errors="0" failures="0" skipped="1" tests="2" time="1.8" timestamp="t" hostname="h"><testcase classname="homer_langgraph.tests.test_state.TestFifo" name="test_fifo_is_refused" time="0.001"><skipped type="pytest.skip" message="no mkfifo on this platform">/x/test_state.py:364: no mkfifo on this platform</skipped></testcase><testcase classname="homer_langgraph.tests.test_state" name="test_reads" time="0.002" /></testsuite></testsuites>`;

const PASSING = '<testsuite name="A" tests="1" skipped="0"><testcase name="t()" classname="A"/></testsuite>';

const tempRoots = [];
afterEach(() => {
  while (tempRoots.length > 0) fs.rmSync(tempRoots.pop(), { recursive: true, force: true });
});

function tempRoot(files) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'skipped-tests-'));
  tempRoots.push(root);
  for (const [file, text] of Object.entries(files)) {
    fs.mkdirSync(path.dirname(path.join(root, file)), { recursive: true });
    fs.writeFileSync(path.join(root, file), text);
  }
  return root;
}

/** One passing report per stack, the way CI unpacks them. */
const passingTree = () =>
  Object.fromEntries(REPORTS.map((r) => [r.path.endsWith('.xml') ? r.path : `${r.path}/TEST-A.xml`, PASSING]));

test('a skipped JUnit testcase is one finding; a skip marker inside CDATA or an escaped name is not', () => {
  const { tests, skipped } = parseReport(GRADLE);
  assert.equal(tests, 3);
  assert.deepEqual(skipped, [{ classname: 'homer.runner.ProcessRunnerTest', name: 'a spawned child is itself dumpable()', message: '' }]);
});

test('a comment or CDATA section that re-forms once another is removed is not read either', () => {
  const inside = (marker) => `<testsuite name="S" tests="1"><testcase name="t" classname="S">${marker}</testcase></testsuite>`;
  for (const crafted of ['<!<!-- x -->-- <skipped/> -->', '<!<![CDATA[x]]>[CDATA[ <skipped/> ]]>', '<!-<!---->- <skipped/> -->']) {
    assert.doesNotMatch(stripUnread(crafted), /<skipped/, crafted);
    assert.deepEqual(parseReport(inside(crafted)), { tests: 1, skipped: [] }, crafted);
  }
  assert.equal(stripUnread('a<!-- b -->c<![CDATA[d]]>e'), 'ace');
});

test('the Vitest shape: each skipped or todo testcase counts once, and the suite attribute adds nothing', () => {
  const { tests, skipped } = parseReport(VITEST);
  assert.equal(tests, 3);
  assert.deepEqual(skipped.map((s) => s.name), ['parser > is skipped', 'parser > is todo']);
});

test('the pytest shape: the skip reason comes from the message attribute', () => {
  const { tests, skipped } = parseReport(PYTEST);
  assert.equal(tests, 2);
  assert.deepEqual(skipped, [{ classname: 'homer_langgraph.tests.test_state.TestFifo', name: 'test_fifo_is_refused', message: 'no mkfifo on this platform' }]);
});

test('a suite skipped attribute counts only when no testcase names the skips, and only the remainder', () => {
  const bare = '<testsuite name="homer.app.OffTest" tests="2" skipped="2"></testsuite>';
  assert.deepEqual(parseReport(bare), { tests: 2, skipped: [{ classname: 'homer.app.OffTest', name: '2 test(s) the report does not name', message: '' }] });
  const partial = `<testsuite name="S" tests="3" skipped="2"><testcase name="a" classname="S"><skipped/></testcase><testcase name="b" classname="S"/></testsuite>`;
  assert.deepEqual(parseReport(partial).skipped.map((s) => s.name), ['a', '1 test(s) the report does not name']);
  const selfClosing = '<testsuite name="E" tests="0" skipped="1"/>';
  assert.equal(parseReport(selfClosing).skipped.length, 1);
});

test('the testsuites wrapper and its totals are not a suite of their own', () => {
  const wrapped = `<testsuites tests="9" skipped="9">${PASSING}</testsuites>`;
  assert.deepEqual(parseReport(wrapped), { tests: 1, skipped: [] });
});

test('a report with no skipped test has no findings', () => {
  assert.deepEqual(stackFindings(REPORTS[0], [{ file: 'x/TEST-A.xml', xml: PASSING }]), { tests: 1, findings: [] });
});

test('each skipped test is a major finding at its report, naming the stack, test and reason', () => {
  const stack = REPORTS.find((r) => r.stack.startsWith('Python'));
  const { findings } = stackFindings(stack, [{ file: stack.path, xml: PYTEST }]);
  assert.equal(findings.length, 1);
  assert.equal(findings[0].path, 'clients/python/report.xml');
  assert.equal(findings[0].severity, 'major');
  assert.equal(findings[0].stableKey, 'homer_langgraph.tests.test_state.TestFifo|test_fifo_is_refused');
  assert.match(findings[0].message, /^Python client \(pytest\): homer_langgraph\.tests\.test_state\.TestFifo > test_fifo_is_refused was skipped \(no mkfifo on this platform\); /);
});

test('a stack with no report, or reports holding no test, is a finding: the check never passes on no input', () => {
  for (const reports of [[], [{ file: 'a.xml', xml: '<testsuites/>' }], [{ file: 'a.xml', xml: 'not xml' }]]) {
    const { findings } = stackFindings(REPORTS[2], reports);
    assert.equal(findings.length, 1);
    assert.match(findings[0].message, /^no test report from :homer-runner at app\/homer-runner\/build\/test-results\/test: /);
  }
});

test('the reports cover every backend module that runs tests, the e2e suite, the console and the Python client', () => {
  assert.deepEqual(
    REPORTS.map((r) => r.path),
    [
      'app/app/build/test-results/test',
      'app/architecture/build/test-results/test',
      'app/homer-runner/build/test-results/test',
      'app/agentdebug/build/test-results/test',
      'app/buildSrc/build/test-results/test',
      'app/e2e/build/test-results/test',
      'console/reports/quality/junit/vitest.xml',
      'clients/python/report.xml',
    ],
  );
});

test('a report directory yields its XML files in name order; a file yields itself', () => {
  const root = tempRoot({ 'r/TEST-B.xml': 'b', 'r/TEST-A.xml': 'a', 'r/binary/x.bin': 'x', 'r/notes.txt': 't', 'one.xml': 'o' });
  assert.deepEqual(readReports(root, 'r'), [
    { file: 'r/TEST-A.xml', xml: 'a' },
    { file: 'r/TEST-B.xml', xml: 'b' },
  ]);
  assert.deepEqual(readReports(root, 'one.xml'), [{ file: 'one.xml', xml: 'o' }]);
  assert.deepEqual(readReports(root, 'missing'), []);
});

test('the whole tree: zero skips is zero findings; one skip in one stack and one missing stack are two', () => {
  const clean = check(tempRoot(passingTree()));
  assert.deepEqual(clean, { tests: REPORTS.length, findings: [] });
  const tree = passingTree();
  tree['app/homer-runner/build/test-results/test/TEST-homer.runner.ProcessRunnerTest.xml'] = GRADLE;
  delete tree['console/reports/quality/junit/vitest.xml'];
  const { findings } = check(tempRoot(tree));
  assert.deepEqual(
    findings.map((f) => f.path),
    ['app/homer-runner/build/test-results/test/TEST-homer.runner.ProcessRunnerTest.xml', 'console/reports/quality/junit/vitest.xml'],
  );
});

test('attributes are read in either quote style and unescaped', () => {
  assert.deepEqual(attributes(` name="a &amp; b" classname='c&#39;s' message = "&#x3c;x&gt;"`), { name: 'a & b', classname: "c's", message: '<x>' });
  assert.equal(unescapeXml('&#99999999; &bogus; &lt;'), '&#99999999; &bogus; <');
});

test('property: escaping a name and reading it back is the identity, and N skipped testcases are N skips', () => {
  const escape = (s) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
  fc.assert(
    fc.property(fc.array(fc.record({ name: fc.string(), skipped: fc.boolean() }), { maxLength: 8 }), fc.nat(3), (cases, extra) => {
      const marked = cases.filter((c) => c.skipped).length;
      const body = cases
        .map((c) => `<testcase classname="K" name="${escape(c.name)}">${c.skipped ? '<skipped/>' : ''}<system-out><![CDATA[<skipped/>]]></system-out></testcase>`)
        .join('');
      const { tests, skipped } = parseReport(`<testsuite name="K" tests="${cases.length}" skipped="${marked + extra}">${body}</testsuite>`);
      assert.equal(tests, cases.length);
      assert.equal(skipped.length, marked + (extra > 0 ? 1 : 0));
      assert.deepEqual(
        skipped.slice(0, marked).map((s) => s.name),
        cases.filter((c) => c.skipped).map((c) => c.name),
      );
    }),
  );
});

test('property: arbitrary text never throws and never counts more skips than skipped markers and attributes allow', () => {
  fc.assert(
    fc.property(fc.string(), (text) => {
      const { tests, skipped } = parseReport(text);
      assert.ok(tests >= 0);
      assert.ok(skipped.length <= (text.match(/<skipped|skipped\s*=/g) ?? []).length);
    }),
  );
});
