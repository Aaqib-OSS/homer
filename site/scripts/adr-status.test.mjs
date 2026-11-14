// An ADR that code or a CLAUDE.md cites is a decision in force, so it must not read `proposed`; and
// the index in docs/adr/README.md must show the same status as each ADR's front matter.
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

const frontMatterStatus = (text) => text.match(/^---\n[\s\S]*?^status: (.+)$/m)?.[1].trim()

// `| [NNNN](NNNN-title.md) | Title | status |` rows of the index table.
function indexStatuses(readme) {
  return new Map([...readme.matchAll(/^\| \[(\d{4})\]\([^)]*\) \|.*\| ([^|]+) \|$/gm)].map((m) => [m[1], m[2].trim()]))
}

function adrStatuses() {
  const statuses = new Map()
  for (const name of readdirSync(adrDir)) {
    const number = name.match(/^(\d{4})-.*\.md$/)?.[1]
    if (number && number !== '0000') statuses.set(number, frontMatterStatus(readFileSync(join(adrDir, name), 'utf8')))
  }
  return statuses
}

test('both reference forms are recognised', () => {
  assert.deepEqual(citedNumbers('See ADR-9998 and [x](../../docs/adr/9999-a-b.md); not ADR-XXXX.'), ['9998', '9999'])
  assert.deepEqual([...indexStatuses('| [9998](9998-a.md) | A \\| B | accepted |\n').entries()], [['9998', 'accepted']])
})

test('no ADR cited from code or a CLAUDE.md is still proposed', () => {
  const files = execFileSync('git', ['ls-files'], { cwd: repo, encoding: 'utf8' }).split('\n').filter((f) => CITING.test(f))
  const statuses = adrStatuses()
  const proposed = []
  for (const file of files) {
    for (const number of new Set(citedNumbers(readFileSync(join(repo, file), 'utf8')))) {
      if (statuses.get(number) === 'proposed') proposed.push(`ADR-${number} cited from ${file}`)
    }
  }
  assert.ok(files.length > 0, 'git ls-files listed no citing file')
  assert.deepEqual(proposed, [], 'flip these ADRs to `accepted` (front matter and index)')
})

test('the index status of every ADR matches its front matter', () => {
  const statuses = adrStatuses()
  const index = indexStatuses(readFileSync(join(adrDir, 'README.md'), 'utf8'))
  const disagree = [...new Set([...statuses.keys(), ...index.keys()])]
    .filter((number) => statuses.get(number) !== index.get(number))
    .map((number) => `${number}: front matter ${statuses.get(number)}, index ${index.get(number)}`)
  assert.ok(statuses.size > 0, 'no ADR found')
  assert.deepEqual(disagree, [])
})
