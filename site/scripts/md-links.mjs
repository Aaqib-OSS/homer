// Link check over the repository's Markdown as GitHub renders it (GH-226): every relative link
// must name a file or directory, every #anchor a heading or explicit anchor of its target, and in
// a CLAUDE.md every backticked repository path must exist. md-links.test.mjs runs it over every
// tracked *.md. transform.mjs owns the published pages' rewriting for the site and its dead-link
// report; its grammar stays its own, since it rewrites lines in place while this one only resolves.
import { dirname, join, normalize } from 'node:path'

const FENCE = /^\s*(`{3,}|~{3,})/
const QUOTE = /^ {0,3}(?:> ?)+/
const ATX = /^ {0,3}#{1,6}(?:\s+(.*?))?(?:\s+#+)?\s*$/
const SETEXT = /^ {0,3}(=+|-+)\s*$/
const NOT_PARAGRAPH = /^\s*$|^ {0,3}(?:#|[-*+] |\d+[.)] |\||<|`{3}|~{3})/
const REFERENCE = /^ {0,3}\[(?!\^)[^\]]+\]:[ \t]*(<[^>\n]*>|\S+)/gm
const CODE_SPAN = /(`+)(?!`)((?:(?!\n\s*\n)[\s\S])*?[^`])\1(?!`)/g
const ANCHOR_TAG = /<a\s[^>]*>/gi
const ANCHOR_ATTR = /\b(?:id|name)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>"']+))/gi

// Removes HTML comments in one linear pass; an unclosed comment runs to the end of the text.
function dropComments(text) {
  let out = ''
  let at = 0
  for (let open = text.indexOf('<!--'); open !== -1; open = text.indexOf('<!--', at)) {
    out += text.slice(at, open)
    const close = text.indexOf('-->', open + 4)
    if (close === -1) return out
    at = close + 3
  }
  return out + text.slice(at)
}

// Drops tags (`<` then a letter or `/`, up to the next `>`) in one linear pass, as rendering does.
function dropTags(text) {
  let out = ''
  for (let i = 0; i < text.length; i++) {
    const close = /[A-Za-z/]/.test(text[i + 1] ?? '') && text[i] === '<' ? text.indexOf('>', i) : -1
    if (close === -1) out += text[i]
    else i = close
  }
  return out
}

/**
 * The lines outside fenced code, with blockquote markers, front matter and HTML comments removed.
 * @returns {{ text: string, prose: string, spans: string[] }} prose: text with inline code spans
 *   blanked; spans: their contents.
 */
export function scan(raw) {
  let fence = null
  const lines = raw.replace(/^---\n[\s\S]*?\n---\n/, '').split('\n').map((full) => {
    const line = full.replace(QUOTE, '')
    const open = FENCE.exec(line)
    if (fence) {
      if (open && open[1][0] === fence[0] && open[1].length >= fence.length && !line.trim().slice(open[1].length)) fence = null
      return ''
    }
    if (open) fence = open[1]
    return open ? '' : line
  })
  const text = dropComments(lines.join('\n'))
  const spans = []
  const prose = text.replace(CODE_SPAN, (m, _, body) => (spans.push(body.trim()), ' '.repeat(m.length)))
  return { text, prose, spans }
}

// GitHub's heading ids are the single intended target: these files are read on GitHub, and the
// anchors of the pages the site publishes are the site build's concern. Each heading's rendered
// text goes through github-slugger, including its bookkeeping for repeats; an explicit
// <a id|name> anchor is taken as written, since GitHub's slugger does not see it.
export function anchors(raw) {
  const { text, prose } = scan(raw)
  const ids = new Set()
  for (const tag of prose.match(ANCHOR_TAG) ?? []) for (const m of tag.matchAll(ANCHOR_ATTR)) ids.add(m[1] ?? m[2] ?? m[3])
  const occurrences = new Map()
  const slug = (heading) => {
    const plain = dropTags(heading.replace(/!?\[([^\]]*)\]\([^)]*\)/g, '$1')).replace(/(^|\s)[*_]+|[*_]+(\s|$)/g, '$1$2')
    const base = plain.replace(/`/g, '').trim().toLowerCase().replace(/[^\p{L}\p{M}\p{N}\p{Pc} -]/gu, '').replace(/ /g, '-')
    let id = base
    while (occurrences.has(id)) {
      occurrences.set(base, occurrences.get(base) + 1)
      id = `${base}-${occurrences.get(base)}`
    }
    occurrences.set(id, 0)
    ids.add(id)
  }
  const lines = text.split('\n')
  lines.forEach((line, i) => {
    const atx = ATX.exec(line)
    if (atx) return slug(atx[1] ?? '')
    if (!SETEXT.test(line)) return
    // A setext underline turns the whole paragraph above it into the heading.
    let start = i
    while (start > 0 && !/^\s*$|^ {0,3}#/.test(lines[start - 1]) && !SETEXT.test(lines[start - 1])) start--
    const paragraph = lines.slice(start, i)
    if (paragraph.length && !paragraph.some((l) => NOT_PARAGRAPH.test(l))) slug(paragraph.map((l) => l.trim()).join(' '))
  })
  return ids
}

// The destination of an inline link whose `](` ends at [at]: `<...>` (spaces allowed) or a run
// without spaces whose parentheses balance, followed by an optional title and `)`.
function destination(prose, at) {
  let i = at
  let target = ''
  if (prose[i] === '<') {
    const close = prose.indexOf('>', i)
    if (close === -1 || prose.slice(i, close).includes('\n')) return undefined
    target = prose.slice(i + 1, close)
    i = close + 1
  } else {
    for (let depth = 0; i < prose.length && !/\s/.test(prose[i]) && (prose[i] !== ')' || depth > 0); i++) {
      depth += prose[i] === '(' ? 1 : prose[i] === ')' ? -1 : 0
      target += prose[i]
    }
  }
  const rest = /^(?:\s+(?:"[^"]*"|'[^']*'|\([^)]*\)))?\s*\)/.exec(prose.slice(i))
  return rest && target ? target : undefined
}

export function linkTargets(prose) {
  const targets = []
  for (let at = prose.indexOf(']('); at !== -1; at = prose.indexOf('](', at + 2)) {
    const target = destination(prose, at + 2)
    if (target !== undefined) targets.push(target)
  }
  for (const m of prose.matchAll(REFERENCE)) targets.push(m[1].startsWith('<') ? m[1].slice(1, -1) : m[1])
  return targets
}

// A backtick span in a CLAUDE.md is taken as a repository path when it contains a '/', only
// [\w./@+-] characters (no space, placeholder, glob or URL), no '...' and no build/ segment (build
// output), and its first segment is '.', '..', a top-level directory, or a directory beside the
// CLAUDE.md. So `app/gradlew` is a path; `app/**`, `homer.app.Foo`, `app/gradlew -p app check`,
// `app/...` and `/api/v1/agents` are not. It resolves from the root or the CLAUDE.md's directory,
// and an extensionless module name (`lib/instances/store` for store.ts) counts.
function pathLike(span, dir, dirs) {
  if (!/^[\w.@+-][\w./@+-]*\/[\w./@+-]*$/.test(span) || span.includes('...') || /(^|\/)build\//.test(span)) return false
  const first = span.split('/')[0]
  return ['.', '..'].includes(first) || dirs.has(first) || dirs.has(join(dir, first))
}

/**
 * @param {object} repo
 * @param {string[]} repo.walk the Markdown files to check
 * @param {Iterable<string>} repo.targets every path a link may name (files; their directories follow)
 * @param {(path: string) => string} repo.read a file's contents
 * @param {Record<string, string>} [repo.linkDir] files whose links are written from another directory
 * @returns {{ problems: string[], counts: { files: number, links: number, anchors: number, paths: number } }}
 */
export function checkLinks({ walk, targets, read, linkDir = {} }) {
  const files = new Set(targets)
  const dirs = new Set([...files].flatMap((f) => f.split('/').slice(0, -1).map((_, i, a) => a.slice(0, i + 1).join('/'))))
  const modules = new Set([...files].map((f) => f.replace(/\.[^./]+$/, '')))
  const exists = (p) => p === '' || files.has(p) || dirs.has(p) // case-sensitive, as on GitHub
  const anchorCache = new Map()
  const anchorsOf = (file) => anchorCache.get(file) ?? anchorCache.set(file, anchors(read(file))).get(file)
  const counts = { files: 0, links: 0, anchors: 0, paths: 0 }
  const problems = []
  for (const file of walk) {
    const dir = linkDir[file] ?? (dirname(file) === '.' ? '' : dirname(file))
    const { prose, spans } = scan(read(file))
    counts.files++
    for (const target of linkTargets(prose).filter((t) => !/^[a-z][a-z0-9+.-]*:/i.test(t))) {
      counts.links++
      let decoded
      try {
        decoded = decodeURI(target)
      } catch {
        problems.push(`${file}: unparseable link ${target}`)
        continue
      }
      const [path, anchor] = decoded.split('#')
      const resolved = path ? normalize(join(dir, path)).replace(/\/$/, '').replace(/^\.$/, '') : file
      if (!exists(resolved)) problems.push(`${file}: link to missing ${target}`)
      if (!exists(resolved) || anchor === undefined || !resolved.endsWith('.md')) continue
      counts.anchors++
      if (!anchorsOf(resolved).has(anchor)) problems.push(`${file}: no heading for ${target}`)
    }
    if (!/(^|\/)CLAUDE\.md$/.test(file)) continue
    for (const span of spans.filter((s) => pathLike(s, dir, dirs))) {
      counts.paths++
      const found = [normalize(span), normalize(join(dir, span))].map((p) => p.replace(/\/$/, ''))
      if (!found.some((p) => exists(p) || modules.has(p))) problems.push(`${file}: missing path \`${span}\``)
    }
  }
  return { problems, counts }
}
