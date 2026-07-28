#!/usr/bin/env bash
set -euo pipefail

TEST_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEPLOY_DIR="$(cd "$TEST_DIR/.." && pwd)"
CLEAN_CHECK="$DEPLOY_DIR/remote/check-clean-source.sh"
RUNTIME_CHECK="$DEPLOY_DIR/remote/verify-runtime-provenance.sh"
work_dir="$(mktemp -d)"
trap 'rm -rf "$work_dir"' EXIT

git_init() {
  local repository="$1"
  git init -q "$repository"
  git -C "$repository" config user.email test@example.invalid
  git -C "$repository" config user.name "Provenance Test"
}

expect_failure() {
  local forbidden="$1"
  shift
  local output
  if output="$("$@" 2>&1)"; then
    echo "Expected command to fail: $*" >&2
    exit 1
  fi
  if [ -n "$forbidden" ] && [[ "$output" == *"$forbidden"* ]]; then
    echo "Failure output leaked forbidden fixture content" >&2
    exit 1
  fi
}

source_repo="$work_dir/source"
git_init "$source_repo"
printf 'clean\n' >"$source_repo/tracked.txt"
git -C "$source_repo" add tracked.txt
git -C "$source_repo" commit -qm initial
expected_commit="$(git -C "$source_repo" rev-parse HEAD)"

actual_commit="$(bash "$CLEAN_CHECK" "$source_repo")"
[ "$actual_commit" = "$expected_commit" ]

printf 'dirty\n' >>"$source_repo/tracked.txt"
expect_failure "" bash "$CLEAN_CHECK" "$source_repo"
git -C "$source_repo" restore tracked.txt

printf 'untracked\n' >"$source_repo/untracked.txt"
expect_failure "" bash "$CLEAN_CHECK" "$source_repo"
rm "$source_repo/untracked.txt"

submodule_repo="$work_dir/submodule-source"
git_init "$submodule_repo"
printf 'submodule\n' >"$submodule_repo/file.txt"
git -C "$submodule_repo" add file.txt
git -C "$submodule_repo" commit -qm initial
git -C "$source_repo" -c protocol.file.allow=always submodule add -q "$submodule_repo" sub
git -C "$source_repo" commit -qam "add submodule"
printf 'dirty submodule\n' >>"$source_repo/sub/file.txt"
expect_failure "" bash "$CLEAN_CHECK" "$source_repo"
git -C "$source_repo/sub" restore file.txt

expected_commit="$(git -C "$source_repo" rev-parse HEAD)"
good_json="$work_dir/good.json"
actuator_json="$work_dir/actuator.json"
dirty_json="$work_dir/dirty.json"
mismatch_json="$work_dir/mismatch.json"
missing_version_json="$work_dir/missing-version.json"
missing_time_json="$work_dir/missing-time.json"
malformed_json="$work_dir/malformed.json"

printf '{"git":{"commit":{"id":"%s"},"dirty":false},"build":{"version":"1.0.0-SNAPSHOT","time":"2026-07-28T00:00:00Z"}}\n' \
  "$expected_commit" >"$good_json"
printf '{"git":{"branch":"main","commit":{"id":{"full":"%s","abbrev":"%s"}},"dirty":"false"},"build":{"version":"1.0.0-SNAPSHOT","time":"2026-07-28T00:00:00Z"}}\n' \
  "$expected_commit" "${expected_commit:0:7}" >"$actuator_json"
printf '{"git":{"commit":{"id":"%s"},"dirty":true},"build":{"version":"1.0.0-SNAPSHOT","time":"2026-07-28T00:00:00Z"},"raw":"DO_NOT_LEAK"}\n' \
  "$expected_commit" >"$dirty_json"
printf '{"git":{"commit":{"id":"0000000000000000000000000000000000000000"},"dirty":false},"build":{"version":"1.0.0-SNAPSHOT","time":"2026-07-28T00:00:00Z"},"raw":"DO_NOT_LEAK"}\n' \
  >"$mismatch_json"
printf '{"git":{"commit":{"id":"%s"},"dirty":false},"build":{"version":"","time":"2026-07-28T00:00:00Z"},"raw":"DO_NOT_LEAK"}\n' \
  "$expected_commit" >"$missing_version_json"
printf '{"git":{"commit":{"id":"%s"},"dirty":false},"build":{"version":"1.0.0-SNAPSHOT","time":""},"raw":"DO_NOT_LEAK"}\n' \
  "$expected_commit" >"$missing_time_json"
printf '{"raw":"DO_NOT_LEAK"\n' >"$malformed_json"

NAVIGATOR_RUNTIME_PROVENANCE_FILE="$good_json" \
  bash "$RUNTIME_CHECK" "$expected_commit" >/dev/null
NAVIGATOR_RUNTIME_PROVENANCE_FILE="$actuator_json" \
  bash "$RUNTIME_CHECK" "$expected_commit" >/dev/null
expect_failure "DO_NOT_LEAK" env NAVIGATOR_RUNTIME_PROVENANCE_FILE="$dirty_json" \
  bash "$RUNTIME_CHECK" "$expected_commit"
expect_failure "DO_NOT_LEAK" env NAVIGATOR_RUNTIME_PROVENANCE_FILE="$mismatch_json" \
  bash "$RUNTIME_CHECK" "$expected_commit"
expect_failure "DO_NOT_LEAK" env NAVIGATOR_RUNTIME_PROVENANCE_FILE="$missing_version_json" \
  bash "$RUNTIME_CHECK" "$expected_commit"
expect_failure "DO_NOT_LEAK" env NAVIGATOR_RUNTIME_PROVENANCE_FILE="$missing_time_json" \
  bash "$RUNTIME_CHECK" "$expected_commit"
expect_failure "DO_NOT_LEAK" env NAVIGATOR_RUNTIME_PROVENANCE_FILE="$malformed_json" \
  bash "$RUNTIME_CHECK" "$expected_commit"
expect_failure "" env NAVIGATOR_RUNTIME_PROVENANCE_FILE="$good_json" \
  bash "$RUNTIME_CHECK" short-commit

echo "provenance preflight tests passed"
