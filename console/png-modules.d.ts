// The `*.png` static-import types come from the generated, gitignored
// `next-env.d.ts`; a clean type check without a prior build needs this copy.
declare module '*.png' {
  const content: string
  export default content
}
