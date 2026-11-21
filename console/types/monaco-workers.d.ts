// Monaco's ESM workers are plain .js with no type declarations, and tsconfig
// excludes node_modules; the lib/monaco/*.worker.ts entries import them bare.
declare module 'monaco-editor/esm/vs/language/json/json.worker.js';
declare module 'monaco-editor/esm/vs/editor/editor.worker.js';
