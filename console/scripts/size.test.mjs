import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { afterAll, describe, expect, it } from 'vitest';

import { CONSOLE_DIR } from './quality-report.mjs';
import { currentMaxima, LINT_PROBE_RULES, lintCapProbe, productionFiles, occurrencesOf, otherMessages, sizeFindings, slack, staleCap, staleCapFindings, suppressionFindings } from './size.mjs';

const result = (filePath, messages) => ({ filePath, messages });

describe('sizeFindings', () => {
  it('turns each size-rule message into a finding keyed without its line count', () => {
    const results = [
      result('/c/lib/a.ts', [
        { ruleId: 'max-lines-per-function', line: 4, message: "Function 'Big' has too many lines (75). Maximum allowed is 60." },
        { ruleId: 'max-lines', line: 601, message: 'File has too many lines (640). Maximum allowed is 600.' },
      ]),
    ];
    expect(sizeFindings(results, '/c')).toEqual([
      {
        path: 'console/lib/a.ts',
        line: 4,
        message: "max-lines-per-function: Function 'Big' has too many lines (75). Maximum allowed is 60.",
        stableKey: "max-lines-per-function: Function 'Big' has too many lines. Maximum allowed is 60.",
        severity: 'minor',
      },
      {
        path: 'console/lib/a.ts',
        line: 601,
        message: 'max-lines: File has too many lines (640). Maximum allowed is 600.',
        stableKey: 'max-lines: File has too many lines. Maximum allowed is 600.',
        severity: 'minor',
      },
    ]);
  });

  it('ignores messages of any other rule', () => {
    expect(sizeFindings([result('/c/a.ts', [{ ruleId: 'no-console', line: 1, message: 'x' }])], '/c')).toEqual([]);
  });
});

describe('otherMessages', () => {
  it('lists parse errors and other rules, which mean the size run is broken', () => {
    const results = [
      result('/c/a.ts', [
        { ruleId: null, line: 3, message: 'Parsing error: Unexpected token' },
        { ruleId: 'max-lines', line: 9, message: 'File has too many lines (700).' },
      ]),
    ];
    expect(otherMessages(results)).toEqual(['/c/a.ts:3: Parsing error: Unexpected token']);
  });
});

describe('staleCap', () => {
  it('is null at the maximum and within the slack, the maximum itself one past it', () => {
    expect(staleCap(600, 600)).toBeNull();
    expect(staleCap(600, 570)).toBeNull();
    expect(staleCap(600, 569)).toBe(569);
  });

  it('allows a slack of at least 1', () => {
    expect(slack(13)).toBe(1);
    expect(staleCap(13, 12)).toBeNull();
    expect(staleCap(13, 11)).toBe(11);
  });

  it('rounds the slack up', () => {
    expect(slack(3300)).toBe(165);
    expect(staleCap(3300, 3135)).toBeNull();
    expect(staleCap(3300, 3134)).toBe(3134);
  });
});

describe('currentMaxima', () => {
  it('reads the largest count per rule from the probe messages', () => {
    const results = [
      result('/c/a.ts', [
        { ruleId: 'max-lines', line: 1, message: 'File has too many lines (120). Maximum allowed is 1.' },
        { ruleId: 'max-lines-per-function', line: 4, message: "Function 'f' has too many lines (30). Maximum allowed is 1." },
      ]),
      result('/c/b.ts', [{ ruleId: 'max-lines', line: 1, message: 'File has too many lines (480). Maximum allowed is 1.' }]),
    ];
    expect(currentMaxima(results)).toEqual({ 'max-lines': 480, 'max-lines-per-function': 30 });
  });
});

describe('currentMaxima failing closed', () => {
  const file = { ruleId: 'max-lines', line: 1, message: 'File has too many lines (120). Maximum allowed is 1.' };
  const fn = { ruleId: 'max-lines-per-function', line: 4, message: "Function 'f' has too many lines (30). Maximum allowed is 1." };

  it('throws when no file or no function was measured', () => {
    expect(() => currentMaxima([])).toThrow('measured nothing for');
    expect(() => currentMaxima([result('/c/a.ts', [file])])).toThrow('measured nothing for');
    expect(() => currentMaxima([result('/c/a.ts', [fn])])).toThrow('measured nothing for');
  });

  it('names every rule it measured nothing for, including the lint caps', () => {
    expect(() => currentMaxima([result('/c/a.ts', [file])])).toThrow('measured nothing for max-lines-per-function');
    const params = { ruleId: 'max-params', line: 1, message: "Function 'f' has too many parameters (3). Maximum allowed is 0." };
    expect(() => currentMaxima([result('/c/a.ts', [params])], ['max-params', 'homer/max-props'])).toThrow('measured nothing for homer/max-props');
    const props = { ruleId: 'homer/max-props', line: 1, message: "Component 'C' has too many props (9). Maximum allowed is 0." };
    expect(currentMaxima([result('/c/a.ts', [params, props])], ['max-params', 'homer/max-props'])).toEqual({ 'max-params': 3, 'homer/max-props': 9 });
  });

  it('throws on a size-rule message whose count does not parse', () => {
    const bad = { ruleId: 'max-lines-per-function', line: 1, message: 'Function f is too long.' };
    expect(() => currentMaxima([result('/c/a.ts', [file, fn, bad])])).toThrow('unreadable probe message');
  });
});

