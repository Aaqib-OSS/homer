// Fails the site build on a link whose #fragment names no heading or element id on the page it
// points at. VitePress's own check covers the page, not the fragment, so a deep link that misses
// opens at the top of the page instead of failing.
//
// Usage: node scripts/check-anchors.mjs [DIST]   (default: site/.vitepress/dist)
// Env: DOCS_BASE, the path the build is served under (as in the VitePress config).
import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs'
import { dirname, join, posix } from 'node:path'
import { fileURLToPath } from 'node:url'

const ID = /\sid="([^"]*)"/g
const HREF = /\shref="([^"]*)"/g
const ENTITIES = { amp: '&', quot: '"', apos: "'", lt: '<', gt: '>' }

const unescape = (text) =>
  text.replace(/&(?:#(\d+)|#x([0-9a-f]+)|(\w+));/gi, (all, dec, hex, name) =>
    dec ? String.fromCodePoint(Number(dec)) : hex ? String.fromCodePoint(parseInt(hex, 16)) : (ENTITIES[name] ?? all),
  )

/**
 * Every link in the built site at [dist] whose fragment the target page does not hold, as
 * "<page>: <href>". Only links to the site's own HTML pages are checked.
 */
export function missingAnchors(dist, base = '/') {
  const pages = readdirSync(dist, { recursive: true })
    .map((name) => name.split('\\').join('/'))
    .filter((name) => name.endsWith('.html'))
  const ids = new Map()
  const idsOf = (page) => {
    if (!ids.has(page)) {
      const html = readFileSync(join(dist, page), 'utf8')
      ids.set(page, new Set([...html.matchAll(ID)].map((m) => unescape(m[1]))))
    }
    return ids.get(page)
  }
  const missing = []
  for (const page of pages) {
    for (const [, raw] of readFileSync(join(dist, page), 'utf8').matchAll(HREF)) {
      const href = unescape(raw)
      const hashAt = href.indexOf('#')
      if (hashAt < 0 || hashAt === href.length - 1 || /^[a-z][a-z0-9+.-]*:|^\/\//i.test(href)) continue
      const target = targetPage(dist, page, href.slice(0, hashAt), base)
      if (target === undefined) continue
      let fragment
      try {
        fragment = decodeURIComponent(href.slice(hashAt + 1))
      } catch {
        fragment = undefined
      }
      if (fragment === undefined || !idsOf(target).has(fragment)) missing.push(`${page}: ${href}`)
    }
  }
  return missing
}

/** The built page [path] points at from [page], or undefined when it is not one of the site's pages. */
function targetPage(dist, page, path, base) {
  if (path === '') return page
  let resolved
  if (path.startsWith('/')) {
    if (!path.startsWith(base)) return undefined
    resolved = posix.normalize(path.slice(base.length))
  } else {
    resolved = posix.normalize(posix.join(posix.dirname(page), path))
  }
  try {
    resolved = decodeURI(resolved)
  } catch {
    return undefined
  }
  if (resolved.startsWith('../')) return undefined
  const candidates = resolved === '.' || resolved.endsWith('/')
    ? [posix.join(resolved, 'index.html')]
    : [resolved, `${resolved}.html`, posix.join(resolved, 'index.html')]
  return candidates.find((c) => c.endsWith('.html') && existsSync(join(dist, c)) && statSync(join(dist, c)).isFile())
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const here = join(dirname(fileURLToPath(import.meta.url)), '..')
  const [dist = join(here, '.vitepress', 'dist')] = process.argv.slice(2)
  const missing = missingAnchors(dist, process.env.DOCS_BASE || '/')
  if (missing.length > 0) {
    console.error(`Links to a section the page does not have (${missing.length}):\n  ${missing.join('\n  ')}`)
    process.exit(1)
  }
  console.log('Every link to a section lands on it.')
}
