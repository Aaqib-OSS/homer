import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import { existsSync, readFileSync } from 'node:fs'
import { dirname, join, normalize } from 'node:path'
import { test } from 'node:test'
import { fileURLToPath } from 'node:url'

// Every tracked markdown file, not only the pages the site publishes: the CLAUDE.md guides are
// read on GitHub and loaded into every agent session, so a stale path there misleads silently.
const root = join(dirname(fileURLToPath(import.meta.url)), '../..')
const git = (...args) => execFileSync('git', ['-C', root, 'ls-files', '-z', ...args], { encoding: 'utf8' }).split('\0').filter(Boolean)
const files = new Set(git('--cached', '--others', '--exclude-standard').filter((f) => existsSync(join(root, f))))
const dirs = new Set([...files].flatMap((f) => f.split('/').slice(0, -1).map((_, i, a) => a.slice(0, i + 1).join('/'))))
const exists = (p) => p === '' || files.has(p) || dirs.has(p) // case-sensitive, as on GitHub and Linux CI
const modules = new Set([...files].map((f) => f.replace(/\.[^./]+$/, ''))) // `lib/instances/store` names store.ts
const topDirs = new Set([...dirs].filter((d) => !d.includes('/')))

// Fenced blocks and HTML comments are dropped (text); prose also blanks inline code spans, returned apart.
function scan(raw) {
  const spans = []
  let fence = null
  const lines = raw.split('\n').map((line) => {
    const open = line.match(/^\s*(`{3,}|~{3,})/)
    if (fence) return (open && open[1][0] === fence[0] && open[1].length >= fence.length && !line.trim().slice(open[1].length) && (fence = null), '')
    if (open) return ((fence = open[1]), '')
    return line
  })
  const text = lines.join('\n').replace(/<!--[\s\S]*?-->/g, '')
  return { text, spans, prose: text.replace(/(`+)(?!`)((?:(?!\n\s*\n)[\s\S])*?[^`])\1(?!`)/g, (m, _, body) => (spans.push(body.trim()), ' '.repeat(m.length))) }
}

// GitHub's heading ids (github-slugger over the rendered text), plus explicit <a id|name="..."> anchors.
const anchorCache = new Map()
function anchors(file) {
  if (anchorCache.has(file)) return anchorCache.get(file)
  const raw = readFileSync(join(root, file), 'utf8')
  const ids = new Set([...raw.matchAll(/<a\s[^>]*\b(?:id|name)="([^"]+)"/g)].map((m) => m[1]))
  const seen = new Map()
  for (const [, heading] of scan(raw).text.matchAll(/^ {0,3}#{1,6}\s+(.*?)(?:\s+#+)?\s*$/gm)) {
    const text = heading.replace(/!?\[([^\]]*)\]\([^)]*\)/g, '$1').replace(/<[^>]+>/g, '').replace(/(^|\s)[*_]+|[*_]+(\s|$)/g, '$1$2')
    const base = text.replace(/`/g, '').trim().toLowerCase().replace(/[^\p{L}\p{M}\p{N}\p{Pc} -]/gu, '').replace(/ /g, '-')
    const n = seen.get(base) ?? 0
    seen.set(base, n + 1)
    ids.add(n ? `${base}-${n}` : base)
  }
  anchorCache.set(file, ids)
  return ids
}

// Files whose links are not written from their own directory. site/pages/** is skipped: its links
// name built-site routes, which the site build's dead-link check covers.
const linkDir = {
  '.github/pull_request_template.md': '', // pasted into a pull request body; written from the root
  'plugins/homer-authoring/skills/homer-agent-authoring/reference/AGENT.md': 'app', // copy of app/AGENT.md
}

// A backtick span in a CLAUDE.md is taken as a repository path when it contains a '/', only
// [\w./@+-] characters (no space, placeholder, glob or URL), no '...' and no build/ segment (build
// output), and its first segment is '.', '..', a top-level directory, or a directory beside the
// CLAUDE.md. So `app/gradlew` is a path; `app/**`, `homer.app.Foo`, `app/gradlew -p app check`,
// `app/...` and `/api/v1/agents` are not. It resolves from the root or the CLAUDE.md's directory.
const pathLike = (s, dir) => {
  if (!/^[\w.@+-][\w./@+-]*\/[\w./@+-]*$/.test(s) || s.includes('...') || /(^|\/)build\//.test(s)) return false
  const first = s.split('/')[0]
  return ['.', '..'].includes(first) || topDirs.has(first) || dirs.has(join(dir, first))
}

const counts = { files: 0, links: 0, anchors: 0, paths: 0 }
const problems = []
for (const file of git('*.md').filter((f) => files.has(f) && !f.startsWith('site/pages/'))) {
  const dir = linkDir[file] ?? (dirname(file) === '.' ? '' : dirname(file))
  const { prose, spans } = scan(readFileSync(join(root, file), 'utf8'))
  const targets = [...prose.matchAll(/\]\(\s*<?([^)\s>]+)>?(?:\s+"[^"]*")?\s*\)/g), ...prose.matchAll(/^ {0,3}\[(?!\^)[^\]]+\]:\s*<?([^\s>]+)>?/gm)]
  counts.files++
  for (const [, target] of targets) {
    if (/^[a-z][a-z0-9+.-]*:/i.test(target)) continue
    counts.links++
    const [path, anchor] = decodeURI(target).split('#')
    const resolved = path ? normalize(join(dir, path)).replace(/\/$/, '').replace(/^\.$/, '') : file
    if (!exists(resolved)) problems.push(`${file}: link to missing ${target}`)
    if (!exists(resolved) || anchor === undefined || !resolved.endsWith('.md')) continue
    counts.anchors++
    const ids = anchors(resolved)
    if (!ids.has(anchor) && !ids.has(anchor.toLowerCase())) problems.push(`${file}: no heading for ${target}`)
  }
  if (!/(^|\/)CLAUDE\.md$/.test(file)) continue
  for (const span of spans.filter((s) => pathLike(s, dir))) {
    counts.paths++
    const found = [normalize(span), normalize(join(dir, span))].map((p) => p.replace(/\/$/, ''))
    if (!found.some((p) => exists(p) || modules.has(p))) problems.push(`${file}: missing path \`${span}\``)
  }
}

test('every relative link, heading anchor and CLAUDE.md path in tracked markdown resolves', () => {
  if (process.env.MD_LINKS_VERBOSE) console.log(counts)
  assert.ok(counts.files > 100 && counts.links > 1000, `walked too little: ${JSON.stringify(counts)}`)
  assert.deepEqual(problems, [])
})
