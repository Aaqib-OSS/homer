import { mergeConfig } from 'vitest/config';
import base from './vitest.config';

// Stryker's per-test coverage instrumentation slows CPU-heavy tests far more than V8 coverage
// does, and a failing dry run aborts the whole mutation run (ADR-0082).
export default mergeConfig(base, {
  test: {
    testTimeout: 120_000,
  },
});
