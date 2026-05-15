#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
. "$SCRIPT_DIR/lib.sh"

echo "Checking access to $(print_target)"

if [ -n "${SSH_PASSWORD:-}" ] && ! has_sshpass; then
  echo "sshpass is not installed; SSH may prompt for a password."
fi

ssh_cmd 'bash -s' <<'REMOTE'
set -euo pipefail

echo "Host: $(hostname)"
echo "User: $(whoami)"
echo "Kernel: $(uname -srmo)"

if command -v sudo >/dev/null 2>&1; then
  if sudo -n true >/dev/null 2>&1; then
    echo "sudo: passwordless OK"
  else
    echo "sudo: available, but passwordless sudo failed"
  fi
else
  echo "sudo: not installed"
fi

echo
for cmd in docker java mvn node npm pnpm git rsync lsof curl; do
  if command -v "$cmd" >/dev/null 2>&1; then
    case "$cmd" in
      java) echo "java: $(java -version 2>&1 | head -n 1)" ;;
      mvn) echo "mvn: $(mvn -version 2>/dev/null | head -n 1)" ;;
      *) echo "$cmd: $(command -v "$cmd")" ;;
    esac
  else
    echo "$cmd: MISSING"
  fi
done
REMOTE
