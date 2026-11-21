#!/bin/sh
# Image build guard: fails unless the JRE's java.version is 25.0.5.1 or later, the first 25 build with the
# Java4Shell fix, which the image JVM launches rely on instead of -XX:-UseBASE64Intrinsics (W-020).
set -eu
java -XshowSettings:properties -version 2>&1 | awk -F' = ' '
    $1 ~ /^ *java\.version$/ { version = $2 }
    END {
        n = split(version, got, /[^0-9]+/)
        split("25.0.5.1", want, ".")
        for (i = 1; i <= 4; i++) {
            part = (i <= n ? got[i] : 0) + 0
            if (part > want[i] + 0) exit 0
            if (part < want[i] + 0) {
                printf "java.version \"%s\" predates 25.0.5.1, the Java4Shell fix; the image would need -XX:-UseBASE64Intrinsics again (W-020)\n", version > "/dev/stderr"
                exit 1
            }
        }
    }'
