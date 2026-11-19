import assert from 'node:assert/strict';
import { test } from 'node:test';
import fc from 'fast-check';
import { commentsOf, languageOf } from './comment-lexers.mjs';

const bodies = (language, source) => commentsOf(language, source).map((c) => source.slice(c.bodyStart, c.bodyEnd).trim());
const kinds = (language, source) => commentsOf(language, source).map((c) => c.kind);

test('languageOf maps each scanned extension to its lexer and leaves the rest out', () => {
  const cases = {
    'a/B.kt': 'kotlin',
    'app/build.gradle.kts': 'kotlin',
    'x.ts': 'js',
    'x.tsx': 'js',
    'x.mjs': 'js',
    'x.cjs': 'js',
    'x.js': 'js',
    'site/.vitepress/config.mts': 'js',
    'a.py': 'python',
    'run.sh': 'shell',
    'app/Dockerfile': 'shell',
    'x/Dockerfile.dev': 'shell',
    'ci.yml': 'yaml',
    '.hadolint.yaml': 'yaml',
    'pyproject.toml': 'toml',
    'gradle.properties': 'line-start',
    '.editorconfig': 'line-start',
    '.env.example': 'line-start',
    'tools/pr-policy/gitleaks.env': 'line-start',
    'clients/python/requirements-test.in': 'line-start',
    '.dockerignore': 'line-start',
    'app/.gitignore': 'line-start',
    '.gitattributes': 'line-start',
    '.github/CODEOWNERS': 'line-start',
    'console/app/globals.css': 'css',
    'site/.vitepress/theme/VersionMenu.vue': 'vue',
  };
  for (const [path, language] of Object.entries(cases)) assert.equal(languageOf(path), language, path);
  for (const path of ['README.md', 'docs/a.html', 'package.json', 'app/gradlew', 'x.lock', 'a.xml', 'notes.txt']) assert.equal(languageOf(path), null, path);
});

test('Kotlin: strings, raw strings and templates hide comment markers; KDoc is a doc comment', () => {
  const source = [
    'val u = "http://x" // real one',
    '/** The contract. */',
    'val r = """a // b /* c */"""',
    'val t = "${"// x"}"',
    "val c = '/'",
    '/* block /* nested */ still */',
  ].join('\n');
  assert.deepEqual(bodies('kotlin', source), ['real one', 'The contract.', 'block /* nested */ still']);
  assert.deepEqual(kinds('kotlin', source), ['line', 'doc', 'block']);
});

test('JS: strings, URLs, templates and regex literals are not comments; JSDoc and JSX comments are', () => {
  const source = [
    "const a = 'http://x'; // after a string",
    'const b = "/* not */";',
    'const c = `${"//"} // no ${`// nested`}`;',
    'const d = /\\/\\/[/*]/g.test(x); // after a regex',
    'const e = a / b / c; // division',
    '/** Doc. */',
    'const f = () => <div>{/* jsx */}</div>;',
  ].join('\n');
  assert.deepEqual(bodies('js', source), ['after a string', 'after a regex', 'division', 'Doc.', 'jsx']);
  assert.deepEqual(kinds('js', source), ['line', 'line', 'line', 'doc', 'block']);
});

test('JSX: a closing tag is no regular expression, so the comment after it is found', () => {
  assert.deepEqual(bodies('js', 'const a = <span>x</span>{/* note */};\nconst b = <p>y</p>; // after'), ['note', 'after']);
});

test('Python: # comments outside strings, and docstrings of the module, a class and a function', () => {
  const source = [
    '"""Module doc."""',
    'import os  # a comment',
    'x = "# not a comment"',
    "y = '''# nor this'''",
    'class A(B):',
    '    """Class doc."""',
    '    def f(self, x: int = 1) -> dict[str, int]:',
    '        # a comment before the docstring',
    '        """Function doc."""',
    '        z = "not a docstring"',
    '        return {"k": 1}',
    'async def g(): """One-line doc."""',
    'if x:',
    '    "not a docstring either"',
  ].join('\n');
  assert.deepEqual(bodies('python', source), ['Module doc.', 'a comment', 'Class doc.', 'a comment before the docstring', 'Function doc.', 'One-line doc.']);
  assert.deepEqual(kinds('python', source), ['doc', 'line', 'doc', 'line', 'doc', 'doc']);
});

