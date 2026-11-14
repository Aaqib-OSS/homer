#!/bin/sh
# Installs the gitleaks release pinned in gitleaks.env into DIR, refusing an archive whose SHA-256
# differs from the pinned one.
#
# Usage: tools/pr-policy/install-gitleaks.sh DIR   (prints the binary's path)
set -eu

dir=$1
# shellcheck source=tools/pr-policy/gitleaks.env
. "$(cd "$(dirname "$0")" && pwd)/gitleaks.env"

case "$(uname -s)-$(uname -m)" in
  Linux-x86_64) platform=linux_x64 want=$GITLEAKS_SHA256_LINUX_X64 ;;
  Darwin-arm64) platform=darwin_arm64 want=$GITLEAKS_SHA256_DARWIN_ARM64 ;;
  *) echo "No pinned gitleaks archive for $(uname -s)-$(uname -m)" >&2; exit 1 ;;
esac

mkdir -p "$dir"
archive="$dir/gitleaks.tar.gz"
curl -fsSL -o "$archive" \
  "https://github.com/gitleaks/gitleaks/releases/download/v$GITLEAKS_VERSION/gitleaks_${GITLEAKS_VERSION}_$platform.tar.gz"
got=$(sha256sum "$archive" | cut -d ' ' -f 1)
if [ "$got" != "$want" ]; then
  echo "gitleaks archive SHA-256 $got, expected $want" >&2
  exit 1
fi
tar -xzf "$archive" -C "$dir" gitleaks
echo "$dir/gitleaks"
