// Heading ids as GitHub makes them (github-slugger), so a #fragment in the repository's Markdown
// lands on the same section on GitHub and on the site. markdown-it-anchor keeps the per-page
// duplicate state and numbers repeats -1, -2 the way GitHub's slugger does.
import { slug } from 'github-slugger'

/** The `markdown.anchor` option of the VitePress config. */
export const anchor = { slugify: (text) => slug(text) }
