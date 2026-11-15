import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { mkdirSync, mkdtempSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join } from 'node:path'
import { test } from 'node:test'
import { fileURLToPath } from 'node:url'
import { missingAnchors } from './check-anchors.mjs'

// A built site: each page is its path → its HTML.
function dist(pages) {
  const dir = mkdtempSync(join(tmpdir(), 'homer-anchors-'))
  for (const [path, html] of Object.entries(pages)) {
    mkdirSync(dirname(join(dir, path)), { recursive: true })
    writeFileSync(join(dir, path), html)
  }
  return dir
}

const server = '<h2 id="servershutdowndrainsec">x</h2><h2 id="liveness--readiness-probes">y</h2><div id="café">z</div>'

test('a fragment the target page holds passes, in-page, relative, absolute and to an index page', () => {
  const site = dist({
    'operate/server.html': `${server}<a href="#servershutdowndrainsec">a</a><a href="#caf%C3%A9">b</a>`,
    'guide/backend.html': '<a href="./../operate/server.html#liveness--readiness-probes">c</a><a href="/operate/server#servershutdowndrainsec">d</a><a href="../case-studies/#top">e</a>',
    'case-studies/index.html': '<h1 id="top">t</h1>',
  })
  assert.deepEqual(missingAnchors(site), [])
})

test('a fragment the target page does not hold is reported with the page that links it', () => {
  const site = dist({
    'operate/server.html': `${server}<a href="#server-shutdown-drain-sec">a</a>`,
    'guide/backend.html': '<a href="./../operate/server.html#servershutdowndrainsec-1">b</a><a href="#%E0%A4%A">c</a>',
  })
  assert.deepEqual(missingAnchors(site).sort(), [
    'guide/backend.html: #%E0%A4%A',
    'guide/backend.html: ./../operate/server.html#servershutdowndrainsec-1',
    'operate/server.html: #server-shutdown-drain-sec',
  ])
})

test('the base path is stripped from absolute links; links off the site are not checked', () => {
  const site = dist({
    'operate/server.html': server,
    'index.html': [
      '<a href="/homer/next/operate/server.html#nope">a</a>',
      '<a href="/other/operate/server.html#nope">b</a>',
      '<a href="https://github.com/livesport/homer/blob/main/app/SERVER.md#nope">c</a>',
      '<a href="//example.com/x.html#nope">d</a>',
      '<a href="./_repo/docs/x.svg#nope">e</a>',
      '<a href="#">top</a>',
    ].join(''),
  })
  assert.deepEqual(missingAnchors(site, '/homer/next/'), ['index.html: /homer/next/operate/server.html#nope'])
})

test('links without a fragment, with an undecodable path, or leaving the site are not checked', () => {
  const site = dist({
    // Outside the built site: a link that reached it would be reported, since it has no id x.
    'out.html': '<p>outside</p>',
    'site/index.html': [
      '<a href="./guide/backend.html">no fragment</a>',
      // decodeURI rejects the path, so it names no page of the site and is skipped; a dead page
      // link is VitePress's own check.
      '<a href="%zz.html#x">malformed</a>',
      '<a href="../out.html#x">up</a>',
      '<a href="/../out.html#x">absolute up</a>',
    ].join(''),
    'site/guide/backend.html': '<a href="../../out.html#x">up twice</a>',
  })
  assert.deepEqual(missingAnchors(join(site, 'site')), [])
})

test('a directory named like a page is not read as one; its index page is the target', () => {
  const site = dist({
    'docs.html/index.html': '<h1 id="x">x</h1>',
    'index.html': '<a href="docs.html#x">a</a><a href="docs.html#y">b</a>',
  })
  assert.deepEqual(missingAnchors(site), ['index.html: docs.html#y'])
})

test('entities in ids and hrefs are decoded before they are compared', () => {
  const site = dist({ 'index.html': '<h2 id="a&amp;b">x</h2><a href="#a&amp;b">a</a><a href="#a%26b">b</a><a href="#a&amp;c">c</a>' })
  assert.deepEqual(missingAnchors(site), ['index.html: #a&c'])
})

test('run as a script on a clean site, it exits 0 and says so', () => {
  const site = dist({ 'index.html': '<h1 id="here">h</h1><a href="#here">a</a>' })
  const script = fileURLToPath(new URL('./check-anchors.mjs', import.meta.url))
  const run = spawnSync(process.execPath, [script, site], { encoding: 'utf8' })
  assert.equal(run.status, 0)
  assert.match(run.stdout, /Every link to a section lands on it\./)
})

test('run as a script, it exits 1 and names each miss', () => {
  const site = dist({ 'index.html': '<a href="#gone">a</a>' })
  const script = fileURLToPath(new URL('./check-anchors.mjs', import.meta.url))
  const run = spawnSync(process.execPath, [script, site], { encoding: 'utf8' })
  assert.equal(run.status, 1)
  assert.match(run.stderr, /index\.html: #gone/)
})
