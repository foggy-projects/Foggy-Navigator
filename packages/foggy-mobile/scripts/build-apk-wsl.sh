#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MANIFEST_PATH="$PROJECT_ROOT/src/manifest.json"
ENV_PATH="$PROJECT_ROOT/.env.release"
DIST_DIR="$PROJECT_ROOT/dist"
COMPILED_APP_DIR="$DIST_DIR/build/app"
KEYSTORE_PATH="$PROJECT_ROOT/keystore/foggy-navigator.keystore"
API_SCRIPT="$PROJECT_ROOT/scripts/uni-admin-api.js"

for required_cmd in node pnpm powershell.exe wslpath curl; do
  if ! command -v "$required_cmd" >/dev/null 2>&1; then
    echo "ERROR: required command not found: $required_cmd" >&2
    exit 1
  fi
done

to_wsl_path() {
  local value="$1"
  if [[ "$value" =~ ^[A-Za-z]:\\ ]]; then
    wslpath -u "$value"
  else
    echo "$value"
  fi
}

load_env_file() {
  local file="$1"
  [[ -f "$file" ]] || return 0
  while IFS='=' read -r key value; do
    [[ "$key" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]] || continue
    value="${value%$'\r'}"
    export "$key=$value"
  done < <(grep -E '^[A-Za-z_][A-Za-z0-9_]*=' "$file" || true)
}

load_env_file "$ENV_PATH"

HBUILDERX_CLI="${HBUILDERX_CLI:-/mnt/d/work/HBuilderX/cli.exe}"
HBUILDERX_CLI="$(to_wsl_path "$HBUILDERX_CLI")"
KEY_ALIAS="${KEY_ALIAS:-foggy-navi}"
KEYSTORE_PASSWORD="${KEYSTORE_PASSWORD:-}"
KEY_PASSWORD="${KEY_PASSWORD:-$KEYSTORE_PASSWORD}"
version="$(node -e "const fs=require('fs'); const m=JSON.parse(fs.readFileSync(process.argv[1],'utf8')); console.log(m.versionName)" "$MANIFEST_PATH")"

echo
echo "========================================"
echo "  Foggy Navigator - APK cloud build (WSL)"
echo "========================================"
echo
echo "Version: v$version"
echo "CLI:     $HBUILDERX_CLI"
echo

if [[ ! -x "$HBUILDERX_CLI" ]]; then
  echo "ERROR: HBuilderX CLI not found or not executable: $HBUILDERX_CLI" >&2
  echo "Set HBUILDERX_CLI=/mnt/<drive>/path/to/HBuilderX/cli.exe" >&2
  exit 1
fi

if [[ ! -f "$KEYSTORE_PATH" ]]; then
  echo "ERROR: keystore not found: $KEYSTORE_PATH" >&2
  exit 1
fi

if [[ -z "$KEYSTORE_PASSWORD" || -z "$KEY_PASSWORD" ]]; then
  echo "ERROR: KEYSTORE_PASSWORD/KEY_PASSWORD must be set in .env.release" >&2
  exit 1
fi

if [[ -n "${DCLOUD_USERNAME:-}" && -n "${DCLOUD_PASSWORD:-}" ]]; then
  echo "Logging in to DCloud CLI..."
  "$HBUILDERX_CLI" user login --username "$DCLOUD_USERNAME" --password "$DCLOUD_PASSWORD"
else
  echo "No DCLOUD_USERNAME/DCLOUD_PASSWORD in .env.release; using current HBuilderX login state."
fi

echo
echo "[1/5] Prebuild App Android resources..."
(cd "$PROJECT_ROOT" && pnpm build:app-android)

if [[ ! -d "$COMPILED_APP_DIR" ]]; then
  echo "ERROR: compiled app dir not found: $COMPILED_APP_DIR" >&2
  exit 1
fi

