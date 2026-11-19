/**
 * Comment extraction per language for the comment-policy check: `{ kind, start, end, bodyStart, bodyEnd }`
 * offsets, `kind` being `line`, `block` or `doc` (JSDoc, a Python docstring). Strings are skipped, not parsed.
 */
import { kotlinComments } from './lib.mjs';

const lineEnd = (source, i) => {
  const end = source.indexOf('\n', i);
  return end < 0 ? source.length : end;
};
const lineComment = (start, end, marker) => ({ kind: 'line', start, end, bodyStart: start + marker, bodyEnd: end });

// After one of these words a `/` starts a regular expression; after any other word it divides.
const REGEX_AFTER = new Set(['return', 'typeof', 'case', 'do', 'else', 'in', 'of', 'new', 'delete', 'void', 'throw', 'instanceof', 'yield', 'await']);
const TEMPLATE = 'template';

// A `/` after `<` closes a JSX tag (`</span>`), never a regular expression.
const regexAllowed = (prev) => (prev === null ? true : /^[\w$]+$/.test(prev) ? REGEX_AFTER.has(prev) : !/[)\]}'"`<]/.test(prev));

// Index of the quote closing the string opened at `i`; an unclosed string stops before the newline.
function skipQuoted(source, i, quote) {
  for (let j = i + 1; j < source.length; j++) {
    if (source[j] === '\\') j++;
    else if (source[j] === quote) return j;
    else if (source[j] === '\n') return j - 1;
  }
  return source.length - 1;
}

// Index of the last character of the regular expression opened at `i`, or -1 when none closes on its line.
function skipRegex(source, i) {
  let inClass = false;
  for (let j = i + 1; j < source.length; j++) {
    const c = source[j];
    if (c === '\\') j++;
    else if (c === '\n') return -1;
    else if (c === '[') inClass = true;
    else if (c === ']') inClass = false;
    else if (c === '/' && !inClass) {
      while (/[a-z]/i.test(source[j + 1] ?? '')) j++;
      return j;
    }
  }
  return -1;
}

// One step inside a template literal; returns the index of the step's last character.
function stepInTemplate(source, i, stack) {
  if (source[i] === '\\') return i + 1;
  if (source[i] === '`') stack.pop();
  else if (source.startsWith('${', i)) {
    stack.push({ braces: 0 });
    return i + 1;
  }
  return i;
}

function blockComment(source, i) {
  const close = source.indexOf('*/', i + 2);
  const end = close < 0 ? source.length : close + 2;
  const doc = source.startsWith('/**', i) && !source.startsWith('/**/', i);
  return { kind: doc ? 'doc' : 'block', start: i, end, bodyStart: i + (doc ? 3 : 2), bodyEnd: close < 0 ? end : close };
}

/** Comments of TypeScript or JavaScript: strings, template literals (`${…}` nesting included) and regex literals skipped. */
export function jsComments(source) {
  const comments = [];
  const stack = [];
  let prev = null;
  for (let i = 0; i < source.length; i++) {
    if (stack.at(-1) === TEMPLATE) {
      const depth = stack.length;
      i = stepInTemplate(source, i, stack);
      if (stack.length !== depth) prev = stack.length < depth ? '`' : '{';
      continue;
    }
    const c = source[i];
    if (/\s/.test(c)) continue;
    if (source.startsWith('//', i)) {
      const end = lineEnd(source, i);
      comments.push(lineComment(i, end, 2));
      i = end - 1;
    } else if (source.startsWith('/*', i)) {
      const comment = blockComment(source, i);
      comments.push(comment);
      i = comment.end - 1;
    } else {
      [i, prev] = stepInJsCode(source, i, stack, prev);
    }
  }
  return comments;
}

