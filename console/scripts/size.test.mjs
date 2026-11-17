import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { afterAll, describe, expect, it } from 'vitest';

import { currentMaxima, hasReason, lineCommentLines, productionFiles, isReasonText, occurrencesOf, otherMessages, sizeFindings, slack, staleCap, staleCapFindings, suppressionFindings } from './size.mjs';

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

  it('reports nothing when every cap is within the slack', () => {
    expect(staleCapFindings({ 'max-lines': 500, 'max-lines-per-function': 58 }, { 'max-lines': 500, 'max-lines-per-function': 60 })).toEqual([]);
  });
});

describe('isReasonText', () => {
  it('needs real words, not a bare marker or TODO', () => {
    expect(isReasonText(' sync buffer to async content')).toBe(true);
    expect(isReasonText('')).toBe(false);
    expect(isReasonText(' ')).toBe(false);
    expect(isReasonText(' TODO')).toBe(false);
    expect(isReasonText(' fixme: ')).toBe(false);
    expect(isReasonText(' ok */')).toBe(false);
    expect(isReasonText(' because */}')).toBe(true);
  });
});

describe('hasReason', () => {
  const reasoned = (source) => {
    const lines = source.split('\n');
    const index = lines.findIndex((l) => l.includes('eslint-disable'));
    return hasReason(lines, index, lines[index].indexOf('eslint-disable') + 'eslint-disable'.length, lineCommentLines(source));
  };
  it('takes a reason after two dashes or a comment line above', () => {
    expect(reasoned('// eslint-disable-next-line no-x -- why not\nfoo()')).toBe(true);
    expect(reasoned('{/* eslint-disable-next-line no-x -- why not */}')).toBe(true);
    expect(reasoned('// because of the loop\n// eslint-disable-next-line no-x\nfoo()')).toBe(true);
  });
  it('rejects a missing, empty or TODO reason, and code above', () => {
    expect(reasoned('foo(); // eslint-disable-line no-x')).toBe(false);
    expect(reasoned('foo(); // eslint-disable-line no-x -- ')).toBe(false);
    expect(reasoned('// TODO\n// eslint-disable-next-line no-x')).toBe(false);
    expect(reasoned('foo()\n// eslint-disable-next-line no-x')).toBe(false);
    expect(reasoned('const s = `\n// why it is here\n`;\n// eslint-disable-next-line no-x')).toBe(false);
    expect(reasoned('const s = `a ${b}\n// why it is here`;\n// eslint-disable-next-line no-x')).toBe(false);
    expect(reasoned('const s = `${`x`}`; // why it is here\n// eslint-disable-next-line no-x')).toBe(false);
  });
});

describe('occurrencesOf', () => {
  const file = (text) => [{ path: 'console/lib/a.ts', text }];
  it('counts the three eslint-disable forms once each and not eslint-enable', () => {
    const text = '/* eslint-disable a */\n// eslint-disable-next-line b\nx(); // eslint-disable-line c\n/* eslint-enable a */';
    expect(occurrencesOf(file(text), /\beslint-disable(?:-next-line|-line)?\b/g).map((o) => o.line)).toEqual([1, 2, 3]);
  });
  it('counts a directive inside a string literal', () => {
    expect(occurrencesOf(file("const s = 'eslint-disable-next-line';"), /\beslint-disable(?:-next-line|-line)?\b/g)).toHaveLength(1);
  });
});

describe('suppressionFindings', () => {
  const files = [{ path: 'console/lib/a.ts', text: '// ok reason\n// eslint-disable-next-line a\nx()\n// eslint-disable-next-line b -- fine\ny()' }];
  const budgets = (eslintDisable, jscpdIgnore = 0) => ({ eslintDisable, jscpdIgnore });
  it('is silent at the budget and within the slack', () => {
    expect(suppressionFindings(files, budgets(2))).toEqual([]);
    expect(suppressionFindings(files, budgets(3))).toEqual([]);
  });
  it('lists every occurrence over budget', () => {
    const findings = suppressionFindings(files, budgets(1));
    expect(findings).toHaveLength(2);
    expect(findings[0].message).toContain('2 occurrence(s), budget 1 (ESLINT_DISABLE_BUDGET');
  });
  it('names the value to lower a stale budget to', () => {
    const findings = suppressionFindings(files, budgets(20));
    expect(findings).toHaveLength(1);
    expect(findings[0].message).toContain('ESLINT_DISABLE_BUDGET (console/scripts/size.mjs) is 20 but the count is 2; lower it to 2');
  });
  it('flags a jscpd block over a zero budget and an eslint-disable without a reason', () => {
    const more = [{ path: 'console/lib/b.ts', text: '// jscpd:ignore-start\nx()\nfoo(); // eslint-disable-line z' }];
    const messages = suppressionFindings(more, budgets(1, 0)).map((f) => f.message);
    expect(messages.some((m) => m.includes('JSCPD_IGNORE_BUDGET'))).toBe(true);
    expect(messages.some((m) => m.startsWith('eslint-disable without a reason'))).toBe(true);
  });
});

describe('lineCommentLines', () => {
  const lines = (text) => [...lineCommentLines(text)];
  it('finds comment-only lines', () => {
    expect(lines('a();\n  // c\nb(); // d\n// e')).toEqual([1, 3]);
  });
  it('skips lines inside template literals, nested templates, strings and block comments', () => {
    expect(lines('const s = `\n// in template\n`;\n// real')).toEqual([3]);
    expect(lines('const s = `${a}\n// still template ${`\n// nested`}\n// still`;\n// real')).toEqual([4]);
    expect(lines("const s = 'http://x';\n// real")).toEqual([1]);
    expect(lines('/*\n// in block\n*/\n// real')).toEqual([3]);
  });
  it('reads an escaped quote inside a string as part of it', () => {
    expect(lines("const s = 'it\\'s // not a comment';\n// real")).toEqual([1]);
    expect(lines('const s = "say \\"hi\\" // not a comment";\n// real')).toEqual([1]);
  });
  it('keeps the line count across a backslash-newline in a string', () => {
    expect(lines("const s = 'a\\\nb';\n// real")).toEqual([2]);
  });
  it('treats a backslash in a block comment as plain text', () => {
    expect(lines('/* a \\\n b */\n// real')).toEqual([2]);
  });
  it('treats a backslash at the end of a line comment as plain text', () => {
    expect(lines('// a \\\n// real')).toEqual([0, 1]);
  });
  it('resumes the template after a ${} block with braces', () => {
    expect(lines('const s = `${{ a: 1 }.a}\n// still template`;\n// real')).toEqual([2]);
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
