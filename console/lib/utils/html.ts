// Escaped as \uXXXX in inline-script JSON: `<` `>` could end the tag; U+2028/2029 end a JS line.
const LINE_SEP = String.fromCharCode(0x2028);
const PARA_SEP = String.fromCharCode(0x2029);
const SCRIPT_ESCAPES: Record<string, string> = {
  '<': '\\u003c',
  '>': '\\u003e',
  [LINE_SEP]: '\\u2028',
  [PARA_SEP]: '\\u2029',
};
const SCRIPT_UNSAFE = new RegExp('[<>' + LINE_SEP + PARA_SEP + ']', 'g');

/**
 * Serializes a value to JSON safe to interpolate into an inline `<script>` body,
 * so even operator-controlled input cannot break out of the tag or the JS parse.
 */
export function serializeForScript(value: unknown): string {
  return JSON.stringify(value).replace(SCRIPT_UNSAFE, (c) => SCRIPT_ESCAPES[c]);
}
