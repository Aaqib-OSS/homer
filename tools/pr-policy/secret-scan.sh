#!/bin/sh
# Scans the commits BASE..HEAD for secrets with gitleaks (job secret-scan, pr-policy.yml).
#
# Usage: tools/pr-policy/secret-scan.sh GITLEAKS BASE_SHA HEAD_SHA   (from the repository root)
#
# The rules come from BASE's .gitleaks.toml, never from the scanned commits: a pull request must not
# be able to allowlist its own secret. A base without one gets gitleaks' default rules. The scanned
# tree's .gitleaksignore and gitleaks:allow comments are ignored for the same reason.
set -eu

gitleaks=$1
base=$2
head=$3

# gitleaks scans a range it cannot resolve as 0 commits and passes: fail closed instead.
git cat-file -e "$base^{commit}"
git cat-file -e "$head^{commit}"

scratch=$(mktemp -d)
trap 'rm -rf "$scratch"' EXIT
if git cat-file -e "$base:.gitleaks.toml" 2>/dev/null; then
  git show "$base:.gitleaks.toml" >"$scratch/gitleaks.toml"
else
  printf '[extend]\nuseDefault = true\n' >"$scratch/gitleaks.toml"
fi
mkdir "$scratch/ignore"

# -m: a merge commit's patch against each parent, so a secret added while resolving a merge is
# scanned too (plain git log prints no patch for a merge). The source is the git directory, not the
# work tree: gitleaks also loads a .gitleaksignore from the source, whatever the ignore path says.
# --verbose --redact: each finding by commit, file and rule, never its value (the CI log is public).
"$gitleaks" git --no-banner --no-color --verbose --redact \
  --config "$scratch/gitleaks.toml" --gitleaks-ignore-path "$scratch/ignore" --ignore-gitleaks-allow \
  --log-opts="-m $base..$head" "$(git rev-parse --absolute-git-dir)"
