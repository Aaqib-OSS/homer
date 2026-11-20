import { describe, it, expect } from 'vitest';
import { buildCsp, parseAllowedApiOrigins } from './csp';

describe('parseAllowedApiOrigins', () => {
  it('splits, trims, and drops empties', () => {
    expect(parseAllowedApiOrigins(undefined)).toEqual([]);
    expect(parseAllowedApiOrigins('')).toEqual([]);
    expect(parseAllowedApiOrigins('https://a.example, https://b.example ,')).toEqual([
      'https://a.example',
      'https://b.example',
    ]);
  });
});

describe('buildCsp', () => {
  const base = { nonce: 'NONCE123', allowedApiOrigins: [] as string[] };

  it('is strict: nonce + strict-dynamic scripts, same-origin workers, no eval or inline script', () => {
    const csp = buildCsp(base);
    const directive = (name: string) => csp.split('; ').find((d) => d.startsWith(`${name} `));
    expect(directive('script-src')).toBe("script-src 'self' 'nonce-NONCE123' 'strict-dynamic'");
    expect(directive('worker-src')).toBe("worker-src 'self'");
    expect(csp).not.toContain('unsafe-eval');
    expect(csp).not.toContain('blob:');
    expect(csp).toContain("object-src 'none'");
    expect(csp).toContain("frame-ancestors 'none'");
  });

  it('leaves connect-src open when no allowlist is configured', () => {
    const csp = buildCsp(base);
    expect(csp).toContain("connect-src 'self' https: http:");
  });

  it('pins connect-src to the allowlist when configured', () => {
    const csp = buildCsp({
      ...base,
      allowedApiOrigins: ['https://homer.example.com'],
    });
    expect(csp).toContain("connect-src 'self' https://homer.example.com");
    expect(csp).not.toContain('https: http:');
  });
});
