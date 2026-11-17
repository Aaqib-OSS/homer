import { RuleTester } from 'eslint';
import nextTypescript from 'eslint-config-next/typescript';

import maxProps from './eslint-max-props.mjs';

// The TypeScript parser the console's lint config uses; it is not a direct dependency of its own.
const { parser } = nextTypescript.find((block) => block.languageOptions?.parser).languageOptions;

const tester = new RuleTester({ languageOptions: { parser, parserOptions: { ecmaFeatures: { jsx: true } } } });

const tooMany = (name, count, max = 2) => ({ messageId: 'tooMany', data: { name, count: String(count), max: String(max) } });

tester.run('homer/max-props', maxProps, {
  valid: [
    { code: 'function Card({ a, b }) { return null }', options: [2] },
    { code: 'interface P { a: string; b: string }\nfunction Card(props: P) { return null }', options: [2] },
    // Not a component: lower-case name.
    { code: 'function helper({ a, b, c }) { return a }', options: [2] },
    // A props type declared elsewhere cannot be counted.
    { code: "import type { P } from './p'\nfunction Card(props: P) { return null }", options: [2] },
    { code: 'function Card(props) { return null }', options: [2] },
    { code: 'function Card() { return null }', options: [2] },
  ],
  invalid: [
    { code: 'function Card({ a, b, c }) { return null }', options: [2], errors: [tooMany('Card', 3)] },
    { code: 'export function Card({ a, b = 1, ...rest }) { return null }', options: [2], errors: [tooMany('Card', 3)] },
    { code: 'const Card = ({ a, b, c }) => null', options: [2], errors: [tooMany('Card', 3)] },
    { code: 'const Card = memo(function ({ a, b, c }) { return null })', options: [2], errors: [tooMany('Card', 3)] },
    { code: 'function Card(props: { a: string; b: string; c: string }) { return null }', options: [2], errors: [tooMany('Card', 3)] },
    {
      code: 'export interface P { a: string; b: string; c: string }\nexport function Card(props: P) { return null }',
      options: [2],
      errors: [tooMany('Card', 3)],
    },
    { code: 'type P = { a: string; b: string; c: string }\nconst Card = (props: P) => null', options: [2], errors: [tooMany('Card', 3)] },
  ],
});
