#!/bin/sh
# Tests secret-scan.sh against the pinned gitleaks: a merge commit's conflict resolution, rules from the base only, fail closed on an unresolvable range.
# Usage: tools/pr-policy/secret-scan-test.sh   (from anywhere; downloads gitleaks, needs git)
set -eu

here=$(cd "$(dirname "$0")" && pwd)
failures=0
scratch=$(mktemp -d)
trap 'rm -rf "$scratch"' EXIT
gitleaks=$("$here/install-gitleaks.sh" "$scratch/bin")

export GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_NOSYSTEM=1
export GIT_AUTHOR_NAME=test GIT_AUTHOR_EMAIL=test@example.invalid
export GIT_COMMITTER_NAME=test GIT_COMMITTER_EMAIL=test@example.invalid

# A fresh fake GitHub token per run: the repository itself holds none.
token="ghp_$(LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom | head -c 36)"

repo=$scratch/repo
git init -q -b main "$repo"
cd "$repo"
printf 'setting = base\n' >conf.txt
git add conf.txt
git commit -qm base
base=$(git rev-parse HEAD)

# Runs the scan of BASE..HEAD from a checkout of HEAD and compares pass/fail with the expectation.
expect() {
  want=$1 name=$2 from=$3 to=$4
  git checkout -q --detach "$to" 2>/dev/null || true
  if "$here/secret-scan.sh" "$gitleaks" "$from" "$to" >"$scratch/out.txt" 2>&1; then got=pass; else got=fail; fi
  if [ "$got" != "$want" ]; then
    echo "FAIL: $name: expected $want, got $got"
    sed 's/^/    /' "$scratch/out.txt"
    failures=$((failures + 1))
  fi
  if grep -q "$token" "$scratch/out.txt"; then
    echo "FAIL: $name: the token reached the output unredacted"
    failures=$((failures + 1))
  fi
}

# A merge whose conflict resolution is the only place the token appears.
git checkout -q -b merged "$base"
printf 'setting = branch\n' >conf.txt
git commit -qam branch-change
clean=$(git rev-parse HEAD)
git checkout -q -b main-moved "$base"
printf 'setting = main\n' >conf.txt
git commit -qam main-change
moved=$(git rev-parse HEAD)
git checkout -q merged
git merge -q "$moved" >/dev/null 2>&1 || true
printf 'setting = resolved\ngithub_token = %s\n' "$token" >conf.txt
git add conf.txt
git commit -qm "merge: main into merged"
merge=$(git rev-parse HEAD)

expect pass "a range without secrets" "$base" "$clean"
expect fail "a token added in a merge commit's conflict resolution" "$moved" "$merge"
expect fail "an unresolvable base" 0123456789012345678901234567890123456789 "$merge"
expect fail "an unresolvable head" "$base" 0123456789012345678901234567890123456789

# The scanned commits cannot switch the scan off: an allowlist of everything, an inline
# gitleaks:allow comment and a .gitleaksignore entry, each added by the range itself.
git checkout -q -b allowlisted "$base"
printf "[extend]\nuseDefault = true\n[[allowlists]]\npaths = ['''.*''']\n" >.gitleaks.toml
printf 'github_token = %s\n' "$token" >conf.txt
git add .gitleaks.toml conf.txt
git commit -qm allowlist-everything
expect fail "an allowlist the range adds itself" "$base" "$(git rev-parse HEAD)"

git checkout -q -b inline-allow "$base"
printf 'github_token = %s # gitleaks:allow\n' "$token" >conf.txt
git commit -qam inline-allow
expect fail "a gitleaks:allow comment" "$base" "$(git rev-parse HEAD)"

git checkout -q -b ignore-file "$base"
printf 'github_token = %s\n' "$token" >conf.txt
git commit -qam token
printf '%s:conf.txt:github-pat:1\n' "$(git rev-parse HEAD)" >.gitleaksignore
git add .gitleaksignore
git commit -qm ignore-it
expect fail "a .gitleaksignore the range adds" "$base" "$(git rev-parse HEAD)"

# The base's own .gitleaks.toml is the one that applies.
git checkout -q -b trusted "$base"
printf "[extend]\nuseDefault = true\n[[allowlists]]\npaths = ['''^fixture\\\\.txt\$''']\n" >.gitleaks.toml
git add .gitleaks.toml
git commit -qm allowlist-fixture
trusted=$(git rev-parse HEAD)
printf 'github_token = %s\n' "$token" >fixture.txt
git add fixture.txt
git commit -qm fixture
expect pass "a fixture the base's allowlist covers" "$trusted" "$(git rev-parse HEAD)"

# The repository's own .gitleaks.toml: each placeholder entry covers its file and no other.
git checkout -q -b repo-config "$base"
cp "$here/../../.gitleaks.toml" .gitleaks.toml
git add .gitleaks.toml
git commit -qm repo-config
repo_config=$(git rev-parse HEAD)

# Commits LINE to PATH on a branch off repo-config and expects WANT from the scan.
expect_placeholder() {
  want=$1 path=$2 line=$3
  git checkout -q -b "placeholder-$(printf '%s' "$path" | tr '/.' '--')" "$repo_config"
  mkdir -p "$(dirname "$path")"
  printf '%s\n' "$line" >"$path"
  git add "$path"
  git commit -qm "placeholder in $path"
  expect "$want" "the placeholder line in $path" "$repo_config" "$(git rev-parse HEAD)"
}
readme_line='curl -X POST -H "Authorization: Bearer your-token-here" http://localhost:8080/api/v1/agent/x'
server_line='curl -H "Authorization: Bearer ci-token-value" http://localhost:8080/api/v1/status/all'
expect_placeholder pass app/README.md "$readme_line"
expect_placeholder fail docs/readme-copy.md "$readme_line"
expect_placeholder pass app/SERVER.md "$server_line"
expect_placeholder fail docs/server-copy.md "$server_line"

# The installer refuses an archive whose SHA-256 differs from the pin, and installs nothing.
mkdir -p "$scratch/tampered"
printf '#!/bin/sh\necho tampered\n' >"$scratch/tampered/gitleaks"
chmod +x "$scratch/tampered/gitleaks"
tar -czf "$scratch/tampered.tar.gz" -C "$scratch/tampered" gitleaks
if HOMER_TEST_GITLEAKS_URL="file://$scratch/tampered.tar.gz" "$here/install-gitleaks.sh" "$scratch/refused" >/dev/null 2>&1; then
  echo "FAIL: the installer accepted a tampered archive"
  failures=$((failures + 1))
fi
if [ -e "$scratch/refused/gitleaks" ] || [ -e "$scratch/refused/gitleaks.tar.gz" ]; then
  echo "FAIL: the installer left a tampered archive or binary behind"
  failures=$((failures + 1))
fi
if HOMER_TEST_UNAME=Plan9-mips "$here/install-gitleaks.sh" "$scratch/unsupported" >/dev/null 2>&1; then
  echo "FAIL: the installer accepted a platform with no pinned archive"
  failures=$((failures + 1))
fi

if [ "$failures" -gt 0 ]; then
  echo "$failures secret-scan test(s) failed"
  exit 1
fi
echo "secret-scan tests passed"
