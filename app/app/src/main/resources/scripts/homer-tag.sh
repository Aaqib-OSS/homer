#!/bin/sh
# homer-tag — declare a subject tag against the running command's own run (ADR-0060).
# Reads HMR_API_URL and HMR_CALLBACK_TOKEN from the environment; usage and exit codes: AGENT.md.

set -u

CURL_MAX_TIME=70

die() {
    printf 'homer-tag: %s\n' "$1" >&2
    exit 2
}

# Runs curl on URL $1 with options $2...; the URL and bearer token reach curl as a
# config on fd 3, never argv, which other users on the host can read (CWE-214).
homer_curl() {
    # A line break would end the config line and let the value inject curl options.
    case "$1$HMR_CALLBACK_TOKEN" in
        *[[:cntrl:]]*) die "the request URL or HMR_CALLBACK_TOKEN contains a control character" ;;
    esac
    cfg_url=$(printf '%s' "$1" | sed 's/[\\"]/\\&/g')
    cfg_token=$(printf '%s' "$HMR_CALLBACK_TOKEN" | sed 's/[\\"]/\\&/g')
    shift
    # --globoff: a {} or [] in the URL must not expand into several requests.
    curl -K /dev/fd/3 --globoff "$@" 3<<EOF
url = "$cfg_url"
header = "Authorization: Bearer $cfg_token"
EOF
}

[ -n "${HMR_API_URL:-}" ] || die "HMR_API_URL is not set"
[ -n "${HMR_CALLBACK_TOKEN:-}" ] || die "HMR_CALLBACK_TOKEN is not set"

TAGS_URL="${HMR_API_URL%/}/api/v1/callback/tags"

# Escapes a value for embedding in a JSON string literal. Char-by-char to
# stay portable: gsub replacement-escape semantics differ across awks.
json_escape() {
    printf '%s' "$1" | awk '
        NR > 1 { printf "%s", "\\n" }
        {
            n = length($0)
            for (j = 1; j <= n; j++) {
                c = substr($0, j, 1)
                if (c == "\\") printf "%s", "\\\\"
                else if (c == "\"") printf "%s", "\\\""
                else if (c == "\t") printf "%s", "\\t"
                else if (c == "\r") printf "%s", "\\r"
                else printf "%s", c
            }
        }'
}

# Prints the unescaped string value of field $2 in JSON $1, if present, so a
# server rejection surfaces its own reason rather than a generic message.
json_str_field() {
    printf '%s' "$1" | awk -v field="$2" '
        BEGIN { found = 0 }
        !found {
            pat = "\"" field "\":"
            i = index($0, pat)
            if (i == 0) next
            s = substr($0, i + length(pat))
            while (substr(s, 1, 1) == " ") s = substr(s, 2)
            if (substr(s, 1, 1) != "\"") next
            found = 1
            s = substr(s, 2)
            while (length(s) > 0) {
                c = substr(s, 1, 1)
                if (c == "\\") {
                    d = substr(s, 2, 1)
                    if (d == "n") printf "\n"
                    else printf "%s", d
                    s = substr(s, 3)
                } else if (c == "\"") {
                    break
                } else {
                    printf "%s", c
                    s = substr(s, 2)
                }
            }
        }
        END { exit !found }'
}

[ $# -eq 1 ] || die "usage: homer-tag KEY:VALUE"
# Split on the first ':' — the value charset is colon-free, so a well-formed
# argument has exactly one separator and everything after it is the value.
case "$1" in
    *:*) ;;
    *) die "usage: homer-tag KEY:VALUE" ;;
esac
KEY=${1%%:*}
VALUE=${1#*:}
[ -n "$KEY" ] || die "KEY must not be empty"
[ -n "$VALUE" ] || die "VALUE must not be empty"

payload="{\"tag\":\"$(json_escape "$KEY"):$(json_escape "$VALUE")\"}"

# Capture body and HTTP status separately (no -f): a 4xx/409 carries a reason
# in { msg } that the author needs to see, which -f would discard.
resp=$(
    printf '%s' "$payload" |
        homer_curl "$TAGS_URL" -sS --max-time "$CURL_MAX_TIME" -X POST \
            -H "Content-Type: application/json" \
            --data-binary @- \
            -w '\n%{http_code}'
) || die "tagging failed (Homer unreachable)"

code=$(printf '%s\n' "$resp" | tail -n1)
body=$(printf '%s\n' "$resp" | sed '$d')

case "$code" in
    200) exit 0 ;;
    *)
        reason=$(json_str_field "$body" "msg")
        die "${reason:-request rejected with HTTP $code}"
        ;;
esac
