#!/bin/sh
# homer-inbox — take the operator messages queued for the running command's own run.
#
# Materialized by Homer into {projectRoot}/.homer/bin (on PATH) for every
# process. Reads HMR_API_URL and HMR_CALLBACK_TOKEN from the environment at
# call time. The run is identified by the callback token alone. Each message is
# returned once: taking it marks it delivered. See AGENT.md "Messages from the
# operator" and ADR-0097.
#
# Usage:
#   homer-inbox
#       Prints {"messages":[{"id","text","createdAt"},...]}, oldest first;
#       the list is empty when nothing was queued. Returns immediately.
#   homer-inbox --hook post-tool-use|stop
#       For a Claude Code hook: prints the hook JSON for that event when
#       messages were queued, nothing otherwise. Does not read stdin.
#
# Exit codes: 0 ok. On any failure (usage, transport, or the run is no longer
# accepting messages): 2, or 1 with --hook — a non-blocking hook error, since
# exit 2 would make Claude Code treat the failure as a blocking decision.

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