test('shell: a shebang, quotes, parameter expansions and heredocs hold no comment', () => {
  const source = [
    '#!/usr/bin/env bash',
    '# real',
    'echo "a # b" \'# c\' ${#arr[@]} $# a#b # trailing',
    'cat <<EOF',
    '# heredoc text',
    'EOF',
    'cat <<-"END"',
    '\t# also text',
    '\tEND',
    'echo done # last',
  ].join('\n');
  assert.deepEqual(bodies('shell', source), ['real', 'trailing', 'last']);
});

test('shell: quotes nest inside a command substitution inside double quotes', () => {
  assert.deepEqual(bodies('shell', 'x="$(echo "a # b")" # real\ny="$(printf \'%s\' "$(cat "f # g")")" # also'), ['real', 'also']);
});

test('a Dockerfile is read as shell', () => {
  assert.deepEqual(bodies(languageOf('app/Dockerfile'), '# syntax\nFROM alpine\nRUN echo "#x" # why\n'), ['syntax', 'why']);
});

test('YAML: quoted scalars and URLs hide #; block scalars are read as embedded shell', () => {
  const source = [
    '# top',
    'a: "x # y" # after a quote',
    "b: it's # after an apostrophe",
    'c: http://x/#frag',
    'run: |',
    '  echo "# not"',
    '  # in the script',
    '  #!/bin/sh is no shebang here',
    'd: >-',
    '  folded # text',
    'e: 1 # back in YAML',
  ].join('\n');
  assert.deepEqual(bodies('yaml', source), ['top', 'after a quote', 'after an apostrophe', 'in the script', '!/bin/sh is no shebang here', 'text', 'back in YAML']);
});

test('TOML: four kinds of string hide #', () => {
  const source = 'a = "x # y" # one\nb = \'# z\'\nc = """\n# no\n"""\nd = \'\'\'# no\'\'\' # two\n';
  assert.deepEqual(bodies('toml', source), ['one', 'two']);
});

test('CSS: block comments outside strings', () => {
  assert.deepEqual(bodies('css', 'a { content: "/* no */"; } /* yes */\n/** doc */'), ['yes', 'doc']);
});

test('Vue: the script block is read as JS and the style block as CSS, at their offsets in the file', () => {
  const source = '<script setup lang="ts">\nconst u = "http://x"; // a\n</script>\n<template><!-- not read --></template>\n<style>\n/* b */\n</style>\n';
  assert.deepEqual(bodies('vue', source), ['a', 'b']);
  assert.deepEqual(commentsOf('vue', source).map((c) => source.slice(c.start, c.start + 2)), ['//', '/*']);
});

test('properties and editorconfig: only a line starting with # is a comment', () => {
  assert.deepEqual(bodies('line-start', '# one\nkey=a#b\n  # two\n'), ['one', 'two']);
});

test('at end of input, an unclosed comment runs to the end; an unclosed string, template, heredoc or script block hides the rest; a regex unclosed on its line is division', () => {
  const cases = [
    ['kotlin', '/* TODO', ['TODO']],
    ['kotlin', 'val s = "abc // x', []],
    ['kotlin', 'val s = """raw // x', []],
    ['js', '/** TODO', ['TODO']],
    ['js', 'const t = `abc // x', []],
    ['js', 'const s = "abc // x', []],
    ['js', 'x = /abc\n// y', ['y']],
    ['python', '"""TODO', ['TODO']],
    ['python', "x = '''abc # y", []],
    ['shell', 'cat <<EOF\n# not a comment', []],
    ['shell', 'echo "$(echo # x', []],
    ['yaml', 'a: "abc # x', []],
    ['toml', 'a = """abc # x', []],
    ['css', '/* TODO', ['TODO']],
    ['vue', '<script>\n/* TODO', []],
  ];
  for (const [language, source, expected] of cases) assert.deepEqual(bodies(language, source), expected, `${language}: ${source}`);
});