// One step in code (or a `${…}` expression); returns the step's last index and the token it read.
function stepInJsCode(source, i, stack, prev) {
  const c = source[i];
  if (c === '/' && regexAllowed(prev)) {
    const end = skipRegex(source, i);
    if (end >= 0) return [end, ')'];
  }
  if (c === '"' || c === "'") return [skipQuoted(source, i, c), c];
  if (c === '`') {
    stack.push(TEMPLATE);
    return [i, '`'];
  }
  const word = /^[\w$]+/.exec(source.slice(i, i + 64))?.[0];
  if (word) return [i + word.length - 1, word];
  const expression = stack.at(-1);
  if (expression && c === '{') expression.braces++;
  else if (expression && c === '}') {
    if (expression.braces === 0) stack.pop();
    else expression.braces--;
  }
  return [i, c];
}

const PY_STRING = /^([rRbBuUfF]{0,2})('''|"""|'|")/;
const PY_HEADER = /^(?:async\s+def|def|class)\b/;

// Exclusive end of the Python string whose quote starts at `j`; a one-quote string ends at its line.
function skipPyString(source, j, quote) {
  for (let k = j + quote.length; k < source.length; k++) {
    if (source[k] === '\\') k++;
    else if (source.startsWith(quote, k)) return k + quote.length;
    else if (source[k] === '\n' && quote.length === 1) return k;
  }
  return source.length;
}

/** Comments of Python: `#` comments and docstrings, the first string statement of the module, a class or a function. */
export function pythonComments(source) {
  const comments = [];
  const state = { depth: 0, atLineStart: true, header: false, expectDoc: true };
  for (let i = 0; i < source.length; i++) {
    const c = source[i];
    if (c === '\n') {
      if (state.depth === 0) [state.atLineStart, state.header] = [true, false];
    } else if (c === '\\' && source[i + 1] === '\n') i++;
    else if (c === '#') {
      const end = lineEnd(source, i);
      comments.push(lineComment(i, end, 1));
      i = end - 1;
    } else if (!/\s/.test(c)) i = stepInPython(source, i, state, comments);
  }
  return comments;
}

function stepInPython(source, i, state, comments) {
  const string = PY_STRING.exec(source.slice(i, i + 5));
  if (string) {
    const [, prefix, quote] = string;
    const end = skipPyString(source, i + prefix.length, quote);
    const bodyStart = i + prefix.length + quote.length;
    if (state.expectDoc) comments.push({ kind: 'doc', start: i, end, bodyStart, bodyEnd: Math.max(bodyStart, end - quote.length) });
    [state.expectDoc, state.atLineStart] = [false, false];
    return end - 1;
  }
  if (state.atLineStart && state.depth === 0) state.header = PY_HEADER.test(source.slice(i, i + 12));
  [state.expectDoc, state.atLineStart] = [false, false];
  const c = source[i];
  if ('([{'.includes(c)) state.depth++;
  else if (')]}'.includes(c)) state.depth = Math.max(0, state.depth - 1);
  else if (c === ':' && state.depth === 0 && state.header) [state.expectDoc, state.header] = [true, false];
  const word = /^\w+/.exec(source.slice(i, i + 64))?.[0];
  return word ? i + word.length - 1 : i;
}

const HEREDOC = /^<<(-?)\s*(['"]?)([A-Za-z_][\w.-]*)\2/;

// Start of the line after the last heredoc body that begins at `from`.
function skipHeredocs(source, from, heredocs) {
  let at = from;
  for (const { strip, word } of heredocs) {
    while (at < source.length) {
      const end = lineEnd(source, at);
      const line = source.slice(at, end);
      at = end + 1;
      if ((strip ? line.replace(/^\t+/, '') : line) === word) break;
    }
  }
  return Math.min(at, source.length);
}

/** Comments of a shell script or a Dockerfile: a `#` that starts a word outside quotes and heredocs; a shebang is none. */
export function shellComments(source, offset = 0) {
  const comments = [];
  const heredocs = [];
  const shebang = /^\s*#!/.exec(source);
  for (let i = shebang ? lineEnd(source, shebang[0].length) : 0; i < source.length; i++) {
    const c = source[i];
    if (c === '\n' && heredocs.length > 0) {
      i = skipHeredocs(source, i + 1, heredocs.splice(0)) - 1;
    } else if (c === '\\') i++;
    else if (c === "'") i = source[i - 1] === '$' ? skipShellString(source, i, "'", true) : skipShellString(source, i, "'", false);
    else if (c === '"') i = skipShellString(source, i, '"', true);
    else if (c === '#' && (i === 0 || /[\s;&|()]/.test(source[i - 1]))) {
      const end = lineEnd(source, i);
      comments.push(lineComment(offset + i, offset + end, 1));
      i = end - 1;
    } else if (c === '<' && source[i + 1] === '<' && source[i + 2] !== '<') {
      const heredoc = HEREDOC.exec(source.slice(i, i + 80));
      if (heredoc) {
        heredocs.push({ strip: heredoc[1] === '-', word: heredoc[3] });
        i += heredoc[0].length - 1;
      }
    }
  }
  return comments;
}

// Index of the quote closing the shell string opened at `i`; it may span lines.
function skipShellString(source, i, quote, escapes) {
  for (let j = i + 1; j < source.length; j++) {
    if (escapes && source[j] === '\\') j++;
    else if (quote === '"' && source.startsWith('$(', j)) j = skipSubstitution(source, j + 1);
    else if (source[j] === quote) return j;
  }
  return source.length - 1;
}

// Index of the parenthesis closing the `$(` whose `(` is at `i`; its own quotes nest.
function skipSubstitution(source, i) {
  let depth = 0;
  for (let j = i; j < source.length; j++) {
    const c = source[j];
    if (c === '\\') j++;
    else if (c === "'") j = skipShellString(source, j, "'", false);
    else if (c === '"') j = skipShellString(source, j, '"', true);
    else if (c === '(') depth++;
    else if (c === ')' && --depth === 0) return j;
  }
  return source.length - 1;
}

const BLOCK_SCALAR = /(?:^|[\s:-])[|>][-+1-9]{0,2}$/;

// Column of the comment on one YAML line outside a quoted scalar, or -1.
function yamlCommentColumn(line) {
  let quote = null;
  for (let j = 0; j < line.length; j++) {
    const ch = line[j];
    if (quote === "'") {
      if (ch === "'" && line[j + 1] === "'") j++;
      else if (ch === "'") quote = null;
    } else if (quote === '"') {
      if (ch === '\\') j++;
      else if (ch === '"') quote = null;
    } else if ((ch === '"' || ch === "'") && /(?:^|[:\-[{,?])\s*$/.test(line.slice(0, j))) quote = ch;
    else if (ch === '#' && (j === 0 || /\s/.test(line[j - 1]))) return j;
  }
  return -1;
}

const indentOf = (line) => /^ */.exec(line)[0].length;

/** Comments of YAML: a `#` outside quoted scalars; a block scalar (`|`, `>`) holds embedded code, read as shell. */
export function yamlComments(source) {
  const comments = [];
  let block = null;
  let at = 0;
  const flush = (end) => {
    if (block) comments.push(...shellComments(source.slice(block.start, end), block.start));
    block = null;
  };
  while (at <= source.length) {
    const end = lineEnd(source, at);
    const line = source.slice(at, end);
    if (block && (line.trim() === '' || indentOf(line) > block.indent)) {
      at = end + 1;
      continue;
    }
    flush(at);
    const column = yamlCommentColumn(line);
    if (column >= 0) comments.push(lineComment(at + column, end, 1));
    const code = (column >= 0 ? line.slice(0, column) : line).trimEnd();
    if (BLOCK_SCALAR.test(code)) block = { indent: indentOf(line), start: end + 1 };
    at = end + 1;
  }
  flush(source.length);
  return comments;
}

const TOML_STRING = /^('''|"""|'|")/;

/** Comments of TOML: a `#` outside its four kinds of string. */
export function tomlComments(source) {
  const comments = [];
  for (let i = 0; i < source.length; i++) {
    const quote = TOML_STRING.exec(source.slice(i, i + 3))?.[0];
    if (quote) {
      const close = quote === "'" || quote === "'''" ? source.indexOf(quote, i + quote.length) : skipPyString(source, i, quote) - quote.length;
      i = close < 0 ? source.length : close + quote.length - 1;
    } else if (source[i] === '#') {
      const end = lineEnd(source, i);
      comments.push(lineComment(i, end, 1));
      i = end - 1;
    }
  }
  return comments;
}

/** Comments of a format whose comments fill a whole line (`LINE_START`): a line starting with `#`. */
export function lineStartComments(source) {
  const comments = [];
  for (const match of source.matchAll(/^[ \t]*(#)[^\n]*/gm)) {
    const start = match.index + match[0].indexOf('#');
    comments.push(lineComment(start, match.index + match[0].length, 1));
  }
  return comments;
}

/** Comments of CSS: block comments outside strings. */
export function cssComments(source) {
  const comments = [];
  for (let i = 0; i < source.length; i++) {
    if (source[i] === '"' || source[i] === "'") i = skipQuoted(source, i, source[i]);
    else if (source.startsWith('/*', i)) {
      const comment = blockComment(source, i);
      comments.push(comment);
      i = comment.end - 1;
    }
  }
  return comments;
}

const shift = (comments, offset) => comments.map((c) => ({ ...c, start: c.start + offset, end: c.end + offset, bodyStart: c.bodyStart + offset, bodyEnd: c.bodyEnd + offset }));

/** Comments of a Vue single-file component: its `<script>` blocks read as JS, its `<style>` blocks as CSS; the template is not read. */
export function vueComments(source) {
  return [...source.matchAll(/<(script|style)\b[^>]*>([\s\S]*?)<\/\1>/g)].flatMap((m) => {
    const offset = m.index + m[0].indexOf('>') + 1;
    return shift(m[1] === 'script' ? jsComments(m[2]) : cssComments(m[2]), offset);
  });
}

const LEXERS = {
  kotlin: kotlinComments,
  js: jsComments,
  python: pythonComments,
  shell: (source) => shellComments(source),
  yaml: yamlComments,
  toml: tomlComments,
  'line-start': lineStartComments,
  css: cssComments,
  vue: vueComments,
};

// Formats whose comments fill a whole line: `.properties`, `.editorconfig`, env files, pip inputs, git and Docker ignore files, CODEOWNERS.
const LINE_START = /(?:\.properties|\.env(?:\.example)?|(?:^|\/)requirements[^/]*\.in|(?:^|\/)(?:\.editorconfig|\.gitignore|\.dockerignore|\.gitattributes|CODEOWNERS))$/;
const DOCKERFILE = /(?:^|\/)(?:Dockerfile(?:\.[^/]+)?|[^/]+\.Dockerfile)$/;

/** The lexer name for `path`, or null when the check does not read the file's language. */
export function languageOf(path) {
  if (/\.kts?$/.test(path)) return 'kotlin';
  if (/\.(?:[cm]?[jt]s|[jt]sx)$/.test(path)) return 'js';
  if (/\.py$/.test(path)) return 'python';
  if (/\.(?:sh|bash)$/.test(path) || DOCKERFILE.test(path)) return 'shell';
  if (/\.ya?ml$/.test(path)) return 'yaml';
  if (/\.toml$/.test(path)) return 'toml';
  if (/\.css$/.test(path)) return 'css';
  if (/\.vue$/.test(path)) return 'vue';
  if (LINE_START.test(path)) return 'line-start';
  return null;
}

/** Every comment of `source` in `language` (a `languageOf` name). */
export const commentsOf = (language, source) => LEXERS[language](source);
