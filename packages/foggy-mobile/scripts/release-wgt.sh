#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MANIFEST_PATH="$PROJECT_ROOT/src/manifest.json"
PACKAGE_PATH="$PROJECT_ROOT/package.json"
API_SCRIPT="$PROJECT_ROOT/scripts/uni-admin-api.js"

parse_semver() {
  local version="${1%%-*}"
  version="${version%%+*}"
  IFS=. read -r major minor patch <<< "$version"
  if [[ -z "${major:-}" || -z "${minor:-}" || -z "${patch:-}" ]]; then
    echo "Invalid semver format: $1 (expected X.Y.Z)" >&2
    return 1
  fi
  echo "$major $minor $patch"
}

max_semver() {
  local left="$1"
  local right="$2"
  local lm ln lp rm rn rp
  read -r lm ln lp < <(parse_semver "$left")
  read -r rm rn rp < <(parse_semver "$right")

  if (( lm != rm )); then
    (( lm > rm )) && echo "$left" || echo "$right"
  elif (( ln != rn )); then
    (( ln > rn )) && echo "$left" || echo "$right"
  elif (( lp >= rp )); then
    echo "$left"
  else
    echo "$right"
  fi
}

json_value() {
  local file="$1"
  local expr="$2"
  node -e "const fs=require('fs'); const data=JSON.parse(fs.readFileSync(process.argv[1],'utf8')); console.log($expr)" "$file"
}

api_json() {
  local output
  if ! output="$(node "$API_SCRIPT" "$@" 2>/dev/null | awk '/^[[:space:]]*\{/{line=$0} END{print line}')"; then
    return 0
  fi
  [[ -n "$output" ]] && echo "$output"
}

update_versions() {
  local version="$1"
  local version_code="$2"
  node - "$MANIFEST_PATH" "$PACKAGE_PATH" "$version" "$version_code" <<'NODE'
const fs = require('fs')
const [manifestPath, packagePath, version, versionCode] = process.argv.slice(2)

function writeJson(file, data) {
  fs.writeFileSync(file, JSON.stringify(data, null, 2) + '\n', 'utf8')
}

const manifest = JSON.parse(fs.readFileSync(manifestPath, 'utf8'))
manifest.versionName = version
manifest.versionCode = String(versionCode)
writeJson(manifestPath, manifest)

const pkg = JSON.parse(fs.readFileSync(packagePath, 'utf8'))
pkg.version = version
writeJson(packagePath, pkg)
NODE
}

current_version="$(json_value "$MANIFEST_PATH" 'data.versionName')"
current_version_code="$(json_value "$MANIFEST_PATH" 'data.versionCode')"

echo
echo "========================================"
echo "  Foggy Navigator - wgt release"
echo "========================================"
echo
echo "Local version: v$current_version (code: $current_version_code)"

server_version=""
latest_json="$(api_json latest-version || true)"
if [[ -n "$latest_json" ]]; then
  server_version="$(node -e "const data=JSON.parse(process.argv[1]); console.log(data.version || '')" "$latest_json")"
fi

if [[ -n "$server_version" ]]; then
  echo "Server version: v$server_version"
  base_version="$(max_semver "$current_version" "$server_version")"
else
  echo "Server version: none or unavailable"
  base_version="$current_version"
fi

read -r major minor patch < <(parse_semver "$base_version")
suggested_version="$major.$minor.$((patch + 1))"

echo
echo "Suggested version: v$suggested_version"
read -r -p "New version (Enter for $suggested_version): " new_version
new_version="${new_version:-$suggested_version}"

new_version_code=$((current_version_code + 1))
echo "Version code: $current_version_code -> $new_version_code"

echo
read -r -p "Release title (Enter for v$new_version): " release_title
release_title="${release_title:-v$new_version}"
read -r -p "Release note: " release_note
release_note="${release_note:-Bug fixes and improvements}"
read -r -p "Silent update? (y/N): " silent_input

echo
echo "--- Confirm release ---"
echo "Version: $new_version (code: $new_version_code)"
echo "Title:   $release_title"
echo "Note:    $release_note"
echo "Silent:  $([[ "$silent_input" =~ ^[yY]$ ]] && echo true || echo false)"
echo
read -r -p "Continue? (y/N): " confirm
if [[ ! "$confirm" =~ ^[yY]$ ]]; then
  echo "Cancelled"
  exit 0
fi

echo
echo "[1/3] Update versions..."
update_versions "$new_version" "$new_version_code"
echo "  manifest.json -> v$new_version (code: $new_version_code)"
echo "  package.json  -> v$new_version"

echo
echo "[2/3] Build wgt..."
(cd "$PROJECT_ROOT" && pnpm build:wgt)

wgt_file="$PROJECT_ROOT/dist/foggy-navigator-$new_version.wgt"
if [[ ! -f "$wgt_file" ]]; then
  echo "ERROR: wgt file not found at $wgt_file" >&2
  exit 1
fi
echo "  wgt: $wgt_file"

echo
echo "[3/3] Publish to uni-admin..."
latest_native_version=""
native_json="$(api_json latest-native-version || true)"
if [[ -n "$native_json" ]]; then
  latest_native_version="$(node -e "const data=JSON.parse(process.argv[1]); console.log(data.version || '')" "$native_json")"
fi

if [[ -n "$latest_native_version" ]]; then
  echo "  Native app version: v$latest_native_version"
else
  echo "  WARNING: no native app version found on server."
  read -r -p "  Enter minVersion manually (blank to cancel): " latest_native_version
  if [[ -z "$latest_native_version" ]]; then
    echo "Cancelled"
    exit 0
  fi
fi

publish_args=(
  "$API_SCRIPT" publish
  --type wgt
  --version "$new_version"
  --title "$release_title"
  --content "$release_note"
  --file "$wgt_file"
  --minVersion "$latest_native_version"
)

if [[ "$silent_input" =~ ^[yY]$ ]]; then
  publish_args+=(--silent)
fi

node "${publish_args[@]}"

echo
echo "========================================"
echo "  Release complete: v$new_version"
echo "========================================"
echo