temp_win_raw="$(powershell.exe -NoProfile -Command '[IO.Path]::GetTempPath()' | tr -d '\r' | tail -n 1)"
temp_wsl_root="$(wslpath -u "$temp_win_raw")"
temp_wsl_dir="$temp_wsl_root/foggy-app-pack"
temp_win_dir="$(wslpath -w "$temp_wsl_dir")"
temp_wsl_keystore="$temp_wsl_root/foggy-navigator.keystore"
temp_win_keystore="$(wslpath -w "$temp_wsl_keystore")"

cleanup() {
  "$HBUILDERX_CLI" project close --path "$temp_win_dir" >/dev/null 2>&1 || true
  rm -rf "$temp_wsl_dir" "$temp_wsl_keystore"
}
trap cleanup EXIT

echo
echo "[2/5] Copy compiled resources to Windows temp..."
rm -rf "$temp_wsl_dir"
mkdir -p "$temp_wsl_dir"
cp -a "$COMPILED_APP_DIR/." "$temp_wsl_dir/"
cp "$KEYSTORE_PATH" "$temp_wsl_keystore"
echo "  Temp project: $temp_win_dir"

"$HBUILDERX_CLI" project close --path "$(wslpath -w "$PROJECT_ROOT")" >/dev/null 2>&1 || true
"$HBUILDERX_CLI" project close --path "$temp_win_dir" >/dev/null 2>&1 || true
"$HBUILDERX_CLI" project open --path "$temp_win_dir" || {
  echo "WARNING: project open returned non-zero, continuing to pack..."
}

echo
echo "[3/5] Submit DCloud cloud build..."
echo "  Platform: Android"
echo "  Package:  com.foggy.navigator"
echo "  Keystore: $temp_win_keystore (alias: $KEY_ALIAS)"
echo

set +e
pack_output="$("$HBUILDERX_CLI" \
  pack \
  --platform android \
  --project "$temp_win_dir" \
  --android.androidpacktype 0 \
  --android.packagename com.foggy.navigator \
  --android.certfile "$temp_win_keystore" \
  --android.certpassword "$KEYSTORE_PASSWORD" \
  --android.storepassword "$KEY_PASSWORD" \
  --android.certalias "$KEY_ALIAS" 2>&1)"
pack_exit=$?
set -e

printf '%s\n' "$pack_output"
if [[ $pack_exit -ne 0 ]]; then
  echo "ERROR: cloud build failed (exit code: $pack_exit)" >&2
  echo "Check Windows HBuilderX logs under %APPDATA%\\HBuilder X\\.log" >&2
  exit 1
fi

echo
echo "[4/5] Download APK..."
mkdir -p "$DIST_DIR"
apk_dest="$DIST_DIR/foggy-navigator-$version.apk"
download_url="$(printf '%s\n' "$pack_output" | grep -Eo 'https?://app\.liuyingyong\.cn/build/download/[a-f0-9-]+' | head -n 1 || true)"

if [[ -n "$download_url" ]]; then
  echo "  URL: $download_url"
  curl -L --fail "$download_url" -o "$apk_dest"
  echo "  APK: $apk_dest"
else
  echo "  No download URL found in pack output."
  echo "  Download manually from the HBuilderX output and save as: $apk_dest"
fi

echo
echo "[5/5] Publish native_app to uni-admin..."
if [[ ! -f "$apk_dest" ]]; then
  echo "  APK file not found, skipping automatic publish."
else
  read -r -p "  Release title (Enter for v$version): " publish_title
  publish_title="${publish_title:-v$version}"
  read -r -p "  Release note (Enter for default): " publish_content
  publish_content="${publish_content:-Foggy Navigator v$version}"

  node "$API_SCRIPT" publish \
    --type native_app \
    --version "$version" \
    --title "$publish_title" \
    --content "$publish_content" \
    --file "$apk_dest" || {
      echo
      echo "  Automatic publish failed. Publish manually in uni-admin."
    }
fi

echo
echo "========================================"
echo "  APK build flow complete"
echo "========================================"
echo
