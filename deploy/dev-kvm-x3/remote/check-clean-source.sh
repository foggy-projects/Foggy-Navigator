#!/usr/bin/env bash
set -euo pipefail

source_dir="${1:-.}"

if ! git -C "$source_dir" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  echo "ERROR: release source is not a Git worktree" >&2
  exit 1
fi

commit="$(git -C "$source_dir" rev-parse --verify HEAD 2>/dev/null || true)"
if [[ ! "$commit" =~ ^[0-9a-fA-F]{40}$ ]]; then
  echo "ERROR: release source commit is missing or malformed" >&2
  exit 1
fi

if ! status="$(git -C "$source_dir" status \
    --porcelain=v1 \
    --untracked-files=all \
    --ignore-submodules=none 2>/dev/null)"; then
  echo "ERROR: release source cleanliness could not be verified" >&2
  exit 1
fi
if [ -n "$status" ]; then
  echo "ERROR: release source must be clean, including untracked and submodule state" >&2
  exit 1
fi

printf '%s\n' "${commit,,}"
