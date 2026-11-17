import { describe, expect, it } from 'vitest';

import { currentMaxima, otherMessages, sizeFindings, slack, staleCap, staleCapFindings } from './size.mjs';

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

describe('currentMaxima without a measured file', () => {
  it('throws instead of reporting a maximum of 0', () => {
    expect(() => currentMaxima([])).toThrow('measured no file');
    expect(() => currentMaxima([result('/c/a.ts', [])])).toThrow('measured no file');
  });
});

describe('staleCapFindings', () => {
  it('names the constant, its file, the current maximum and the value to lower it to', () => {
    const findings = staleCapFindings({ 'max-lines': 400, 'max-lines-per-function': 60 }, { 'max-lines': 500, 'max-lines-per-function': 60 });
    expect(findings).toHaveLength(1);
    expect(findings[0].message).toBe('max-lines: MAX_FILE_LINES (console/scripts/size.mjs) is 500 but the current maximum is 400; lower it to 400');
  });

  it('reports nothing when every cap is within the slack', () => {
    expect(staleCapFindings({ 'max-lines': 500, 'max-lines-per-function': 58 }, { 'max-lines': 500, 'max-lines-per-function': 60 })).toEqual([]);
  });
});
