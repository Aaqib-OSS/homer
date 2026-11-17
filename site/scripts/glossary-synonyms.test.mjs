import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { test } from 'node:test'
import { fileURLToPath } from 'node:url'
import { findHits, format, parseForbidden, stripHtml, stripNonCode, tokens } from './glossary-synonyms.mjs'

const root = join(dirname(fileURLToPath(import.meta.url)), '../..')
const tracked = (...patterns) => execFileSync('git', ['-C', root, 'ls-files', '-z', '--cached', ...patterns], { encoding: 'utf8' }).split('\0').filter(Boolean)
const read = (f) => readFileSync(join(root, f), 'utf8')

// Scope, as stated in docs/glossary.md: every tracked *.md except PROSE_OUT, the feature atlas as
// text, the backend's src/main Kotlin and the console's production TypeScript.
const PROSE_OUT = /^(docs\/(?!glossary\.md$|quality-checks\.md$)|CHANGELOG\.md$|THIRD-PARTY-LICENSES\.md$|CONTRIBUTORS\.md$|plugins\/|\.github\/)/
const ATLAS = 'docs/homer-feature-atlas.html'
// Code in scope: every module's src/main Kotlin and the console's production files, the file set
// of console/scripts/size.mjs.
const KOTLIN = /^app\/[^/]+\/src\/main\/.*\.kt$/
const CONSOLE = /^console\/((app|components|lib|types)\/.*\.tsx?|middleware\.ts)$/
const CONSOLE_OUT = /\.test\.|^console\/components\/ui\/|\.d\.ts$/

function scanFiles() {
  const prose = tracked('*.md').filter((f) => !PROSE_OUT.test(f)).map((path) => ({ path, kind: 'prose' }))
  prose.push({ path: ATLAS, kind: 'html' })
  const code = tracked('app/*.kt', 'console/*.ts', 'console/*.tsx')
    .filter((f) => KOTLIN.test(f) || (CONSOLE.test(f) && !CONSOLE_OUT.test(f)))
    .map((path) => ({ path, kind: path.endsWith('.kt') ? 'kotlin' : 'ts' }))
  return { prose, code }
}

test('no forbidden synonym of a glossary term appears in the docs or in main-code identifiers', () => {
  const glossary = read('docs/glossary.md')
  const rules = parseForbidden(glossary)
  const { prose, code } = scanFiles()
  assert.ok(prose.length > 20 && code.length > 300, `walked too little: ${prose.length} docs, ${code.length} code files`)
  const used = new Set()
  const hits = []
  for (const f of [...prose, ...code]) {
    // The section that lists the synonyms names them by design.
    const text = f.path === 'docs/glossary.md' ? read(f.path).replace(rules.section, rules.section.replace(/[^\n]/g, ' ')) : read(f.path)
    hits.push(...findHits({ ...f, text }, rules, used))
  }
  assert.deepEqual(hits.map(format), [])
  const stale = rules.allowed.filter((a) => !used.has(a))
  assert.deepEqual(stale.map((a) => `${a.file}: allowed "${a.word}" in a line containing "${a.text}" matches nothing; remove the row`), [])
})

const GLOSSARY = `# Glossary

## Forbidden synonyms

| Synonym | Use instead | Why |
| --- | --- | --- |
| \`Skill\` | agent | retired |
| \`lane\` | flow | one name |

### Allowed contexts

| Synonym | File | Line contains | Reason |
| --- | --- | --- | --- |
| \`skill\` | README.md | bundled skill | a Claude Code skill |

## Next section

| Synonym | Use instead |
| --- | --- |
| \`ignored\` | x |
`

test('the parser reads both tables of the section and nothing outside it', () => {
  const { forbidden, allowed } = parseForbidden(GLOSSARY)
  assert.deepEqual(forbidden, [{ word: 'skill', use: 'agent' }, { word: 'lane', use: 'flow' }])
  assert.deepEqual(allowed, [{ word: 'skill', file: 'README.md', text: 'bundled skill', reason: 'a Claude Code skill' }])
})

test('the parser rejects a missing section, an empty list and an allowance for an unlisted word', () => {
  assert.throws(() => parseForbidden('# Glossary\n'), /no "## Forbidden synonyms" section/)
  assert.throws(() => parseForbidden('## Forbidden synonyms\n\ntext\n'), /lists no synonym/)
  const orphan = GLOSSARY.replace('| `skill` | README.md', '| `other` | README.md')
  assert.throws(() => parseForbidden(orphan), /"other", which is not a forbidden synonym/)
  assert.throws(() => parseForbidden(GLOSSARY.replace('| agent |', '|  |')), /names no term/)
  const noReason = GLOSSARY.replace('| a Claude Code skill |', '|  |')
  assert.throws(() => parseForbidden(noReason), /needs a file, a line text and a reason/)
})

