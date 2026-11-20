#!/bin/sh
# Installs the gitleaks release pinned in gitleaks.env into DIR (prints the binary's path), refusing an archive whose SHA-256 differs from the pin.
# Usage: tools/pr-policy/install-gitleaks.sh DIR. Test-only overrides: HOMER_TEST_GITLEAKS_URL (download URL), HOMER_TEST_UNAME (the "OS-arch").
set -eu

dir=$1
# infra-lint runs shellcheck without -x; the file holds only VAR=value pins.
# shellcheck source=tools/pr-policy/gitleaks.env disable=SC1091
. "$(cd "$(dirname "$0")" && pwd)/gitleaks.env"

sha256() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | cut -d ' ' -f 1
  else
    shasum -a 256 "$1" | cut -d ' ' -f 1
  fi
}

system=${HOMER_TEST_UNAME:-$(uname -s)-$(uname -m)}
case "$system" in
  Linux-x86_64) platform=linux_x64 want=$GITLEAKS_SHA256_LINUX_X64 ;;
  Darwin-arm64) platform=darwin_arm64 want=$GITLEAKS_SHA256_DARWIN_ARM64 ;;
  *) echo "No pinned gitleaks archive for $system" >&2; exit 1 ;;
esac

url=${HOMER_TEST_GITLEAKS_URL:-https://github.com/gitleaks/gitleaks/releases/download/v$GITLEAKS_VERSION/gitleaks_${GITLEAKS_VERSION}_$platform.tar.gz}
mkdir -p "$dir"
archive="$dir/gitleaks.tar.gz"
curl -fsSL -o "$archive" "$url"
got=$(sha256 "$archive")
if [ "$got" != "$want" ]; then
  rm -f "$archive"
  echo "gitleaks archive SHA-256 $got, expected $want" >&2
  exit 1
fi
tar -xzf "$archive" -C "$dir" gitleaks
echo "$dir/gitleaks"
