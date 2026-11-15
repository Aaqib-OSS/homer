import assert from 'node:assert/strict'
import { test } from 'node:test'
import GithubSlugger from 'github-slugger'
import { createMarkdownRenderer } from 'vitepress'
import { anchor } from './anchor.mjs'

const md = await createMarkdownRenderer(process.cwd(), { anchor })
const ids = (markdown) => [...md.render(markdown).matchAll(/<h[1-6] id="([^"]*)"/g)].map((m) => m[1])

test('headings with punctuation get the ids GitHub gives them', () => {
  const headings = {
    'GET/POST /api/v1/maintenance': 'getpost-apiv1maintenance',
    '`server.shutdownDrainSec`': 'servershutdowndrainsec',
    'Liveness & readiness probes': 'liveness--readiness-probes',
    "Inheriting the registering run's inputs": 'inheriting-the-registering-runs-inputs',
    'Command.Claude - Claude AI execution': 'commandclaude---claude-ai-execution',
    '1. Think Before Coding': '1-think-before-coding',
  }
  assert.deepEqual(ids(Object.keys(headings).map((h) => `## ${h}`).join('\n\n')), Object.values(headings))
})

test('repeated headings are numbered as GitHub numbers them, afresh on every page', () => {
  // Every sequence of up to four headings drawn from texts whose slugs collide with each other's
  // numbered forms; each render is a new page, so a fresh slugger is its reference.
  const texts = ['a', 'a-1', 'A', 'a 1', 'a-2', 'b']
  let sequences = [[]]
  for (let length = 1; length <= 4; length++) {
    sequences = sequences.flatMap((s) => texts.map((t) => [...s, t]))
    for (const sequence of sequences) {
      const slugger = new GithubSlugger()
      assert.deepEqual(
        ids(sequence.map((t, i) => `${'#'.repeat((i % 3) + 1)} ${t}`).join('\n\n')),
        sequence.map((t) => slugger.slug(t)),
        JSON.stringify(sequence),
      )
    }
  }
})
