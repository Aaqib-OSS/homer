import assert from 'node:assert/strict';
import fs from 'node:fs';
import { test } from 'node:test';
import { parseSarif, sortFindings } from './detekt.mjs';

const result = (ruleId, uri, line, text) => ({
  ruleId,
  message: { text },
  locations: [{ physicalLocation: { artifactLocation: { uri, uriBaseId: '%SRCROOT%' }, region: { startLine: line } } }],
});

const sarif = {
  runs: [
    {
      results: [
        result('detekt.exceptions.TooGenericExceptionCaught', 'app/app/src/main/kotlin/homer/app/A.kt', 9, 'The caught exception is too generic.'),
        result('detekt.coroutines.InjectDispatcher', 'app/app/src/main/kotlin/homer/app/B.kt', 4, 'Dispatcher IO is used without dependency injection.'),
        result('detekt.potential-bugs.MissingUseCall', 'file:///repo/app/homer-runner/src/main/kotlin/R.kt', 2, "open doesn't call `use`"),
        { ruleId: 'detekt.style.NoLocation', message: { text: 'project-level' } },
      ],
    },
  ],
};

test('each located result becomes a repo-relative finding named by rule set and rule', () => {
  const findings = parseSarif(sarif, '/repo');
  assert.deepEqual(
    findings.map((f) => [f.path, f.line, f.severity]),
    [
      ['app/app/src/main/kotlin/homer/app/A.kt', 9, 'minor'],
      ['app/app/src/main/kotlin/homer/app/B.kt', 4, 'major'],
      ['app/homer-runner/src/main/kotlin/R.kt', 2, 'major'],
    ],
  );
  assert.equal(findings[0].message, 'exceptions.TooGenericExceptionCaught: The caught exception is too generic.');
});

test('coroutine and potential-bug findings sort before exception findings', () => {
  const sorted = sortFindings(parseSarif(sarif, '/repo'));
  assert.deepEqual(
    sorted.map((f) => f.message.split(':')[0]),
    ['coroutines.InjectDispatcher', 'potential-bugs.MissingUseCall', 'exceptions.TooGenericExceptionCaught'],
  );
});

test('a report without runs or results has no findings', () => {
  assert.deepEqual(parseSarif({}, '/repo'), []);
  assert.deepEqual(parseSarif({ runs: [{}] }, '/repo'), []);
});

// A misspelled or renamed rule key already fails detektMain (config validation); this pins the active set.
test('the style rule set runs exactly the five unused-code rules (GH #297)', () => {
  const config = fs.readFileSync(new URL('../../app/config/detekt/detekt.yml', import.meta.url), 'utf8');
  const style = config.split('\nstyle:\n')[1].split(/\n(?=\S)/)[0];
  const active = [...style.matchAll(/^ {2}(\w+):\n {4}active: true$/gm)].map(([, rule]) => rule);

  assert.match(style, /^ {2}active: true$/m);
  assert.deepEqual(active, ['UnusedParameter', 'UnusedPrivateClass', 'UnusedPrivateFunction', 'UnusedPrivateProperty', 'UnusedVariable']);
});

test('the complexity rule set runs exactly CognitiveComplexMethod and NestedBlockDepth (GH #307, GH #308)', () => {
  const config = fs.readFileSync(new URL('../../app/config/detekt/detekt.yml', import.meta.url), 'utf8');
  const complexity = config.split('\ncomplexity:\n')[1].split(/\n(?=\S)/)[0];
  const active = [...complexity.matchAll(/^ {2}(\w+):\n {4}active: true$/gm)].map(([, rule]) => rule);

  assert.match(complexity, /^ {2}active: true$/m);
  assert.match(complexity, /^ {4}allowedComplexity: 23$/m);
  assert.match(complexity, /^ {4}allowedDepth: 4$/m);
  assert.deepEqual(active, ['CognitiveComplexMethod', 'NestedBlockDepth']);
  // Unlisted, detekt would run these at their defaults (ADR-0082, nesting-depth cap amendment).
  for (const rule of ['ComplexCondition', 'CyclomaticComplexMethod', 'LargeClass', 'LongMethod', 'LongParameterList', 'TooManyFunctions']) {
    assert.match(complexity, new RegExp(`^ {2}${rule}:\\n {4}active: false$`, 'm'), `${rule} must be listed active: false`);
  }
});

test('the naming rule set runs exactly MatchingDeclarationName (GH #309)', () => {
  const config = fs.readFileSync(new URL('../../app/config/detekt/detekt.yml', import.meta.url), 'utf8');
  const naming = config.split('\nnaming:\n')[1].split(/\n(?=\S)/)[0];
  const active = [...naming.matchAll(/^ {2}(\w+):\n {4}active: true$/gm)].map(([, rule]) => rule);

  assert.match(naming, /^ {2}active: true$/m);
  assert.deepEqual(active, ['MatchingDeclarationName']);
});
