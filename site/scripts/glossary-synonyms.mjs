// Forbidden-synonyms lint (GH-314): docs/glossary.md lists words that name a glossary term's
// concept under another name; this module parses that list and finds them in prose and in code
// identifiers, so one name stays one concept and an exact search finds all of it.
// glossary-synonyms.test.mjs runs it over the repository.

const SECTION = /^## Forbidden synonyms\s*$/
const ANY_H2 = /^## /

const cells = (line) => line.trim().replace(/^\||\|$/g, '').split('|').map((c) => c.trim().replace(/^`(.*)`$/, '$1'))

/**
 * Reads the "Forbidden synonyms" section of the glossary: the table headed `Synonym | Use instead`
 * and the table headed `Synonym | File | Line contains | Reason`.
 * @returns {{ forbidden: {word: string, use: string}[], allowed: {word: string, file: string, text: string, reason: string}[], section: string }}
 */
export function parseForbidden(markdown) {
  const lines = markdown.split('\n')
  const start = lines.findIndex((l) => SECTION.test(l))
  if (start === -1) throw new Error('docs/glossary.md has no "## Forbidden synonyms" section')
  let end = lines.findIndex((l, i) => i > start && ANY_H2.test(l))
  if (end === -1) end = lines.length
  const forbidden = []
  const allowed = []
  let kind = null
  for (const line of lines.slice(start + 1, end)) {
    if (!line.trim().startsWith('|')) { kind = null; continue }
    const row = cells(line)
    if (row[0] === 'Synonym') { kind = row.length === 2 || row[1] === 'Use instead' ? 'forbidden' : 'allowed'; continue }
    if (/^:?-+:?$/.test(row[0])) continue
    if (kind === 'forbidden' && !row[1]) throw new Error(`forbidden synonym "${row[0]}" names no term in "Use instead"`)
    if (kind === 'forbidden') forbidden.push({ word: row[0].toLowerCase(), use: row[1] })
    else if (kind === 'allowed') allowed.push({ word: row[0].toLowerCase(), file: row[1], text: row[2], reason: row[3] })
  }
  if (forbidden.length === 0) throw new Error('the Forbidden synonyms section lists no synonym')
  for (const a of allowed) {
    if (!forbidden.some((f) => f.word === a.word)) throw new Error(`allowed context for "${a.word}", which is not a forbidden synonym`)
    if (!a.file || !a.text || !a.reason) throw new Error(`allowed context for "${a.word}" needs a file, a line text and a reason`)
  }
  return { forbidden, allowed, section: lines.slice(start, end).join('\n') }
}

/** Splits an identifier or a word run into lowercase tokens on camelCase, snake_case and letter-digit edges. */
export function tokens(text) {
  return (text.match(/[A-Za-z][A-Za-z0-9]*/g) ?? []).flatMap((id) =>
    id.replace(/([a-z0-9])([A-Z])/g, '$1 $2').replace(/([A-Za-z])([0-9])/g, '$1 $2').replace(/([0-9])([A-Za-z])/g, '$1 $2').replace(/([A-Z]+)([A-Z][a-z])/g, '$1 $2').split(' ').map((t) => t.toLowerCase()))
}

// Comments and string literals are not identifiers: Kotlin and TypeScript alike, in one pass so
// a `//` inside a string or a quote inside a comment is not misread. Newlines are kept. A backtick
// is a template literal in TypeScript, but in Kotlin it quotes an identifier, so Kotlin keeps it.
// A TypeScript regex literal is not recognized: a quote inside one can mask the rest of its line.
const NOT_KOTLIN = /\/\*[\s\S]*?\*\/|\/\/[^\n]*|"""[\s\S]*?"""|"(?:[^"\\\n]|\\.)*"|'(?:[^'\\\n]|\\.)*'/g
const NOT_TS = new RegExp(`${NOT_KOTLIN.source}|\`(?:[^\`\\\\]|\\\\.)*\``, 'g')
const blank = (m) => m.replace(/[^\n]/g, ' ')
export const stripNonCode = (text, lang = 'kotlin') => text.replace(lang === 'ts' ? NOT_TS : NOT_KOTLIN, blank)

// HTML as prose: script and style bodies, comments and tags are dropped, line breaks kept. A
// script, style or comment left unclosed would drop the rest of the file silently, so it throws.
export function stripHtml(text) {
  const blocks = text.replace(/<(script|style)\b[\s\S]*?<\/\1\s*>|<!--[\s\S]*?-->/gi, blank)
  const open = /<(?:script|style)\b|<!--/i.exec(blocks)
  if (open) throw new Error(`unclosed ${open[0]} at line ${blocks.slice(0, open.index).split('\n').length}`)
  return blocks.replace(/<[^>]*>/g, blank)
}

const clean = { kotlin: (t) => stripNonCode(t, 'kotlin'), ts: (t) => stripNonCode(t, 'ts'), html: stripHtml, prose: (t) => t }

const matches = (lineTokens, word) => lineTokens.some((t) => t === word || t === `${word}s`)

/**
 * Every line of `text` that holds a forbidden synonym and is not an allowed context.
 * @param {{path: string, text: string, kind: 'prose'|'html'|'kotlin'|'ts'}} file
 * @param {{forbidden: {word: string, use: string}[], allowed: {word: string, file: string, text: string}[]}} rules
 * @param {Set<object>} [used] receives each allowed context that excused a hit
 */
export function findHits(file, rules, used = new Set()) {
  let cleaned
  try {
    cleaned = clean[file.kind](file.text)
  } catch (e) {
    throw new Error(`${file.path}: ${e.message}`)
  }
  const lines = cleaned.split('\n')
  const original = file.text.split('\n')
  const hits = []
  lines.forEach((line, i) => {
    const lineTokens = tokens(line)
    for (const { word, use } of rules.forbidden) {
      if (!matches(lineTokens, word)) continue
      const excuse = rules.allowed.find((a) => a.word === word && file.path === a.file && original[i].includes(a.text))
      if (excuse) used.add(excuse)
      else hits.push({ path: file.path, line: i + 1, word, use })
    }
  })
  return hits
}

export const format = (h) => `${h.path}:${h.line}: "${h.word}" names a glossary concept — use "${h.use}" (docs/glossary.md#forbidden-synonyms)`
