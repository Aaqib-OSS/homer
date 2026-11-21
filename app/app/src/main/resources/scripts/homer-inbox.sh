#!/bin/sh
# homer-inbox — take the operator messages queued for the running command's own run (ADR-0097).
# Reads HMR_API_URL and HMR_CALLBACK_TOKEN from the environment; usage and exit codes: AGENT.md.

set -u

CURL_MAX_TIME=10
FAIL_EXIT=2
USAGE="usage: homer-inbox [--hook post-tool-use|stop]"

die() {
    printf 'homer-inbox: %s\n' "$1" >&2
    exit "$FAIL_EXIT"
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

QUERY=""
if [ "${1:-}" = "--hook" ]; then
    # Exit 1, not 2: Claude Code reads a hook's exit 2 as a blocking decision.
    FAIL_EXIT=1
    [ $# -eq 2 ] || die "$USAGE"
    case "$2" in
        post-tool-use | stop) QUERY="?hook=$2" ;;
        *) die "$USAGE" ;;
    esac
elif [ $# -ne 0 ]; then
    die "$USAGE"
fi

[ -n "${HMR_API_URL:-}" ] || die "HMR_API_URL is not set"
[ -n "${HMR_CALLBACK_TOKEN:-}" ] || die "HMR_CALLBACK_TOKEN is not set"

TAKE_URL="${HMR_API_URL%/}/api/v1/callback/messages/take$QUERY"

# Capture body and HTTP status separately (no -f): a rejection carries its
# reason in { msg }, which -f would discard.
resp=$(
    homer_curl "$TAKE_URL" -s --max-time "$CURL_MAX_TIME" -X POST \
        --data '' \
        -w '\n%{http_code}'
) || die "request failed (Homer unreachable, curl exit $?)"

code=$(printf '%s\n' "$resp" | tail -n1)
body=$(printf '%s\n' "$resp" | sed '$d')

case "$code" in
    200) printf '%s\n' "$body" ;;
    204) ;;
    *)
        reason=$(printf '%s' "$body" | sed -n 's/.*"msg": *"\([^"]*\)".*/\1/p')
        die "${reason:-request rejected with HTTP $code}"
        ;;
esac
