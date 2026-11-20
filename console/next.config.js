/** @type {import('next').NextConfig} */

// Static hardening headers for every route and asset; the CSP needs a per-request
// nonce, so middleware.ts emits it (lib/security/csp.ts).
const securityHeaders = [
  { key: 'X-Frame-Options', value: 'DENY' },
  { key: 'X-Content-Type-Options', value: 'nosniff' },
  { key: 'Referrer-Policy', value: 'strict-origin-when-cross-origin' },
]

const nextConfig = {
  output: 'standalone',
  reactStrictMode: true,
  // Optimization needs `sharp`/`@img/*` (LGPL-3.0), which the image deletes (ADR-0056); keep it off.
  images: { unoptimized: true },
  async headers() {
    return [{ source: '/:path*', headers: securityHeaders }]
  },
}

module.exports = nextConfig
