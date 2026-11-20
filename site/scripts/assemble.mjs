// Assembles the GitHub Pages site (ADR-0087): node scripts/assemble.mjs RELEASES_DIR NEXT_DIR OUT_DIR SITE_ROOT
// Or `--kept <tags` prints the kept tags among those on stdin.
import { cpSync, existsSync, mkdirSync, readdirSync, readFileSync, statSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'

const VERSION = /^(\d+)\.(\d+)\.(\d+)$/
const SITE_ROOT = /^\/(?:[A-Za-z0-9._-]+\/)*$/
export const KEEP = 10

export function assemble({ releasesDir, nextDir, outDir, siteRoot, keep = KEEP }) {
  if (!SITE_ROOT.test(siteRoot)) throw new Error(`Not a site root path: ${siteRoot}`)
  const names = existsSync(releasesDir) ? readdirSync(releasesDir) : []
  for (const name of names) {
    // A release directory names a path on the site, so nothing but a version may become one.
    if (!VERSION.test(name) || !statSync(join(releasesDir, name)).isDirectory()) {
      throw new Error(`Not a release version: ${name}`)
    }
  }
  const versions = keptVersions(names, keep)
  const latest = versions[0] ?? 'next'

  mkdirSync(outDir, { recursive: true })
  for (const version of versions) cpSync(join(releasesDir, version), join(outDir, version), { recursive: true })
  cpSync(nextDir, join(outDir, 'next'), { recursive: true })

  for (const version of [...versions, 'next']) {
    const schemas = join(outDir, version, 'schemas')
    if (existsSync(schemas)) cpSync(schemas, join(outDir, 'schemas', version), { recursive: true })
  }
  if (existsSync(join(outDir, latest, 'schemas'))) {
    cpSync(join(outDir, latest, 'schemas'), join(outDir, 'schemas', 'latest'), { recursive: true })
  }

  redirect(join(outDir, 'index.html'), `${siteRoot}${latest}/`, 'Homer documentation')
  mkdirSync(join(outDir, 'atlas'), { recursive: true })
  redirect(join(outDir, 'atlas', 'index.html'), `${siteRoot}${latest}/atlas/`, 'Homer feature atlas')
  if (existsSync(join(outDir, latest, '404.html'))) cpSync(join(outDir, latest, '404.html'), join(outDir, '404.html'))
  writeFileSync(join(outDir, 'versions.json'), `${JSON.stringify([...versions, 'next'])}\n`)
  return { versions, latest }
}

/** The newest patch of each of the [keep] newest minor versions among [names], newest first. */
export function keptVersions(names, keep = KEEP) {
  const kept = []
  for (const name of names.filter((n) => VERSION.test(n)).sort(byVersionDescending)) {
    const minor = name.replace(/\.\d+$/, '')
    if (kept.length < keep && !kept.some((v) => v.replace(/\.\d+$/, '') === minor)) kept.push(name)
  }
  return kept
}

function redirect(file, target, title) {
  writeFileSync(
    file,
    `<!doctype html>\n<meta charset="utf-8">\n<title>${title}</title>\n` +
      `<meta http-equiv="refresh" content="0; url=${target}">\n<link rel="canonical" href="${target}">\n` +
      `<p><a href="${target}">${title}</a></p>\n`,
  )
}

function byVersionDescending(a, b) {
  const [, ...x] = VERSION.exec(a).map(Number)
  const [, ...y] = VERSION.exec(b).map(Number)
  for (let i = 0; i < 3; i++) if (x[i] !== y[i]) return y[i] - x[i]
  return 0
}

if (process.argv[1] === fileURLToPath(import.meta.url) && process.argv[2] === '--kept') {
  const tags = readFileSync(0, 'utf8').split('\n').map((line) => line.trim())
  for (const version of keptVersions(tags)) console.log(version)
} else if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const [releasesDir, nextDir, outDir, siteRoot] = process.argv.slice(2)
  if (!siteRoot) {
    console.error('Usage: node scripts/assemble.mjs RELEASES_DIR NEXT_DIR OUT_DIR SITE_ROOT')
    process.exit(2)
  }
  const { versions, latest } = assemble({ releasesDir, nextDir, outDir, siteRoot })
  console.log(`Assembled ${versions.length} release(s) and next; the root redirects to ${latest}.`)
}
