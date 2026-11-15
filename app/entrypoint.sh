#!/bin/sh
set -e

# Fix workspace ownership at runtime (volume is mounted after build-time chown)
mkdir -p /app/workspace

if [ "$(id -u)" = "0" ]; then
    # Running as root (Docker bind-mount case): the mounted workspace may be owned
    # by an arbitrary host UID, so ownership must be fixed before dropping to homer.
    # Only chown when the top-level dir is NOT already homer-owned: a normal restart
    # (tree already homer-owned, since homer created it) then skips the recursive
    # chown entirely, collapsing the root window to this ownership check plus the
    # su-exec drop — no attacker-influenceable recursive op runs as root. See the
    # 2026-07-12 security review (L7).
    if [ "$(stat -c '%U' /app/workspace 2>/dev/null)" != "homer" ]; then
        chown -R homer:homer /app/workspace 2>/dev/null || true
    fi
    # CONFIG_PATH reaches the inner shell as one positional argument (none when unset or
    # empty), so it is never split or glob-expanded there.
    # shellcheck disable=SC2016 # $JAVA_OPTS and "$@" are meant to expand in the inner shell
    exec su-exec homer sh -c 'exec java $JAVA_OPTS -jar /app/homer.jar "$@"' sh ${CONFIG_PATH:+"$CONFIG_PATH"}
else
    # Already running as non-root (K8s with securityContext.runAsUser).
    # su-exec is not needed and setgroups is typically blocked in K8s.
    # shellcheck disable=SC2086 # JAVA_OPTS holds several JVM flags and is split on purpose
    exec java $JAVA_OPTS -jar /app/homer.jar ${CONFIG_PATH:+"$CONFIG_PATH"}
fi
