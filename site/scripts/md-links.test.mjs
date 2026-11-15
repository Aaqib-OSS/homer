import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import { existsSync, readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { test } from 'node:test'
import { fileURLToPath } from 'node:url'
import { anchors, checkLinks, linkTargets } from './md-links.mjs'

// Every tracked markdown file, not only the pages the site publishes: the CLAUDE.md guides are
// read on GitHub and loaded into every agent session, so a stale path there misleads silently.
// Only tracked files are walked; untracked files git does not ignore still count as link targets,
// so a new doc can be linked before `git add`. site/pages/** is not walked: its links name
// built-site routes, which the site build's dead-link check covers.
const root = join(dirname(fileURLToPath(import.meta.url)), '../..')
const git = (...args) => execFileSync('git', ['-C', root, 'ls-files', '-z', ...args], { encoding: 'utf8' }).split('\0').filter(Boolean)
const onDisk = (f) => existsSync(join(root, f))
const linkDir = {
  '.github/pull_request_template.md': '', // pasted into a pull request body; written from the root
  'plugins/homer-authoring/skills/homer-agent-authoring/reference/AGENT.md': 'app', // copy of app/AGENT.md
}

test('every relative link, heading anchor and CLAUDE.md path in tracked markdown resolves', () => {
  const { problems, counts } = checkLinks({
    walk: git('--cached', '*.md').filter((f) => onDisk(f) && !f.startsWith('site/pages/')),
    targets: git('--cached', '--others', '--exclude-standard').filter(onDisk),
    read: (f) => readFileSync(join(root, f), 'utf8'),
    linkDir,
  })
  if (process.env.MD_LINKS_VERBOSE) console.log(counts)
  assert.ok(counts.files > 100 && counts.links > 1000 && counts.anchors > 100 && counts.paths > 50, `walked too little: ${JSON.stringify(counts)}`)
  assert.deepEqual(problems, [])
})

// A tree held in memory: path → contents. Every path is a target; the .md files are walked.
function check(tree) {
  const read = (f) => tree[f]
  return checkLinks({ walk: Object.keys(tree).filter((f) => f.endsWith('.md')), targets: Object.keys(tree), read }).problems
}

test('dead links, anchors, reference definitions and CLAUDE.md paths are reported', () => {
  const problems = check({
    'README.md': '# Top\n\n[ok](docs/a.md#intro) [dead](docs/gone.md) [no anchor](docs/a.md#outro) [self](#top) [bad](#nope)\n\n[ref]: docs/missing.md\n[bad%escape](docs/a%zz.md)',
    'docs/a.md': '## Intro\n',
    'app/CLAUDE.md': 'See `app/gradlew`, `app/gradlew -p app check`, `app/**`, `homer.app.Foo`, `app/...` and `app/nope.kt`.\n',
    'app/gradlew': '',
  })
  assert.deepEqual(problems, [
    'README.md: link to missing docs/gone.md',
    'README.md: no heading for docs/a.md#outro',
    'README.md: no heading for #nope',
    'README.md: unparseable link docs/a%zz.md',
    'README.md: link to missing docs/missing.md',
    'app/CLAUDE.md: missing path `app/nope.kt`',
  ])
})

test('links in code blocks and code spans, URLs and other files\' backticks are not checked', () => {
  assert.deepEqual(check({
    'a.md': '`[x](gone.md)`\n\n```\n[y](gone.md)\n```\n\n~~~~\n[z](gone.md)\n~~~~\n\n<!-- [c](gone.md) -->\n[u](https://example.com/gone.md) [m](mailto:x@example.com)\n\n`app/nope.kt`',
    'app/x': '',
  }), [])
})

test('link destinations: balanced parentheses, angle brackets with spaces, titles, images', () => {
  assert.deepEqual(linkTargets('[x](foo_(bar).md) [y](<my file.md>) [z](a.md "title") ![i](img.png) [w](b.md)'), ['foo_(bar).md', 'my file.md', 'a.md', 'img.png', 'b.md'])
  assert.deepEqual(check({ 'a.md': '[x](foo_(bar).md) [y](<my file.md>)', 'foo_(bar).md': '', 'my file.md': '' }), [])
  assert.deepEqual(check({ 'a.md': '[x](foo_(baz).md)' }), ['a.md: link to missing foo_(baz).md'])
})

test('anchors follow GitHub: slugs, repeats, setext, blockquotes and explicit anchors', () => {
  const ids = anchors([
    '# `GET/POST /api/v1/x` (**new**)',
    '## Repeat', '## Repeat', '## Repeat 1',
    'Setext One', '===', 'Setext', 'two lines', '---',
    '> ## Quoted',
    '<a id="dq"></a> <a id=\'sq\'></a> <a id=bare></a> <a name="named"></a>',
    '## dq',
    '```', '# not a heading', '```',
  ].join('\n'))
  for (const id of ['getpost-apiv1x-new', 'repeat', 'repeat-1', 'repeat-1-1', 'setext-one', 'setext-two-lines', 'quoted', 'dq', 'sq', 'bare', 'named']) assert.ok(ids.has(id), id)
  assert.ok(!ids.has('not-a-heading') && !ids.has('dq-1'))
  // Fragments are case-sensitive on GitHub.
  assert.deepEqual(check({ 'a.md': '## Intro\n[x](#Intro)' }), ['a.md: no heading for #Intro'])
})

test('whitespace around a destination, and a reference destination on the next line, are checked', () => {
  assert.deepEqual(check({ 'a.md': '[x]( gone1.md ) [y](\ngone2.md\n)\n\n[ref]:\n  gone3.md\n\n[z](\n\ngone4.md)' }), [
    'a.md: link to missing gone1.md',
    'a.md: link to missing gone2.md',
    'a.md: link to missing gone3.md',
  ])
  // A title on the next line, a line break before `)`, and an angle destination holding a line break
  // (not a link in CommonMark, so skipped).
  assert.deepEqual(check({ 'a.md': '[x](\ngone5.md\n"t")\n\n[w](gone6.md "t"\n)\n\n[y](<go\nne.md>)' }), [
    'a.md: link to missing gone5.md',
    'a.md: link to missing gone6.md',
  ])
})

test('empty and unclosed-angle destinations are skipped; angle-bracket references are checked', () => {
  // `[x]()` names the page itself and `[x](<a.md` is text in CommonMark: neither is a link to check.
  assert.deepEqual(linkTargets('[x]() [y](<gone.md'), [])
  assert.deepEqual(check({ 'a.md': '[x]() [y](<gone.md\n\n[ref]: <gone ref.md>' }), ['a.md: link to missing gone ref.md'])
})

test('front matter, unclosed comments and inline tags are handled as GitHub renders them', () => {
  assert.deepEqual(check({ 'a.md': '---\ntitle: x\n---\n[dead](gone.md)' }), ['a.md: link to missing gone.md'])
  const ids = anchors('---\n# not a heading\ntitle: x\n---\n# A <b>bold</b> head\n')
  assert.ok(!ids.has('not-a-heading') && !ids.has('title-x'))
  assert.ok(ids.has('a-bold-head'))
  assert.deepEqual(check({ 'a.md': '[a](gone1.md)\n<!-- never closed\n[b](gone2.md)' }), ['a.md: link to missing gone1.md'])
})

test('file existence is case-sensitive; an anchor on a non-Markdown target is not checked', () => {
  assert.deepEqual(check({ 'a.md': '[x](Docs/b.md) [y](img.png#frag)', 'docs/b.md': '', 'img.png': '' }), ['a.md: link to missing Docs/b.md'])
})

test('a CLAUDE.md may name a module without its extension', () => {
  const tree = { 'console/lib/instances/store.ts': '' }
  assert.deepEqual(check({ ...tree, 'console/CLAUDE.md': 'See `lib/instances/store`.' }), [])
  assert.deepEqual(check({ ...tree, 'console/CLAUDE.md': 'See `lib/instances/nope`.' }), ['console/CLAUDE.md: missing path `lib/instances/nope`'])
})
