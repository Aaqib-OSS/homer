// Size cap of every tracked CLAUDE.md in UTF-8 bytes, ratcheting down only (docs/quality-checks.md).
export const CAPS = {
  'CLAUDE.md': 23876,
  'app/CLAUDE.md': 55083,
  'app/app/src/main/kotlin/homer/app/executors/llm/CLAUDE.md': 8494,
  'app/app/src/main/kotlin/homer/app/runners/CLAUDE.md': 11488,
  'app/app/src/main/kotlin/homer/app/services/persistence/CLAUDE.md': 20457,
  'clients/python/homer_langgraph/CLAUDE.md': 4110,
  'console/CLAUDE.md': 34607,
}

/** Slack a cap may sit above the current size: 5% of the cap, rounded up, at least 1. */
export function slack(cap) {
  return Math.max(1, Math.ceil(cap / 20))
}

/** One message per problem: `caps` and `sizes` map a tracked CLAUDE.md path to its cap / byte size. */
export function findings(caps, sizes) {
  const out = []
  for (const [path, size] of Object.entries(sizes)) {
    const cap = caps[path]
    if (cap === undefined) out.push(`add a cap for ${path} (${size} bytes) in claude-md-budget.mjs`)
    else if (size > cap) out.push(`${path} is ${size} bytes, over its cap ${cap}: trim it (or raise the cap in claude-md-budget.mjs with a reason in the commit)`)
    else if (cap - size > slack(cap)) out.push(`lower ${path}'s cap to ${size} (it is ${cap}, more than ${slack(cap)} above the current size)`)
  }
  for (const path of Object.keys(caps)) {
    if (!(path in sizes)) out.push(`remove the cap for ${path}: no such CLAUDE.md`)
  }
  return out
}
