#!/bin/sh
set -e

# Fix workspace ownership at runtime (volume is mounted after build-time chown)
mkdir -p /app/workspace

# -XX:-UseBASE64Intrinsics is the Java4Shell mitigation (DOCKER.md); it precedes JAVA_OPTS, so an
# operator's JAVA_OPTS keeps it and can still override it.

if [ "$(id -u)" = "0" ]; then
    # A bind-mounted workspace may be owned by any host UID, so fix it before dropping to homer, but only when
    # the top dir is not homer-owned: no recursive chown over the agent-writable tree on a restart (ADR-0047 rule 2).
    if [ "$(stat -c '%U' /app/workspace 2>/dev/null)" != "homer" ]; then
        chown -R homer:homer /app/workspace 2>/dev/null || true
    fi
    # CONFIG_PATH reaches the inner shell as one positional argument (none when unset or
    # empty), so it is never split or glob-expanded there.
    # shellcheck disable=SC2016 # $JAVA_OPTS and "$@" are meant to expand in the inner shell
    exec su-exec homer sh -c 'exec java -XX:-UseBASE64Intrinsics $JAVA_OPTS -jar /app/homer.jar "$@"' sh ${CONFIG_PATH:+"$CONFIG_PATH"}
else
    # Already running as non-root (K8s with securityContext.runAsUser).
    # su-exec is not needed and setgroups is typically blocked in K8s.
    # shellcheck disable=SC2086 # JAVA_OPTS holds several JVM flags and is split on purpose
    exec java -XX:-UseBASE64Intrinsics $JAVA_OPTS -jar /app/homer.jar ${CONFIG_PATH:+"$CONFIG_PATH"}
fi
