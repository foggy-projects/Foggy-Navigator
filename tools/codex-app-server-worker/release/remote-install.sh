#!/usr/bin/env bash
set -euo pipefail

RELEASE_BASE_URL="__RELEASE_BASE_URL__"
PRODUCT='codex-app-server-worker'
INSTALL_DIR="${CODEX_APP_SERVER_WORKER_HOME:-$HOME/.codex-app-server-worker}"

[[ "$(uname -s)" == Linux ]] || { echo 'Codex App Server Worker remote install supports Linux only in this shell' >&2; exit 1; }
[[ "$RELEASE_BASE_URL" != '__RELEASE_BASE_URL__' && -n "$RELEASE_BASE_URL" ]] || {
  echo 'Release URL was not injected into install.sh' >&2
  exit 1
}
command -v curl >/dev/null || { echo 'curl is required' >&2; exit 1; }
command -v node >/dev/null || { echo 'Node.js is required' >&2; exit 1; }
command -v npm >/dev/null || { echo 'npm is required' >&2; exit 1; }
command -v unzip >/dev/null || { echo 'unzip is required' >&2; exit 1; }
command -v sha256sum >/dev/null || { echo 'sha256sum is required' >&2; exit 1; }

echo 'Fetching Codex App Server Worker release metadata...'
latest_json="$(curl -fsSL -H 'Cache-Control: no-cache' "$RELEASE_BASE_URL/latest.json?ts=$(date +%s)")"
manifest_fields="$(printf '%s' "$latest_json" | node -e '
let source=""; process.stdin.setEncoding("utf8"); process.stdin.on("data", chunk => source += chunk); process.stdin.on("end", () => {
  const value = JSON.parse(source)
  const version = String(value.version || "")
  const file = String(value.files?.linux || "")
  const sha = String(value.sha256?.linux || "").toLowerCase()
  const bytes = Number(value.bytes?.linux)
  const expected = `${version}/codex-app-server-worker-${version}.zip`
  if (value.schemaVersion !== 1 || value.product !== "codex-app-server-worker") throw new Error("unexpected product or schema")
  if (!/^\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?(?:\+[0-9A-Za-z.-]+)?$/.test(version)) throw new Error("invalid version")
  if (file !== expected || /[\\?#]/.test(file) || file.startsWith("/")) throw new Error("unsafe or unexpected artifact path")
  if (!/^[0-9a-f]{64}$/.test(sha) || !Number.isSafeInteger(bytes) || bytes <= 0) throw new Error("invalid integrity metadata")
  process.stdout.write([version, file, sha, bytes].join("\t"))
})
')"
IFS=$'\t' read -r version file_path expected_hash expected_bytes <<< "$manifest_fields"

compare_semver() {
  node - "$1" "$2" <<'NODE'
const parse = (value) => {
  const match = value.match(/^(\d+)\.(\d+)\.(\d+)(?:-([^+]+))?(?:\+.*)?$/)
  if (!match) throw new Error('installed or release version is not valid SemVer')
  return { core: match.slice(1, 4).map(Number), pre: match[4] ? match[4].split('.') : [] }
}
const left = parse(process.argv[2]); const right = parse(process.argv[3])
for (let index = 0; index < 3; index += 1) {
  if (left.core[index] !== right.core[index]) { process.stdout.write(left.core[index] < right.core[index] ? '-1' : '1'); process.exit(0) }
}
if (!left.pre.length || !right.pre.length) {
  process.stdout.write(left.pre.length === right.pre.length ? '0' : left.pre.length ? '-1' : '1'); process.exit(0)
}
for (let index = 0; index < Math.max(left.pre.length, right.pre.length); index += 1) {
  if (index >= left.pre.length || index >= right.pre.length) { process.stdout.write(index >= left.pre.length ? '-1' : '1'); process.exit(0) }
  const a = left.pre[index]; const b = right.pre[index]
  if (a === b) continue
  const an = /^\d+$/.test(a); const bn = /^\d+$/.test(b)
  if (an && bn) {
    const av = a.replace(/^0+(?=\d)/, ''); const bv = b.replace(/^0+(?=\d)/, '')
    process.stdout.write(av.length === bv.length ? (av < bv ? '-1' : '1') : (av.length < bv.length ? '-1' : '1')); process.exit(0)
  }
  if (an !== bn) { process.stdout.write(an ? '-1' : '1'); process.exit(0) }
  process.stdout.write(a < b ? '-1' : '1'); process.exit(0)
}
process.stdout.write('0')
NODE
}

read_env_value() {
  local key="$1" env_file="$INSTALL_DIR/.env"
  [[ -f "$env_file" ]] || return 0
  node - "$env_file" "$key" <<'NODE'
const fs = require('node:fs')
const [file, key] = process.argv.slice(2)
let result = ''
for (const line of fs.readFileSync(file, 'utf8').split(/\r?\n/)) {
  const match = line.match(/^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$/)
  if (!match || match[1] !== key) continue
  let value = match[2].trim()
  if (value.length >= 2 && ((value.startsWith('"') && value.endsWith('"')) || (value.startsWith("'") && value.endsWith("'")))) value = value.slice(1, -1)
  else value = value.replace(/\s+#.*$/, '').trim()
  result = value
}
process.stdout.write(result)
NODE
}

resolve_operation_path() {
  local key="$1" fallback="$2" value
  value="$(printenv "$key" 2>/dev/null || true)"
  [[ -n "$value" ]] || value="$(read_env_value "$key")"
  if [[ -z "$value" ]]; then printf '%s' "$fallback"; return; fi
  [[ "$value" == /* ]] || { echo "$key must be an absolute path before install state can be verified" >&2; return 1; }
  node -e 'const path=require("node:path"); process.stdout.write(path.resolve(process.argv[1]))' "$value"
}

existing_install=false
repair_install=false
if [[ -f "$INSTALL_DIR/package.json" ]]; then
  identity_fields="$(node -e 'const value=require(process.argv[1]); process.stdout.write(`${value.name || ""}\t${value.version || ""}`)' "$INSTALL_DIR/package.json")"
  IFS=$'\t' read -r identity installed_version <<< "$identity_fields"
  [[ "$identity" == "$PRODUCT" ]] || { echo "Install directory belongs to another product: $INSTALL_DIR" >&2; exit 1; }
  version_comparison="$(compare_semver "$installed_version" "$version")"
  if [[ "$version_comparison" == 0 ]]; then
    [[ -f "$INSTALL_DIR/VERSION" ]] || { echo 'Incomplete installation has no VERSION identity; refusing automatic repair' >&2; exit 1; }
    recorded_version="$(tr -d '\r\n' < "$INSTALL_DIR/VERSION")"
    [[ "$recorded_version" == "$installed_version" ]] || { echo 'package.json and VERSION identities disagree; refusing automatic repair' >&2; exit 1; }
    run_dir="$(resolve_operation_path CODEX_APP_SERVER_RUN_DIR "$INSTALL_DIR/logs/run")"
    state_dir="$(resolve_operation_path CODEX_APP_SERVER_STATE_DIR "$INSTALL_DIR/logs/state")"
    for evidence in "$INSTALL_DIR/update.in-progress" "$INSTALL_DIR/lifecycle.lock" "$run_dir/stop.failed" "$state_dir/lifecycle.failed"; do
      [[ ! -e "$evidence" && ! -L "$evidence" ]] || {
        echo 'Unresolved update or lifecycle failure evidence exists; follow the README manual recovery procedure before rerunning the installer' >&2
        exit 1
      }
    done
    required_files=(
      VERSION .env .env.example package-lock.json dist/index.js
      start.sh stop.sh update.sh install.sh
      scripts/configure-install-env.mjs scripts/read-dotenv-value.mjs
      node_modules/@openai/codex/package.json
    )
    missing=false
    for required in "${required_files[@]}"; do [[ -f "$INSTALL_DIR/$required" ]] || missing=true; done
    if [[ "$missing" == false ]]; then
      echo "Codex App Server Worker $version is already installed and complete; nothing to do."
      exit 0
    fi
    repair_install=true
    echo "Codex App Server Worker $version is incomplete; repairing the installation from the published archive."
  fi
  if [[ "$version_comparison" == 1 ]]; then
    echo "Installed version $installed_version is newer than published latest $version; refusing downgrade" >&2
    exit 1
  fi
  existing_install=true
fi

tmp_dir="$(mktemp -d)"
trap 'rm -rf -- "$tmp_dir"' EXIT
archive="$tmp_dir/$PRODUCT-$version.zip"
echo "Downloading Codex App Server Worker $version..."
curl -fsSL -o "$archive" "$RELEASE_BASE_URL/$file_path"
actual_bytes="$(wc -c < "$archive" | tr -d '[:space:]')"
[[ "$actual_bytes" == "$expected_bytes" ]] || { echo 'Downloaded archive size does not match latest.json' >&2; exit 1; }
actual_hash="$(sha256sum "$archive" | awk '{print tolower($1)}')"
[[ "$actual_hash" == "$expected_hash" ]] || { echo 'Downloaded archive SHA-256 does not match latest.json' >&2; exit 1; }

if [[ "$existing_install" == true && "$repair_install" == false ]]; then
  [[ -f "$INSTALL_DIR/update.sh" ]] || { echo "Existing installation has no update.sh: $INSTALL_DIR" >&2; exit 1; }
  echo "Updating existing installation at $INSTALL_DIR..."
  bash "$INSTALL_DIR/update.sh" --package "$archive" --install-dir "$INSTALL_DIR"
else
  extract_dir="$tmp_dir/extract"
  mkdir -p "$extract_dir"
  unzip -q "$archive" -d "$extract_dir"
  mapfile -t candidates < <(find "$extract_dir" -mindepth 2 -maxdepth 2 -type f -name install.sh -print)
  [[ ${#candidates[@]} -eq 1 ]] || { echo 'Release must contain exactly one installable root' >&2; exit 1; }
  candidate_dir="$(dirname "${candidates[0]}")"
  candidate_name="$(node -p 'require(process.argv[1]).name' "$candidate_dir/package.json")"
  candidate_version="$(node -p 'require(process.argv[1]).version' "$candidate_dir/package.json")"
  [[ "$candidate_name" == "$PRODUCT" && "$candidate_version" == "$version" ]] || {
    echo 'Downloaded release identity does not match latest.json' >&2
    exit 1
  }
  echo "Installing into $INSTALL_DIR..."
  bash "$candidate_dir/install.sh" --install-dir "$INSTALL_DIR"
fi

echo 'Install/update complete. A fresh installation is ready and remains stopped until start.sh is run.'
