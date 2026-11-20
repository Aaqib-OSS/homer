// Heading ids as GitHub makes them (github-slugger), so a #fragment lands on the same section
// on GitHub and on the site (ADR-0087).
import { slug } from 'github-slugger'

/** The `markdown.anchor` option of the VitePress config. */
export const anchor = { slugify: (text) => slug(text) }
