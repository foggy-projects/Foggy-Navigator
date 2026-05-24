#!/usr/bin/env bash
set -euo pipefail

RELEASE_BASE_URL="__RELEASE_BASE_URL__"

if [ "$RELEASE_BASE_URL" = "__RELEASE_BASE_URL__" ] || [ -z "$RELEASE_BASE_URL" ]; then
  echo "ERROR: release URL was not injected into install.sh"
  exit 1
fi

echo "Fetching LangGraph BizWorker release metadata..."
latest_json="$(curl -fsSL "$RELEASE_BASE_URL/latest.json")"
version="$(printf '%s' "$latest_json" | grep '"version"' | head -1 | sed 's/.*"\([^"]*\)".*/\1/')"
if [ -z "$version" ]; then
  echo "ERROR: could not parse version from latest.json"
  exit 1
fi

case "$(uname -s)" in
  Linux*) os_tag="linux" ;;
  Darwin*) os_tag="macos" ;;
  *) os_tag="linux" ;;
esac

file_path="$(printf '%s' "$latest_json" | grep "\"$os_tag\"" | head -1 | sed 's/.*"\([^"]*langgraph-biz-worker[^"]*\)".*/\1/')"
if [ -z "$file_path" ]; then
  echo "ERROR: no $os_tag release found in latest.json"
  exit 1
fi

tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT
archive="$tmp_dir/$(basename "$file_path")"

echo "Downloading LangGraph BizWorker $version ($os_tag)..."
curl -fsSL -o "$archive" "$RELEASE_BASE_URL/$file_path"

if [[ "$archive" == *.zip ]]; then
  unzip -q "$archive" -d "$tmp_dir"
else
  tar xzf "$archive" -C "$tmp_dir"
fi

install_script="$(find "$tmp_dir" -maxdepth 3 -name install.sh | head -1)"
if [ -z "$install_script" ]; then
  echo "ERROR: install.sh not found in archive"
  exit 1
fi

chmod +x "$install_script"
bash "$install_script"
