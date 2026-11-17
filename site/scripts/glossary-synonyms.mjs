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

/** Splits an identifier or a word run into lowercase tokens on camelCase, snake_case and digits' edges. */
export function tokens(text) {
  return (text.match(/[A-Za-z][A-Za-z0-9]*/g) ?? []).flatMap((id) =>
    id.replace(/([a-z0-9])([A-Z])/g, '$1 $2').replace(/([A-Z]+)([A-Z][a-z])/g, '$1 $2').split(' ').map((t) => t.toLowerCase()))
}

// Comments and string literals are not identifiers: Kotlin and TypeScript alike, in one pass so
// a `//` inside a string or a quote inside a comment is not misread. Newlines are kept.
const NOT_CODE = /\/\*[\s\S]*?\*\/|\/\/[^\n]*|"""[\s\S]*?"""|"(?:[^"\\\n]|\\.)*"|'(?:[^'\\\n]|\\.)*'|`(?:[^`\\]|\\.)*`/g
export function stripNonCode(text) {
  return text.replace(NOT_CODE, (m) => m.replace(/[^\n]/g, ' '))
}

const matches = (lineTokens, word) => lineTokens.some((t) => t === word || t === `${word}s`)

/**
 * Every line of `text` that holds a forbidden synonym and is not an allowed context.
 * @param {{path: string, text: string, kind: 'prose'|'code'}} file
 * @param {{forbidden: {word: string, use: string}[], allowed: {word: string, file: string, text: string}[]}} rules
 * @param {Set<object>} [used] receives each allowed context that excused a hit
 */
export function findHits(file, rules, used = new Set()) {
  const lines = (file.kind === 'code' ? stripNonCode(file.text) : file.text).split('\n')
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