describe('staleCapFindings', () => {
  it('names the constant, its file, the current maximum and the value to lower it to', () => {
    const findings = staleCapFindings({ 'max-lines': 400, 'max-lines-per-function': 60 }, { 'max-lines': 500, 'max-lines-per-function': 60 });
    expect(findings).toHaveLength(1);
    expect(findings[0].message).toBe('max-lines: MAX_FILE_LINES (console/scripts/size.mjs) is 500 but the current maximum is 400; lower it to 400');
  });

  it('names a lint cap in eslint.config.mjs (GH #311)', () => {
    const findings = staleCapFindings({ 'max-params': 3, 'homer/max-props': 14 }, { 'max-params': 5, 'homer/max-props': 14 });
    expect(findings).toEqual([
      {
        path: 'console/eslint.config.mjs',
        line: 1,
        message: 'max-params: MAX_PARAMS (console/eslint.config.mjs) is 5 but the current maximum is 3; lower it to 3',
        stableKey: 'max-params: stale MAX_PARAMS',
        severity: 'minor',
      },
    ]);
  });

  it('names a stale MAX_PROPS in eslint.config.mjs (GH #311)', () => {
    const [finding] = staleCapFindings({ 'max-params': 5, 'homer/max-props': 11 }, { 'max-params': 5, 'homer/max-props': 14 });
    expect(finding.path).toBe('console/eslint.config.mjs');
    expect(finding.message).toBe('homer/max-props: MAX_PROPS (console/eslint.config.mjs) is 14 but the current maximum is 11; lower it to 11');
    expect(finding.stableKey).toBe('homer/max-props: stale MAX_PROPS');
  });

  it('reports nothing when every cap is within the slack', () => {
    expect(staleCapFindings({ 'max-lines': 500, 'max-lines-per-function': 58 }, { 'max-lines': 500, 'max-lines-per-function': 60 })).toEqual([]);
  });
});

describe('occurrencesOf', () => {
  const file = (text) => [{ path: 'console/lib/a.ts', text }];
  it('counts each match once, with its line', () => {
    expect(occurrencesOf(file('// jscpd:ignore-start\nx()\n// jscpd:ignore-end\n/* jscpd:ignore-start */'), /jscpd:ignore-start/g).map((o) => o.line)).toEqual([1, 4]);
  });
  it('counts a directive inside a string literal', () => {
    expect(occurrencesOf(file("const s = 'jscpd:ignore-start';"), /jscpd:ignore-start/g)).toHaveLength(1);
  });
});

describe('suppressionFindings', () => {
  const files = [{ path: 'console/lib/a.ts', text: '// jscpd:ignore-start\nx()\n// jscpd:ignore-end\n// jscpd:ignore-start\ny()' }];
  it('is silent at the budget and within the slack', () => {
    expect(suppressionFindings(files, 2)).toEqual([]);
    expect(suppressionFindings(files, 3)).toEqual([]);
  });
  it('lists every occurrence over budget', () => {
    const findings = suppressionFindings(files, 1);
    expect(findings).toHaveLength(2);
    expect(findings[0].message).toContain('2 occurrence(s), budget 1 (JSCPD_IGNORE_BUDGET');
  });
  it('names the value to lower a stale budget to', () => {
    const findings = suppressionFindings(files, 20);
    expect(findings).toHaveLength(1);
    expect(findings[0].message).toContain('JSCPD_IGNORE_BUDGET (console/scripts/size.mjs) is 20 but the count is 2; lower it to 2');
  });
});

describe('productionFiles', () => {
  const root = mkdtempSync(join(tmpdir(), 'size-prod-'));
  afterAll(() => rmSync(root, { recursive: true, force: true }));
  const make = (rel) => {
    const filePath = join(root, rel);
    mkdirSync(dirname(filePath), { recursive: true });
    writeFileSync(filePath, `// ${rel}\n`);
    return { filePath, messages: [] };
  };
  it('keeps production files with repo-relative paths and drops tests, shadcn primitives and declarations', () => {
    const results = ['lib/a.ts', 'lib/a.test.tsx', 'components/ui/button.tsx', 'types/x.d.ts', 'middleware.ts', 'components/b.tsx'].map(make);
    const files = productionFiles(results, root);
    expect(files.map((f) => f.path)).toEqual(['console/lib/a.ts', 'console/middleware.ts', 'console/components/b.tsx']);
    expect(files[0].text).toBe('// lib/a.ts\n');
  });
});

describe('otherMessages with the lint-cap rules', () => {
  it('keeps only messages from no lint-cap rule', () => {
    const results = [
      result('/c/a.ts', [
        { ruleId: 'max-params', line: 1, message: "Function 'f' has too many parameters (3). Maximum allowed is 0." },
        { ruleId: 'homer/max-props', line: 2, message: "Component 'C' has too many props (2). Maximum allowed is 0." },
        { ruleId: 'max-lines', line: 3, message: 'File has too many lines (9). Maximum allowed is 1.' },
      ]),
    ];
    expect(otherMessages(results, LINT_PROBE_RULES)).toEqual(['/c/a.ts:3: File has too many lines (9). Maximum allowed is 1.']);
  });
});

describe('lintCapProbe (GH #311)', () => {
  const code = 'export function Card({ a, b, c, d }: { a: string; b: string; c: string; d: string }) {\n  return null;\n}\nexport function add(x: number, y: number, z: number) {\n  return x + y + z;\n}\n';

  it('measures parameters and props in production source with the config\'s own homer/max-props', async () => {
    const results = await lintCapProbe().lintText(code, { filePath: join(CONSOLE_DIR, 'components/lint-cap-probe.tsx') });
    expect(otherMessages(results, LINT_PROBE_RULES)).toEqual([]);
    expect(currentMaxima(results, Object.keys(LINT_PROBE_RULES))).toEqual({ 'max-params': 3, 'homer/max-props': 4 });
  });

  it('leaves test files out, as the caps do', async () => {
    const results = await lintCapProbe().lintText(code, { filePath: join(CONSOLE_DIR, 'components/lint-cap-probe.test.tsx') });
    expect(results.flatMap((r) => r.messages)).toEqual([]);
  });
});
