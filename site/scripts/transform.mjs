// Turns a repository Markdown file into a site page: links, images and text Vue would compile
// are rewritten; this file only rewrites published pages and reports their dead links.
import { posix } from 'node:path'

const HTML_TAGS =
  'a|abbr|b|blockquote|br|caption|cite|code|dd|del|details|div|dl|dt|em|figcaption|figure|' +
  'h[1-6]|hr|i|img|ins|kbd|li|mark|ol|p|picture|pre|q|s|samp|small|source|span|strong|sub|' +
  'summary|sup|table|tbody|td|tfoot|th|thead|tr|u|ul|var|video'
const STRAY_LT = new RegExp(`<(?!/?(?:${HTML_TAGS})(?=[\\s>/])|!--|https?:|mailto:)`, 'gi')
// Images match first; DONE marks a rewritten image target so the link pass skips it.
const IMAGE = /(!\[[^\]]*\]\()<?([^)\s>]+)>?((?:\s+"[^"]*")?)\)/g
const INLINE_LINK = /\]\(<?([^)\s>]+)>?((?:\s+"[^"]*")?)\)/g
const DONE = '\u0001'
const REFERENCE_LINK = /^(\s*\[[^\]]+\]:\s*)<?(\S+?)>?(\s.*)?$/
const HTML_ATTR = /\b(href|src|srcset)="([^"]+)"/g
const FENCE = /^\s*(```|~~~)/
const IMAGE_FILE = /\.(png|jpe?g|gif|svg|webp|avif|bmp|ico)$/i
/** Where an image from the repository is served on the site, relative to the site root. */
export const REPO_ASSETS = '_repo'

/** Returns {page, deadLinks, images}: the site page, its dead links, the repository image paths to copy under REPO_ASSETS.
 * @param {string} markdown the file's contents
 * @param {{from: string (repo path), to: string (site path), siteOf: (p: string) => string | undefined, kind: (p: string) => 'file' | 'dir' | undefined, repoUrl: string, ref: string}} ctx
 */
export function transform(markdown, ctx) {
  const deadLinks = []
  const images = new Set()
  const link = (target, image) => {
    const rewritten = rewrite(target, image, ctx, images)
    if (rewritten === undefined) {
      deadLinks.push(target)
      return target
    }
    return rewritten
  }

  let frontmatter = ''
  let body = markdown
  const fm = /^---\n[\s\S]*?\n---\n/.exec(markdown)
  if (fm) {
    frontmatter = fm[0]
    body = markdown.slice(fm[0].length)
  }

  let fence = null
  const lines = body.split('\n').map((line) => {
    const opener = FENCE.exec(line)
    if (fence) {
      if (opener && opener[1] === fence) fence = null
      return line
    }
    if (opener) {
      fence = opener[1]
      return line
    }
    const ref = REFERENCE_LINK.exec(line)
    // A definition can serve an image (`![x][ref]`) or a link, so rewrite() decides by the file
    // it names.
    if (ref) return `${ref[1]}${link(ref[2], 'by-file')}${ref[3] ?? ''}`
    return outsideInlineCode(line, (text) =>
      text
        .replace(IMAGE, (_, open, target, title) => `${open}${DONE}${link(target, true)}${title})`)
        .replace(INLINE_LINK, (all, target, title) => (target.startsWith(DONE) ? all : `](${link(target, false)}${title})`))
        .replaceAll(DONE, '')
        .replace(HTML_ATTR, (_, attr, target) => `${attr}="${link(target, attr !== 'href')}"`)
        .replace(STRAY_LT, '&lt;'),
    )
  })

  const page = `${frontmatter}<div v-pre>\n\n${lines.join('\n')}\n\n</div>\n`
  return { page, deadLinks, images: [...images] }
}

function outsideInlineCode(line, fn) {
  return line
    .split(/(`+[^`]*?`+)/)
    .map((part, i) => (i % 2 === 1 ? part : fn(part)))
    .join('')
}

/** @param {boolean | 'by-file'} image whether the target is shown as an image; 'by-file' when only the file it names can tell. */
function rewrite(target, image, { from, to, siteOf, kind, repoUrl, ref }, images) {
  if (/^[a-z][a-z0-9+.-]*:/i.test(target) || target.startsWith('#')) return target
  const hashAt = target.indexOf('#')
  const path = hashAt < 0 ? target : target.slice(0, hashAt)
  const hash = hashAt < 0 ? '' : target.slice(hashAt)
  let decoded
  try {
    decoded = decodeURI(path)
  } catch {
    return undefined // a malformed escape such as `100%.md` names no file
  }
  // CWE-22: only plain relative POSIX paths. A backslash (also `%5C`) is a separator on Windows
  // that posix.normalize does not see, so `..\..\x` would pass the `..` check below.
  if (decoded.includes('\\')) return undefined
  const repoPath = posix.normalize(
    decoded.startsWith('/') ? decoded.slice(1) : posix.join(posix.dirname(from), decoded),
  ).replace(/\/$/, '')
  if (repoPath === '..' || repoPath.startsWith('../')) return undefined
  const page = siteOf(repoPath)
  if (page !== undefined) {
    const relative = posix.relative(posix.dirname(to), page)
    return `${relative.startsWith('.') ? relative : `./${relative}`}${hash}`
  }
  const found = kind(repoPath)
  if (found === undefined) return undefined
  const shown = image === 'by-file' ? IMAGE_FILE.test(repoPath) : image
  if (shown && found === 'file' && IMAGE_FILE.test(repoPath)) {
    images.add(repoPath)
    const relative = posix.relative(posix.dirname(to), `${REPO_ASSETS}/${repoPath}`)
    return `${encodeURI(relative.startsWith('.') ? relative : `./${relative}`)}${hash}`
  }
  const view = shown ? 'raw' : found === 'dir' ? 'tree' : 'blob'
  return `${repoUrl}/${view}/${ref}/${encodeURI(repoPath)}${hash}`
}
