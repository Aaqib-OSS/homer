#!/bin/sh
# Scans the commits BASE..HEAD with gitleaks, with the rules of BASE's .gitleaks.toml, never the scanned commits' (job secret-scan; ADR-0090, 2026-11-20 amendment).
# Usage: tools/pr-policy/secret-scan.sh GITLEAKS BASE_SHA HEAD_SHA   (from the repository root)
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

# -m scans a merge's patch against each parent; the git directory is the source, as gitleaks loads a .gitleaksignore from a work tree (ADR-0090, 2026-11-20 amendment).
# --verbose --redact: findings by commit, file and rule, never the value, as the CI log is public.
"$gitleaks" git --no-banner --no-color --verbose --redact \
  --config "$scratch/gitleaks.toml" --gitleaks-ignore-path "$scratch/ignore" --ignore-gitleaks-allow \
  --log-opts="-m $base..$head" "$(git rev-parse --absolute-git-dir)"
