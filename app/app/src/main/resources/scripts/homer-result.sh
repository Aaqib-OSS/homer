#!/bin/sh
# homer-result — fetch a large continuation result by reference (ADR-0025).
# Reads HMR_API_URL, HMR_CALLBACK_TOKEN and HMR_RESULT_TOKEN from the environment; usage and exit codes: AGENT.md.

set -u

CURL_MAX_TIME=70

die() {
    printf 'homer-result: %s\n' "$1" >&2
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

TOKEN=${HMR_RESULT_TOKEN:-}
OUT=""
while [ $# -gt 0 ]; do
    case "$1" in
        --token) # removeIn 1.45.0
            [ $# -ge 2 ] || die "--token needs a value"
            printf 'homer-result: --token is deprecated and shows the token on the command line; set HMR_RESULT_TOKEN instead\n' >&2
            TOKEN=$2
            shift 2 ;;
        --out) [ $# -ge 2 ] || die "--out needs a value"; OUT=$2; shift 2 ;;
        *) die "unknown argument: $1" ;;
    esac
done
[ -n "$TOKEN" ] || die "HMR_RESULT_TOKEN is not set (usage: HMR_RESULT_TOKEN=TOKEN homer-result [--out FILE])"

RESULTS_URL="${HMR_API_URL%/}/api/v1/callback/results/$TOKEN"

if [ -n "$OUT" ]; then
    homer_curl "$RESULTS_URL" -sS -f --max-time "$CURL_MAX_TIME" \
        -o "$OUT" ||
        die "fetch failed (invalid/expired token, artifact gone, oversized, or Homer unreachable)"
else
    homer_curl "$RESULTS_URL" -sS -f --max-time "$CURL_MAX_TIME" ||
        die "fetch failed (invalid/expired token, artifact gone, oversized, or Homer unreachable)"
fi
