import { describe, expect, it } from 'vitest';

import config from '../eslint.config.mjs';

describe('eslint.config.mjs cognitive complexity (GH #307)', () => {
  const blocks = config.filter((block) => block.rules?.['sonarjs/cognitive-complexity']);

  it('caps cognitive complexity at 11 in production source only', () => {
    expect(blocks).toHaveLength(1);
    const [block] = blocks;
    expect(block.rules['sonarjs/cognitive-complexity']).toEqual(['warn', 11]);
    expect(block.files).toEqual(['**/*.{ts,tsx}']);
    expect(block.ignores).toEqual(['**/*.test.{ts,tsx}', 'vitest.setup.ts']);
    expect(Object.keys(block.plugins)).toEqual(['sonarjs']);
  });
});
