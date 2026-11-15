// An ADR that code or a CLAUDE.md cites is a decision in force, so it must not read `proposed`; the
// index in docs/adr/README.md must show the same status as each ADR's front matter, link an existing
// file and cover every file; a heading must carry its filename's number, the numbering must have no
// hole; and an ADR amended in place must date that amendment in its front matter.
import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import { readdirSync, readFileSync } from 'node:fs'
import { join } from 'node:path'
import { test } from 'node:test'
import { fileURLToPath } from 'node:url'

const repo = fileURLToPath(new URL('../..', import.meta.url))
const adrDir = join(repo, 'docs', 'adr')

// Tracked source code, workflows and every CLAUDE.md: the files whose ADR references an agent acts on.
const CITING = /(\.(kt|kts|ts|tsx|mjs|py|sh)$|^\.github\/workflows\/.*\.ya?ml$|(^|\/)CLAUDE\.md$)/

// `ADR-NNNN`, or a link to `.../adr/NNNN-some-title.md`.
function citedNumbers(text) {
  return [...text.matchAll(/ADR-(\d{4})\b|adr\/(\d{4})-[\w.-]*\.md/g)].map((m) => m[1] ?? m[2])
}

// The `<key>:` line inside the leading `---` block; undefined when there is none.
const frontMatterField = (text, key) =>
  text.match(/^---\n([\s\S]*?)\n---\n/)?.[1].match(new RegExp(`^${key}: (.+)$`, 'm'))?.[1].trim()

// A cited ADR must not read `proposed`, and must have a status at all.
function proposedCitations(textsByFile, statuses) {
  const found = []
  for (const [file, text] of textsByFile) {
    for (const number of new Set(citedNumbers(text))) {
      const status = statuses.get(number)
      if (statuses.has(number) && (status === undefined || status === 'proposed')) found.push(`ADR-${number} cited from ${file}`)
    }
  }
  return found
}

// The four statuses docs/adr/README.md defines.
const KNOWN_STATUS = /^(proposed|accepted|deprecated|superseded by ADR-\d{4})$/

const unknownStatuses = (statuses) =>
  [...statuses].filter(([, status]) => status !== undefined && !KNOWN_STATUS.test(status)).map(([number, status]) => `${number}: ${status}`)

function indexDisagreements(statuses, index) {
  return [...new Set([...statuses.keys(), ...index.keys()])]
    .filter((number) => statuses.get(number) !== index.get(number))
    .map((number) => `${number}: front matter ${statuses.get(number)}, index ${index.get(number)}`)
}

// `| [NNNN](NNNN-title.md) | Title | status |` rows of the index table: number -> {file, status}.
function indexRows(readme) {
  return new Map([...readme.matchAll(/^\| \[(\d{4})\]\(([^)]*)\) \|.*\| ([^|]+) \|$/gm)].map((m) => [m[1], { file: m[2], status: m[3].trim() }]))
}

const indexStatuses = (readme) => new Map([...indexRows(readme)].map(([number, row]) => [number, row.status]))

// The `NNNN-title.md` files under docs/adr, the `0000` template excluded: number -> {name, text}.
function adrDocs() {
  const docs = new Map()
  for (const name of readdirSync(adrDir)) {
    const number = name.match(/^(\d{4})-.*\.md$/)?.[1]
    if (number && number !== '0000') docs.set(number, { name, text: readFileSync(join(adrDir, name), 'utf8') })
  }
  return docs
}

const adrStatuses = () => new Map([...adrDocs()].map(([number, { text }]) => [number, frontMatterField(text, 'status')]))

// Every index row links the file its number names, and every file has a row.
function indexFileMismatches(docs, index) {
  const found = [...index]
    .filter(([number, row]) => docs.get(number)?.name !== row.file)
    .map(([number, row]) => `${number}: index links ${row.file}, ${docs.has(number) ? `the file is ${docs.get(number).name}` : 'no such file'}`)
  for (const [number, { name }] of docs) if (!index.has(number)) found.push(`${number}: ${name} has no index row`)
  return found
}

