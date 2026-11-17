import { describe, expect, it } from 'vitest';

import config from '../eslint.config.mjs';

describe('eslint.config.mjs cognitive complexity and nesting depth (GH #307, GH #308)', () => {
  const blocks = config.filter((block) => block.rules?.['sonarjs/cognitive-complexity']);

  it('caps cognitive complexity at 11 and nesting depth at 4 in production source only', () => {
    expect(blocks).toHaveLength(1);
    const [block] = blocks;
    expect(block.rules['sonarjs/cognitive-complexity']).toEqual(['warn', 11]);
    expect(block.rules['max-depth']).toEqual(['warn', 4]);
    expect(block.files).toEqual(['**/*.{ts,tsx}']);
    expect(block.ignores).toEqual(['**/*.test.{ts,tsx}', 'vitest.setup.ts']);
    expect(Object.keys(block.plugins)).toEqual(['sonarjs']);
  });

  it('enables no other sonarjs rule and spreads no sonarjs preset', () => {
    const isOff = (setting) => [setting].flat()[0] === 'off' || [setting].flat()[0] === 0;
    const enabled = config.flatMap((block) =>
      Object.entries(block.rules ?? {})
        .filter(([key, setting]) => key.startsWith('sonarjs/') && !isOff(setting))
        .map(([key]) => key),
    );
    expect([...new Set(enabled)]).toEqual(['sonarjs/cognitive-complexity']);
  });
});
