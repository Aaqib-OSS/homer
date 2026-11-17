import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import { existsSync, statSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { test } from 'node:test'
import { fileURLToPath } from 'node:url'
import { CAPS, findings, slack } from './claude-md-budget.mjs'

const root = join(dirname(fileURLToPath(import.meta.url)), '../..')

test('slack is 5% of the cap rounded up, at least 1', () => {
  assert.equal(slack(1000), 50)
  assert.equal(slack(1001), 51)
  assert.equal(slack(20), 1)
  assert.equal(slack(1), 1)
  assert.equal(slack(0), 1)
})

test('a size at its cap, or within the slack below it, passes', () => {
  assert.deepEqual(findings({ 'a/CLAUDE.md': 1000 }, { 'a/CLAUDE.md': 1000 }), [])
  assert.deepEqual(findings({ 'a/CLAUDE.md': 1000 }, { 'a/CLAUDE.md': 950 }), [])
})

test('a cap more than the slack above the size fails and names the value to lower it to', () => {
  assert.deepEqual(findings({ 'a/CLAUDE.md': 1000 }, { 'a/CLAUDE.md': 949 }), [
    "lower a/CLAUDE.md's cap to 949 (it is 1000, more than 50 above the current size)",
  ])
  assert.deepEqual(findings({ 'a/CLAUDE.md': 1 }, { 'a/CLAUDE.md': 0 }), [])
  assert.equal(findings({ 'a/CLAUDE.md': 2 }, { 'a/CLAUDE.md': 0 }).length, 1)
})

test('a guide over its cap fails and names the file, size and cap', () => {
  assert.deepEqual(findings({ 'a/CLAUDE.md': 1000 }, { 'a/CLAUDE.md': 1001 }), [
    'a/CLAUDE.md is 1001 bytes, over its cap 1000: trim it (or raise the cap in claude-md-budget.mjs with a reason in the commit)',
  ])
})

test('a tracked guide without a cap, and a cap without a tracked guide, fail', () => {
  assert.deepEqual(findings({}, { 'new/CLAUDE.md': 10 }), ['add a cap for new/CLAUDE.md (10 bytes) in claude-md-budget.mjs'])
  assert.deepEqual(findings({ 'gone/CLAUDE.md': 10 }, {}), ['remove the cap for gone/CLAUDE.md: no such CLAUDE.md'])
})

test('every tracked CLAUDE.md is within its cap, and no cap is stale', () => {
  const paths = execFileSync('git', ['-C', root, 'ls-files', '-z', '--cached', '*CLAUDE.md'], { encoding: 'utf8' }).split('\0').filter((p) => p === 'CLAUDE.md' || p.endsWith('/CLAUDE.md'))
  assert.ok(paths.length >= 7, `found only ${paths.length} CLAUDE.md files`)
  const sizes = {}
  for (const p of paths) {
    if (existsSync(join(root, p))) sizes[p] = statSync(join(root, p)).size // a deleted guide: findings names its stale cap
  }
  assert.deepEqual(findings(CAPS, sizes), [])
})