const LANGUAGES = ['kotlin', 'js', 'python', 'shell', 'yaml', 'toml', 'line-start', 'css', 'vue'];
const tricky = fc.string({ unit: fc.constantFrom('/', '*', '#', '"', "'", '`', '$', '{', '}', '\\', '\n', '<', '|', 'a', ' ', ':', '-'), size: 'medium' });

test('property: no lexer throws, and every comment lies inside the source', () => {
  fc.assert(
    fc.property(fc.oneof(tricky, fc.string({ size: 'medium' })), fc.constantFrom(...LANGUAGES), (source, language) => {
      for (const c of commentsOf(language, source)) {
        assert.ok(c.start >= 0 && c.start <= c.bodyStart && c.bodyStart <= c.bodyEnd && c.bodyEnd <= c.end && c.end <= source.length);
      }
    }),
    { numRuns: 2000 },
  );
});

// Strings hold the sentinel S and comment markers; comments hold C and never S.
const text = (excluded) => fc.string({ unit: fc.constantFrom('/', '*', '#', ':', 'S', 'a', ' ', '-', "'", '"', '`', '{', '$', '\\').filter((ch) => !excluded.includes(ch)), maxLength: 12 });
const commentText = fc.string({ unit: fc.constantFrom('C', 'a', ' ', ':', '-'), maxLength: 12 });

const SHAPES = {
  kotlin: {
    strings: [text('"\\$').map((t) => `val x = "${t}"`), text('"$').map((t) => `val x = """${t}"""`)],
    comments: [commentText.map((t) => `// C${t}`), commentText.map((t) => `/* C${t} */`)],
  },
  js: {
    strings: [text("'\\").map((t) => `x = '${t}';`), text('"\\').map((t) => `x = "${t}";`), text('`\\$').map((t) => `x = \`${t}\`;`)],
    comments: [commentText.map((t) => `// C${t}`), commentText.map((t) => `/* C${t} */`)],
  },
  python: {
    strings: [text("'\\").map((t) => `x = '${t}'`), text('"\\').map((t) => `x = "${t}"`), text('"\\').map((t) => `x = """${t}"""`)],
    comments: [commentText.map((t) => `# C${t}`)],
  },
  shell: {
    strings: [text("'").map((t) => `x='${t}'`), text('"\\$`').map((t) => `x="${t}"`)],
    comments: [commentText.map((t) => `# C${t}`)],
  },
  yaml: {
    strings: [text("'").map((t) => `k: '${t}'`), text('"\\').map((t) => `k: "${t}"`)],
    comments: [commentText.map((t) => `# C${t}`)],
  },
  toml: {
    strings: [text("'").map((t) => `k = '${t}'`), text('"\\').map((t) => `k = "${t}"`)],
    comments: [commentText.map((t) => `# C${t}`)],
  },
};

test('property: a comment marker inside a generated string literal is never a comment', () => {
  for (const [language, { strings, comments }] of Object.entries(SHAPES)) {
    const line = fc.oneof(...strings.map((s) => s.map((code) => ({ code, comment: false }))), ...comments.map((c) => c.map((code) => ({ code, comment: true }))));
    fc.assert(
      fc.property(fc.array(line, { maxLength: 8 }), (lines) => {
        const source = ['x = 1', ...lines.map((l) => l.code)].join('\n');
        const found = commentsOf(language, source).map((c) => source.slice(c.start, c.end));
        assert.equal(found.length, lines.filter((l) => l.comment).length, `${language}:\n${source}`);
        for (const comment of found) assert.ok(!comment.includes('S'), `${language}: ${comment}\n${source}`);
      }),
      { numRuns: 500 },
    );
  }
});
