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
    // A wrapper call holding no function, and a qualified type name, declare nothing countable.
    { code: 'const Card = hoc(Base)', options: [2] },
    { code: 'function Card(props: NS.P) { return null }', options: [2] },
    // An interface extending a type declared elsewhere, or a generic base, is not counted.
    { code: "import type { Base } from './base'\ninterface P extends Base { a: string; b: string; c: string }\nfunction Card(props: P) { return null }", options: [2] },
    { code: 'interface P extends Omit<Q, "x"> { a: string; b: string; c: string }\nfunction Card(props: P) { return null }', options: [2] },
    // A circular alias or interface stops instead of recursing.
    { code: 'type P = Q\ntype Q = P\nfunction Card(props: P) { return null }', options: [2] },
    { code: 'interface P extends Q { a: string }\ninterface Q extends P { b: string }\nfunction Card(props: P) { return null }', options: [2] },
    // One unresolvable base leaves the type uncounted, even after a resolvable one.
    { code: "import type { Imported } from './i'\ninterface Base { a: string; b: string }\ninterface P extends Base, Imported { c: string }\nfunction Card(props: P) { return null }", options: [2] },
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
    { code: 'type Q = { a: string; b: string; c: string }\ntype P = Q\nfunction Card(props: P) { return null }', options: [2], errors: [tooMany('Card', 3)] },
    // Inherited members count: 1 own + 2 from Base.
    {
      code: 'interface Base { a: string; b: string }\ninterface P extends Base { c: string }\nfunction Card(props: P) { return null }',
      options: [2],
      errors: [tooMany('Card', 3)],
    },
    // A same-file type alias as a base counts too, and so does every base: 1 own + 1 + 2.
    {
      code: 'type Base = { a: string }\ninterface Other { b: string; c: string }\ninterface P extends Base, Other { d: string }\nfunction Card(props: P) { return null }',
      options: [2],
      errors: [tooMany('Card', 4)],
    },
    // A props type declared after the component still resolves.
    { code: 'function Card(props: P) { return null }\ninterface P { a: string; b: string; c: string }', options: [2], errors: [tooMany('Card', 3)] },
  ],
});