// The first `# ADR-NNNN` heading carries the number the filename does.
function headingMismatches(docs) {
  const found = []
  for (const [number, { name, text }] of docs) {
    const heading = text.match(/^# ADR-(\d{4})\b/m)?.[1]
    if (heading !== number) found.push(`${name}: heading ${heading === undefined ? 'is missing' : `is ADR-${heading}`}`)
  }
  return found
}

// A hole in 0001..max needs a reason here; docs/adr/README.md never reuses a number, so there is none.
const GAP_REASONS = new Map()

function numberingGaps(docs, reasons = GAP_REASONS) {
  const found = []
  for (let n = 1; n <= Math.max(0, ...[...docs.keys()].map(Number)); n++) {
    const number = String(n).padStart(4, '0')
    if (!docs.has(number) && !reasons.has(number)) found.push(`${number}: missing, and no reason listed`)
  }
  return found
}

// An `Amendment` heading at any level, or a `> **Amendment` blockquote, amends a decision in place.
const AMENDMENT = /^(?:#{2,6} Amendment|> \*\*Amendment)/m
// The dated `- YYYY-MM-DD:` bullets a plural `## Amendments` section lists, until the next heading
// of the section's level or above ends it.
const bulletDates = (text) => {
  const dates = []
  let level = 0
  for (const line of text.split('\n')) {
    const heading = line.match(/^(#{2,6}) Amendments\s*$/)
    if (heading !== null) level = heading[1].length
    else if (level !== 0 && new RegExp(`^#{1,${level}} `).test(line)) level = 0
    else if (level !== 0) {
      const date = line.match(/^\s*- (\d{4}-\d{2}-\d{2}):/)?.[1]
      if (date !== undefined) dates.push(date)
    }
  }
  return dates
}
// The date each amendment carries: a singular heading, a blockquote, or a dated bullet under a
// plural `## Amendments` heading — the list README.md calls "the same thing".
const amendmentDates = (text) => [
  ...[...text.matchAll(/^(?:#{2,6} Amendment[^\n]*|> \*\*Amendment[^\n]*)/gm)]
    .map((line) => line[0].match(/\d{4}-\d{2}-\d{2}/)?.[0])
    .filter((date) => date !== undefined),
  ...bulletDates(text),
]
// `YYYY-MM-DD` with the month and day ranges: a date, not a run of digits.
const DATE = /^\d{4}-(?:0[1-9]|1[0-2])-(?:0[1-9]|[12]\d|3[01])$/
const ADR_NUMBER = /^ADR-\d{4}$/
const splitList = (value) => value.split(',').map((item) => item.trim())

// Every amendment is dated, listed and ascending, and every `superseded-by:` names an existing ADR file.
function unmarkedAmendments(docs) {
  const found = []
  for (const [number, { text }] of docs) {
    if (!AMENDMENT.test(text)) continue
    const amended = frontMatterField(text, 'amended')
    const dates = amended === undefined ? [] : splitList(amended)
    const unlisted = amendmentDates(text).filter((date) => !dates.includes(date))
    if (amended === undefined) found.push(`${number}: an amendment with no \`amended:\``)
    else if (!dates.every((date) => DATE.test(date))) found.push(`${number}: \`amended: ${amended}\` is not a comma-separated YYYY-MM-DD list`)
    else if (dates.join() !== [...dates].sort().join()) found.push(`${number}: \`amended: ${amended}\` is not ascending`)
    else if (unlisted.length > 0) found.push(`${number}: \`amended: ${amended}\` does not list ${unlisted.join(', ')}`)
    const supersededBy = frontMatterField(text, 'superseded-by')
    if (supersededBy === undefined) continue
    const named = splitList(supersededBy)
    if (!named.every((adr) => ADR_NUMBER.test(adr))) found.push(`${number}: \`superseded-by: ${supersededBy}\` is not a comma-separated ADR-NNNN list`)
    else if (named.some((adr) => !docs.has(adr.slice(4)))) found.push(`${number}: \`superseded-by: ${supersededBy}\` names no ADR file`)
  }
  return found
}

const withFrontMatter = (frontMatter, body) => `---\n${frontMatter}\n---\n${body}`

test('both reference forms are recognised', () => {
  assert.deepEqual(citedNumbers('See ADR-9998 and [x](../../docs/adr/9999-a-b.md); not ADR-XXXX.'), ['9998', '9999'])
})

test('an index row yields its number, its linked file and its status', () => {
  const readme = '| [9998](9998-a.md) | A \\| B | accepted |\n'
  assert.deepEqual([...indexRows(readme).entries()], [['9998', { file: '9998-a.md', status: 'accepted' }]])
  assert.deepEqual([...indexStatuses(readme).entries()], [['9998', 'accepted']])
})

test('a front-matter key is read only from the leading block, and the amendment keys are not a status', () => {
  const text = withFrontMatter('status: accepted\namended: 2026-10-09\nsuperseded-by: ADR-0035', '# T\n')
  assert.equal(frontMatterField(text, 'status'), 'accepted')
  assert.equal(frontMatterField(text, 'amended'), '2026-10-09')
  assert.equal(frontMatterField(text, 'superseded-by'), 'ADR-0035')
  assert.deepEqual(unknownStatuses(new Map([['9999', frontMatterField(text, 'status')]])), [])
  assert.equal(frontMatterField(withFrontMatter('date: x', '# T\n'), 'status'), undefined)
  assert.equal(frontMatterField('---\ndate: x\n---\nstatus: accepted\n', 'status'), undefined)
  assert.equal(frontMatterField('# T\nstatus: accepted\n', 'status'), undefined)
  assert.equal(frontMatterField('---\nstatus: accepted\n---\namended: 2026-10-09\n', 'amended'), undefined)
})

test('the guards flag a proposed or status-less citation and an index disagreement', () => {
  const statuses = new Map([['9996', 'accepted'], ['9997', 'proposed'], ['9998', undefined], ['9999', 'deprecated']])
  const texts = new Map([['a.kt', 'ADR-9996 ADR-9997 ADR-9998 ADR-9999 ADR-9995']])
  assert.deepEqual(proposedCitations(texts, statuses), ['ADR-9997 cited from a.kt', 'ADR-9998 cited from a.kt'])
  const index = new Map([['9996', 'accepted'], ['9997', 'accepted'], ['9998', undefined], ['9999', 'deprecated'], ['9995', 'accepted']])
  assert.deepEqual(indexDisagreements(statuses, index), ['9997: front matter proposed, index accepted', '9995: front matter undefined, index accepted'])
  const vocabulary = new Map([['9993', 'superseded by ADR-0001'], ['9994', 'accpeted'], ['9999', 'deprecated']])
  assert.deepEqual(unknownStatuses(vocabulary), ['9994: accpeted'])
})

test('an index row without its file, and a file without its row, are flagged', () => {
  const docs = new Map([['9996', { name: '9996-a.md', text: '' }], ['9997', { name: '9997-b.md', text: '' }]])
  const index = new Map([['9996', { file: '9996-other.md', status: 'accepted' }], ['9995', { file: '9995-c.md', status: 'accepted' }]])
  assert.deepEqual(indexFileMismatches(docs, index), [
    '9996: index links 9996-other.md, the file is 9996-a.md',
    '9995: index links 9995-c.md, no such file',
    '9997: 9997-b.md has no index row',
  ])
})

test('a heading number that differs from its filename, or is missing, is flagged', () => {
  const docs = new Map([
    ['9997', { name: '9997-a.md', text: '# ADR-9997: A\n' }],
    ['9998', { name: '9998-b.md', text: '# ADR-9999: B\n' }],
    ['9996', { name: '9996-c.md', text: '## Not a heading\n' }],
  ])
  assert.deepEqual(headingMismatches(docs), ['9998-b.md: heading is ADR-9999', '9996-c.md: heading is missing'])
})

test('a hole in the numbering is flagged unless a reason is listed', () => {
  // The guard walks 0001..max, so the whole answer is asserted on a low span; the 99xx pair
  // below would flag every number under it too.
  const docs = new Map([['0001', { name: '0001-a.md', text: '' }], ['0003', { name: '0003-c.md', text: '' }]])
  assert.deepEqual(numberingGaps(docs), ['0002: missing, and no reason listed'])
  assert.deepEqual(numberingGaps(docs, new Map([['0002', 'withdrawn before merge']])), [])
  const sparse = new Map([['9996', { name: '9996-a.md', text: '' }], ['9998', { name: '9998-b.md', text: '' }]])
  assert.ok(numberingGaps(sparse).includes('9997: missing, and no reason listed'), '9997 is missing between 9996 and 9998')
  assert.ok(!numberingGaps(sparse, new Map([['9997', 'withdrawn before merge']])).includes('9997: missing, and no reason listed'))
})

test('an amendment must be dated and a supersession must name an ADR file', () => {
  const amendment = '## Amendment (2026-01-02): x\n'
  const docs = new Map([
    ['9994', { name: '9994-plain.md', text: withFrontMatter('status: accepted', '# ADR-9994: Plain\n') }],
    ['9995', { name: '9995-dated.md', text: withFrontMatter('status: accepted\namended: 2026-01-02', `# ADR-9995: Dated\n\n${amendment}`) }],
    ['9996', { name: '9996-undated.md', text: withFrontMatter('status: accepted', `# ADR-9996: Undated\n\n${amendment}`) }],
    ['9997', { name: '9997-malformed.md', text: withFrontMatter('status: accepted\namended: 2026-13-99', `# ADR-9997: Malformed\n\n${amendment}`) }],
    ['9998', { name: '9998-descending.md', text: withFrontMatter('status: accepted\namended: 2026-02-02, 2026-01-01', `# ADR-9998: Descending\n\n${amendment}`) }],
    ['9999', { name: '9999-missing.md', text: withFrontMatter('status: accepted\namended: 2026-01-02\nsuperseded-by: ADR-9990', `# ADR-9999: Missing\n\n${amendment}`) }],
    ['9993', { name: '9993-nonsense.md', text: withFrontMatter('status: accepted\namended: 2026-01-02\nsuperseded-by: foo', `# ADR-9993: Nonsense\n\n${amendment}`) }],
    ['9992', { name: '9992-subheading.md', text: withFrontMatter('status: accepted', '# ADR-9992: Subheading\n\n### Amendment 2026-01-02 — later\n') }],
    ['9991', { name: '9991-partial.md', text: withFrontMatter('status: accepted\namended: 2026-01-02', '# ADR-9991: Partial\n\n> **Amendment — no date of its own.**\n\n## Amendment 2026-01-02 — one\n\n### Amendment 2026-03-04 — two\n') }],
    ['9989', { name: '9989-list.md', text: withFrontMatter('status: accepted\namended: 2026-02-01', '# ADR-9989: List\n\n## Amendments\n\n- 2026-02-01: one\n- 2026-02-03: two\n') }],
    ['9988', { name: '9988-scoped.md', text: withFrontMatter('status: accepted\namended: 2026-02-01', '# ADR-9988: Scoped\n\n## Amendments\n\n- 2026-02-01: one\n\n## Notes\n\n- 2026-02-03: not an amendment, the section ended above\n') }],
  ])
  assert.deepEqual(unmarkedAmendments(docs), [
    '9996: an amendment with no `amended:`',
    '9997: `amended: 2026-13-99` is not a comma-separated YYYY-MM-DD list',
    '9998: `amended: 2026-02-02, 2026-01-01` is not ascending',
    '9999: `superseded-by: ADR-9990` names no ADR file',
    '9993: `superseded-by: foo` is not a comma-separated ADR-NNNN list',
    '9992: an amendment with no `amended:`',
    '9991: `amended: 2026-01-02` does not list 2026-03-04',
    '9989: `amended: 2026-02-01` does not list 2026-02-03',
  ])
})

test('every ADR status is one the ADR README defines', () => {
  assert.deepEqual(unknownStatuses(adrStatuses()), [])
})

test('no ADR cited from code or a CLAUDE.md is still proposed', () => {
  const files = execFileSync('git', ['ls-files'], { cwd: repo, encoding: 'utf8' }).split('\n').filter((f) => CITING.test(f))
  const proposed = proposedCitations(new Map(files.map((f) => [f, readFileSync(join(repo, f), 'utf8')])), adrStatuses())
  assert.ok(files.length > 0, 'git ls-files listed no citing file')
  assert.deepEqual(proposed, [], 'flip these ADRs to `accepted` (front matter and index)')
})

test('the index status of every ADR matches its front matter', () => {
  const statuses = adrStatuses()
  const index = indexStatuses(readFileSync(join(adrDir, 'README.md'), 'utf8'))
  assert.ok(statuses.size > 0, 'no ADR found')
  assert.deepEqual(indexDisagreements(statuses, index), [])
})

test('every index row links its ADR file, and every ADR file has a row', () => {
  const docs = adrDocs()
  const index = indexRows(readFileSync(join(adrDir, 'README.md'), 'utf8'))
  assert.ok(docs.size > 0, 'no ADR found')
  assert.deepEqual(indexFileMismatches(docs, index), [])
})

test('every ADR heading carries its filename number', () => {
  const docs = adrDocs()
  assert.ok(docs.size > 0, 'no ADR found')
  assert.deepEqual(headingMismatches(docs), [])
})

test('the ADR numbering has no unexplained hole', () => {
  const docs = adrDocs()
  assert.ok(docs.size > 0, 'no ADR found')
  assert.deepEqual(numberingGaps(docs), [])
})

test('every amended ADR is marked in its front matter', () => {
  const docs = adrDocs()
  const amended = [...docs.values()].filter(({ text }) => AMENDMENT.test(text))
  assert.ok(amended.length > 0, 'no amendment found: has the marker moved?')
  assert.ok([...docs.values()].some(({ text }) => bulletDates(text).length > 0), 'no dated bullet under a plural `## Amendments` section: has the form moved?')
  assert.deepEqual(unmarkedAmendments(docs), [])
})
