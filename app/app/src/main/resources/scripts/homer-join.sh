#!/bin/sh
# homer-join — register a parallel fan-out join from a running command; the JSON body comes on stdin (ADR-0026).
# Exits 0 on success, 2 on a usage or transport error (dead origin process, unknown or undispatchable agent).

# Reads HMR_API_URL and HMR_CALLBACK_TOKEN from the environment; usage: AGENT.md.

set -u

CURL_MAX_TIME=70

die() {
    printf 'homer-join: %s\n' "$1" >&2
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

JOINS_URL="${HMR_API_URL%/}/api/v1/callback/joins"

body=$(cat)
[ -n "$body" ] || die "no request body on stdin"

response=$(
    printf '%s' "$body" |
        homer_curl "$JOINS_URL" -sS -f --max-time "$CURL_MAX_TIME" -X POST \
            -H "Content-Type: application/json" \
            --data-binary @-
) || die "join failed (terminal origin process, unknown or non-dispatchable agent, or Homer unreachable)"
printf '%s\n' "$response"