test('tokens split camelCase, snake_case, acronyms and digits', () => {
  assert.deepEqual(tokens('runSkill'), ['run', 'skill'])
  assert.deepEqual(tokens('SKILL_NAME skillId'), ['skill', 'name', 'skill', 'id'])
  assert.deepEqual(tokens('HTTPServer2 x'), ['http', 'server', '2', 'x'])
  assert.deepEqual(tokens('skill2 v2skill'), ['skill', '2', 'v', '2', 'skill'])
  assert.deepEqual(tokens('a-skill/b'), ['a', 'skill', 'b'])
  assert.deepEqual(tokens('1 2'), [])
})

test('code comments and string literals are not identifiers', () => {
  const code = 'val a = "skill" // skill\n/* skill\nskill */ val b = """\nskill\n""" + \'skill\'\nval skill = 1\n'
  assert.equal(stripNonCode(code).split('\n').length, code.split('\n').length)
  assert.deepEqual(findHits({ path: 'A.kt', text: code, kind: 'kotlin' }, parseForbidden(GLOSSARY)).map((h) => h.line), [6])
})

test('prose hits report file, line, and the term to use; allowed contexts excuse only their file and line text', () => {
  const rules = parseForbidden(GLOSSARY)
  const text = 'One skill here.\nThe bundled skill there.\nNo hit: skillful.\nTwo skills.'
  const used = new Set()
  const hits = findHits({ path: 'README.md', text, kind: 'prose' }, rules, used)
  assert.deepEqual(hits.map(format), [
    'README.md:1: "skill" names a glossary concept — use "agent" (docs/glossary.md#forbidden-synonyms)',
    'README.md:4: "skill" names a glossary concept — use "agent" (docs/glossary.md#forbidden-synonyms)',
  ])
  assert.equal(used.size, 1)
  assert.equal(findHits({ path: 'OTHER.md', text: 'The bundled skill', kind: 'prose' }, rules).length, 1)
})

test('a Kotlin backticked name is an identifier, a TypeScript template literal is not', () => {
  const rules = parseForbidden(GLOSSARY)
  const code = 'fun `skill works`() {}\nconst a = `skill ${x}`\nconst skill2 = 1\n'
  assert.deepEqual(findHits({ path: 'A.kt', text: code, kind: 'kotlin' }, rules).map((h) => h.line), [1, 2, 3])
  assert.deepEqual(findHits({ path: 'a.ts', text: code, kind: 'ts' }, rules).map((h) => h.line), [3])
})

test('HTML is read as text: tags, scripts, styles and comments are not', () => {
  const rules = parseForbidden(GLOSSARY)
  const html = '<a class="skill" href="skill">x</a>\n<script>\nvar skill\n</script><style>.skill{}</style>\n<!-- skill -->\n<p>a <b>skill</b></p>'
  assert.deepEqual(findHits({ path: 'a.html', text: html, kind: 'html' }, rules).map((h) => h.line), [6])
})

test('an unclosed script, style or comment in HTML throws instead of shrinking the scan', () => {
  for (const open of ['<script>', '<style type="text/css">', '<!--']) {
    assert.throws(() => stripHtml(`<p>a</p>\n${open} skill`), /unclosed .* at line 2/)
  }
  assert.equal(stripHtml('<!-- <script> -->x').trim(), 'x')
})

test('the documented corners: a ">" in a quoted attribute ends its tag early; a regex literal quote hides the rest of its line', () => {
  const rules = parseForbidden(GLOSSARY)
  const html = '<img title="a>b" alt="skill">'
  assert.deepEqual(findHits({ path: 'a.html', kind: 'html', text: html }, rules).map((h) => h.line), [1])
  const ts = 'const re = /"/; const skill = "x"\nconst skill = 1\n'
  assert.deepEqual(findHits({ path: 'a.ts', kind: 'ts', text: ts }, rules).map((h) => h.line), [2])
})

test('a malformed file throws with its path', () => {
  assert.throws(() => findHits({ path: 'docs/a.html', kind: 'html', text: '<script>' }, parseForbidden(GLOSSARY)), /^Error: docs\/a\.html: unclosed <script at line 1/)
})
