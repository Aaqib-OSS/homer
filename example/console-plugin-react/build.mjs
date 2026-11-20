import { build } from 'esbuild';

// The console imports plugins from its own origin (ADR-0059), so this builds into its plugin
// directory; `--outfile=<path>` overrides it.
const outfileArg = process.argv.find((a) => a.startsWith('--outfile='));
const outfile = outfileArg?.slice('--outfile='.length) ?? '../../console/public/plugins/fleet.js';

// The console imports this file with a bare dynamic import(), so it must be a
// single self-contained ES module: React is bundled in, not externalized.
await build({
  entryPoints: ['src/plugin.tsx'],
  outfile,
  bundle: true,
  format: 'esm',
  target: 'es2022',
  jsx: 'automatic',
  define: { 'process.env.NODE_ENV': '"production"' },
  minify: true,
  logLevel: 'info',
});
