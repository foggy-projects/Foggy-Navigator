#!/usr/bin/bash -p
# INT-001 bootstrap boundary for the disposable synthetic upstream harness.
#
# This helper deliberately accepts a single run-owned target carrier and must
# never source it.  It is kept fail-closed until every provisioning step below
# has a source-matched CLI implementation and an integration assertion.
#
# Invoke this file through its privileged shebang (or /usr/bin/bash -p), never
# through a caller-selected `bash`.  This prevents non-interactive startup
# files from running before the bootstrap boundary can establish its own
# minimal environment.

set -euo pipefail
IFS=$'\n\t'
umask 077

# This helper reads private credentials after its initial path checks.  Reset
# command lookup and discard shell-startup/directory injection variables before
# the first `cd`, profile parse, or child process.
readonly SAFE_PATH='/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin'
PATH="$SAFE_PATH"
export PATH
unset BASH_ENV ENV CDPATH
case "$-" in
  *p*) ;;
  *)
    printf 'INT-001 synthetic bootstrap: requires /usr/bin/bash -p\n' >&2
    exit 2
    ;;
esac

readonly SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
readonly REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd -P)"
readonly ARTIFACT_ROOT="$REPO_ROOT/temp/test-artifacts/INT-001"
readonly HARNESS_SCRIPT="$REPO_ROOT/tools/navigator-upstream/scripts/synthetic-upstream-harness.sh"
readonly TARGET_PROFILE_NAME='bootstrap-target.env'
readonly PRIVATE_DIRECTORY_NAME='private'
readonly OUTPUT_PROFILE_NAME='runtime-child.env'
readonly SOURCE_SDK_JAR="$REPO_ROOT/navigator-open-sdk/target/navigator-open-sdk-1.0.21.jar"
readonly SDK_LIB_DIR="$REPO_ROOT/tools/navigator-upstream/lib"
readonly CLI_MAIN_CLASS='com.foggy.navigator.sdk.cli.UpstreamCli'
readonly TRUSTED_JAVA_LINK='/usr/bin/java'
readonly RUN_ID_PATTERN='^[a-z0-9][a-z0-9-]{5,63}$'
readonly HARNESS_LIFECYCLE_LOCK_FD=8
readonly EXPECTED_INPUT_KEY_COUNT=8
readonly EXPECTED_OUTPUT_KEY_COUNT=13
readonly ADMIN_SCOPES='CLIENT_APP_MANAGE,CLIENT_APP_CONTROL_KEY_ISSUE,CLIENT_APP_RUNTIME_KEY_ISSUE,WORKER_MANAGE'
readonly CONTROL_SCOPES='MODEL_CONFIG_MANAGE,AGENT_BUNDLE_SYNC,WORKING_DIRECTORY_MANAGE,UPSTREAM_USER_GRANT,AGENT_MODEL_BINDING_MANAGE,AGENT_WORKSPACE_BINDING_MANAGE'

readonly -a INPUT_KEYS=(
  INT001_RUN_ID
  INT001_NAVIGATOR_URL
  INT001_BIZ_BASE_URL
  INT001_DIRECTORY_FACADE_URL
  INT001_MOCK_LLM_URL
  INT001_DIRECTORY_FACADE_TOKEN
  INT001_BOOTSTRAP_ROOT_USERNAME
  INT001_BOOTSTRAP_ROOT_PASSWORD
)

readonly -a OUTPUT_KEYS=(
  INT001_SYNTHETIC_UPSTREAM_HARNESS
  INT001_RUN_ID
  INT001_NAVI_BASE_URL
  INT001_A_TENANT_ID
  INT001_A_CLIENT_APP_ID
  INT001_A_CLIENT_APP_KEY
  INT001_A_CLIENT_APP_SECRET
  INT001_A_AGENT_ID
  INT001_A_UPSTREAM_USER_ID
  INT001_A_MODEL_CONFIG_ID
  INT001_A_DIRECTORY_ID
  INT001_B_AGENT_ID
  INT001_C_AGENT_ID
)

RUN_DIR=''
RUN_ID=''
ALLOW_CREATE=0
TARGET_PROFILE=''
PRIVATE_DIR=''
OUTPUT_PROFILE=''
CLI_CLASSPATH=''
CLI_JAVA=''
ROOT_PROFILE=''
UPSTREAM_ADMIN_PROFILE=''
A_CONTROL_PROFILE=''
A_RUNTIME_PROFILE=''
B_CONTROL_PROFILE=''
C_CONTROL_PROFILE=''
WORKER_HOST_MANIFEST=''
A_DIRECTORY_MANIFEST=''
A_AGENT_MANIFEST=''
B_AGENT_MANIFEST=''
C_AGENT_MANIFEST=''
UPSTREAM_SYSTEM_ID=''
A_TENANT_ID=''
C_TENANT_ID=''
A_CLIENT_APP_ID=''
B_CLIENT_APP_ID=''
C_CLIENT_APP_ID=''
PHYSICAL_WORKER_ID=''
BIZ_WORKER_ID=''
WORKER_HOST_ID=''
BIZ_WORKER_REQUEST_ID=''
A_MODEL_CONFIG_ID=''
B_MODEL_CONFIG_ID=''
C_MODEL_CONFIG_ID=''
A_DIRECTORY_ID=''
A_AGENT_ID=''
B_AGENT_ID=''
C_AGENT_ID=''
A_UPSTREAM_USER_ID=''
RUN_FINGERPRINT=''
MODEL_NAME=''
A_AGENT_CODE=''
B_AGENT_CODE=''
C_AGENT_CODE=''

declare -A TARGET=()

usage() {
  cat <<'USAGE'
Usage:
  synthetic-upstream-bootstrap.sh --allow-create --run-dir <runDir>

The target must be a prepared INT-001 run beneath
temp/test-artifacts/INT-001.  This command never accepts a Navigator, TMS,
SIM, or arbitrary external profile path.
USAGE
}

fail() {
  # Do not include input values, profile paths, credentials, or command output.
  printf 'INT-001 synthetic bootstrap: %s\n' "$1" >&2
  exit 2
}

require_value() {
  [[ -n "${2:-}" ]] || fail "$1 requires a value"
}

parse_args() {
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --allow-create)
        [[ "$ALLOW_CREATE" == 0 ]] || fail '--allow-create may be specified only once'
        ALLOW_CREATE=1
        shift
        ;;
      --run-dir)
        require_value "$1" "${2:-}"
        [[ -z "$RUN_DIR" ]] || fail '--run-dir may be specified only once'
        RUN_DIR="$2"
        shift 2
        ;;
      --help|-h)
        usage
        exit 0
        ;;
      *)
        fail "unknown option: $1"
        ;;
    esac
  done

  [[ "$ALLOW_CREATE" == 1 ]] || fail 'bootstrap requires --allow-create'
  [[ -n "$RUN_DIR" ]] || fail 'bootstrap requires --run-dir'
}

assert_private_dir() {
  local directory="$1" mode owner
  [[ -d "$directory" && ! -L "$directory" ]] || return 1
  mode="$(stat -c '%a' -- "$directory" 2>/dev/null)" || return 1
  owner="$(stat -c '%u' -- "$directory" 2>/dev/null)" || return 1
  [[ "$mode" == 700 && "$owner" == "$(id -u)" ]]
}

assert_private_file() {
  local file="$1" mode owner links
  [[ -f "$file" && ! -L "$file" ]] || return 1
  mode="$(stat -c '%a' -- "$file" 2>/dev/null)" || return 1
  owner="$(stat -c '%u' -- "$file" 2>/dev/null)" || return 1
  links="$(stat -c '%h' -- "$file" 2>/dev/null)" || return 1
  [[ "$mode" == 600 && "$owner" == "$(id -u)" && "$links" == 1 ]]
}

assert_run_ownership() {
  local root_real run_real candidate_id
  [[ ! -L "$ARTIFACT_ROOT" ]] || fail 'artifact root must not be a symlink'
  root_real="$(realpath -e -- "$ARTIFACT_ROOT" 2>/dev/null)" \
    || fail 'artifact root is unavailable'
  run_real="$(realpath -e -- "$RUN_DIR" 2>/dev/null)" \
    || fail 'run directory is unavailable'
  candidate_id="${run_real##*/}"
  [[ "$candidate_id" =~ $RUN_ID_PATTERN ]] || fail 'run directory does not have a valid INT-001 run id'
  [[ "$run_real" == "$root_real/$candidate_id" ]] || fail 'run directory is outside the INT-001 artifact root'
  [[ -d "$run_real" && ! -L "$run_real" ]] || fail 'run directory must be a real directory'
  RUN_DIR="$run_real"
  RUN_ID="$candidate_id"
  assert_private_dir "$RUN_DIR" || fail 'run directory is not private and current-user owned'

  PRIVATE_DIR="$RUN_DIR/$PRIVATE_DIRECTORY_NAME"
  [[ ! -e "$RUN_DIR/$TARGET_PROFILE_NAME" && ! -L "$RUN_DIR/$TARGET_PROFILE_NAME" ]] \
    || fail 'legacy root bootstrap carrier is forbidden'
  assert_private_dir "$PRIVATE_DIR" || fail 'private output directory is unsafe'
  TARGET_PROFILE="$PRIVATE_DIR/$TARGET_PROFILE_NAME"
  OUTPUT_PROFILE="$PRIVATE_DIR/$OUTPUT_PROFILE_NAME"
  assert_private_file "$TARGET_PROFILE" || fail 'bootstrap target carrier must be a current-user-owned 0600 regular file'

  # A retry must not overwrite a prior runtime projection.  An existing
  # private directory is permitted only if it still proves private ownership.
  [[ ! -e "$OUTPUT_PROFILE" && ! -L "$OUTPUT_PROFILE" ]] \
    || fail 'runtime child output already exists; refusing overwrite'
}

input_key_allowed() {
  local candidate="$1" key
  for key in "${INPUT_KEYS[@]}"; do
    [[ "$candidate" == "$key" ]] && return 0
  done
  return 1
}

read_strict_target_profile() {
  local line key value line_count=0 key_count=0
  while IFS= read -r line || [[ -n "$line" ]]; do
    ((line_count += 1))
    [[ "$line" != *$'\r'* && "$line" == *=* ]] || fail 'bootstrap target carrier has an invalid line'
    key="${line%%=*}"
    value="${line#*=}"
    input_key_allowed "$key" || fail 'bootstrap target carrier has an unsupported field'
    [[ -z "${TARGET[$key]+present}" ]] || fail 'bootstrap target carrier has a duplicate field'
    [[ -n "$value" ]] || fail 'bootstrap target carrier has an empty required field'
    TARGET["$key"]="$value"
    ((key_count += 1))
  done < "$TARGET_PROFILE"

  [[ "$line_count" == "$EXPECTED_INPUT_KEY_COUNT" && "$key_count" == "$EXPECTED_INPUT_KEY_COUNT" ]] \
    || fail 'bootstrap target carrier must contain exactly eight fields'
  local key
  for key in "${INPUT_KEYS[@]}"; do
    [[ -n "${TARGET[$key]+present}" ]] || fail 'bootstrap target carrier is missing a required field'
  done
  [[ "${TARGET[INT001_RUN_ID]}" == "$RUN_ID" ]] || fail 'bootstrap target run id does not match the run directory'
}

assert_loopback_http_url() {
  local value="$1"
  # Accept only an origin on loopback; paths, credentials, query strings,
  # fragments and the shared 8112 port are all rejected before any CLI call.
  [[ "$value" =~ ^https?://(127\.0\.0\.1|localhost):([1-9][0-9]{0,4})$ ]] \
    || return 1
  local port="${BASH_REMATCH[2]}"
  (( port <= 65535 && port != 8112 ))
}

validate_target_urls() {
  assert_loopback_http_url "${TARGET[INT001_NAVIGATOR_URL]}" \
    || fail 'Navigator target must be a loopback non-8112 origin'
  assert_loopback_http_url "${TARGET[INT001_BIZ_BASE_URL]}" \
    || fail 'Biz target must be a loopback non-8112 origin'
  assert_loopback_http_url "${TARGET[INT001_DIRECTORY_FACADE_URL]}" \
    || fail 'directory facade target must be a loopback non-8112 origin'
  assert_loopback_http_url "${TARGET[INT001_MOCK_LLM_URL]}" \
    || fail 'Mock LLM target must be a loopback non-8112 origin'
}

assert_harness_lifecycle_handoff() {
  local expected actual
  # The harness holds an exclusive flock on this already-validated run
  # directory for its whole bootstrap stage.  An inherited descriptor is a
  # process-local handoff: a direct helper invocation cannot turn a private
  # but forged carrier into a mutation-capable bootstrap request.
  [[ -d "/proc/self/fd/$HARNESS_LIFECYCLE_LOCK_FD" ]] \
    || fail 'bootstrap requires the harness-owned lifecycle lock handoff'
  expected="$(realpath -m -- "$RUN_DIR")" \
    || fail 'bootstrap lifecycle handoff is unsafe'
  actual="$(readlink -f -- "/proc/self/fd/$HARNESS_LIFECYCLE_LOCK_FD" 2>/dev/null)" \
    || fail 'bootstrap lifecycle handoff is unsafe'
  [[ "$actual" == "$expected" ]] \
    || fail 'bootstrap lifecycle handoff does not belong to this run'
  # Take/confirm the exclusive lock on the inherited descriptor.  The normal
  # path inherits the harness's already-held lock; a caller that deliberately
  # recreates this local descriptor still has to pass the full read-only
  # RUNNING ownership proof below before any mutation can begin.
  flock -n -x "$HARNESS_LIFECYCLE_LOCK_FD" \
    || fail 'bootstrap requires the harness-owned lifecycle lock handoff'
}

verify_harness_owned_target() {
  assert_harness_lifecycle_handoff
  [[ -f "$HARNESS_SCRIPT" && ! -L "$HARNESS_SCRIPT" ]] \
    || fail 'synthetic harness verifier is unavailable'
  # Reuse the harness's read-only RUNNING proof instead of duplicating its
  # profile alignment, Compose label, child ownership and health checks here.
  # Suppress verifier diagnostics because they can contain local paths; this
  # mutation-capable helper exposes only a fixed failure category.
  if ! env -i "PATH=$SAFE_PATH" "HOME=$RUN_DIR/home" \
    /usr/bin/bash -p "$HARNESS_SCRIPT" verify-running --run-id "$RUN_ID" \
    >/dev/null 2>&1; then
    fail 'bootstrap target is not a verified running INT-001 harness'
  fi
}

create_private_file() {
  local file="$1"
  [[ ! -e "$file" && ! -L "$file" ]] || fail 'bootstrap private output already exists; refusing overwrite'
  if ! (umask 077; : > "$file") 2>/dev/null; then
    fail 'cannot create bootstrap private output'
  fi
  chmod 600 -- "$file" 2>/dev/null || fail 'cannot protect bootstrap private output'
  assert_private_file "$file" || fail 'bootstrap private output is unsafe'
}

write_private_lines() {
  local file="$1"
  shift
  assert_private_file "$file" || fail 'bootstrap private output is unsafe'
  if ! printf '%s\n' "$@" > "$file" 2>/dev/null; then
    fail 'cannot write bootstrap private output'
  fi
  chmod 600 -- "$file" 2>/dev/null || fail 'cannot protect bootstrap private output'
  assert_private_file "$file" || fail 'bootstrap private output is unsafe'
}

initialize_profile() {
  local profile="$1"
  create_private_file "$profile"
  write_private_lines "$profile" "NAVI_BASE_URL=${TARGET[INT001_NAVIGATOR_URL]}"
}

private_output_absent() {
  local path
  for path in \
    "$ROOT_PROFILE" "$UPSTREAM_ADMIN_PROFILE" "$A_CONTROL_PROFILE" "$A_RUNTIME_PROFILE" \
    "$B_CONTROL_PROFILE" "$C_CONTROL_PROFILE" "$WORKER_HOST_MANIFEST" \
    "$A_DIRECTORY_MANIFEST" "$A_AGENT_MANIFEST" "$B_AGENT_MANIFEST" "$C_AGENT_MANIFEST"; do
    [[ ! -e "$path" && ! -L "$path" ]] || return 1
  done
  return 0
}

resolve_trusted_cli_java() {
  local resolved version_line version_value major
  [[ -x "$TRUSTED_JAVA_LINK" ]] || fail 'fixed source CLI Java path is required'
  resolved="$(/usr/bin/readlink -f -- "$TRUSTED_JAVA_LINK")" \
    || fail 'cannot resolve fixed source CLI Java path'
  [[ -f "$resolved" && ! -L "$resolved" && -x "$resolved" ]] \
    || fail 'resolved source CLI Java path is unsafe'
  version_line="$("$resolved" -version 2>&1 | /usr/bin/sed -n '1p')" \
    || fail 'fixed source CLI Java version check failed'
  case "$version_line" in
    *\"*)
      version_value="${version_line#*\"}"
      version_value="${version_value%%\"*}"
      ;;
    *) fail 'fixed source CLI Java version cannot be parsed' ;;
  esac
  [[ "$version_value" =~ ^([0-9]+)(\.[0-9]+)*([+_-].*)?$ ]] \
    || fail 'fixed source CLI Java version cannot be parsed'
  major="${BASH_REMATCH[1]}"
  (( major >= 17 )) || fail 'fixed source CLI Java must be version 17 or newer'
  CLI_JAVA="$resolved"
}

build_cli_classpath() {
  local jar base
  resolve_trusted_cli_java
  [[ -f "$SOURCE_SDK_JAR" && ! -L "$SOURCE_SDK_JAR" ]] \
    || fail 'source-matched Navigator Open SDK is unavailable'
  [[ -d "$SDK_LIB_DIR" && ! -L "$SDK_LIB_DIR" ]] \
    || fail 'Navigator Open SDK dependency directory is unavailable'
  CLI_CLASSPATH="$SOURCE_SDK_JAR"
  shopt -s nullglob
  for jar in "$SDK_LIB_DIR"/*.jar; do
    base="${jar##*/}"
    [[ "$base" == 'navigator-open-sdk-1.0.18.jar' ]] && continue
    [[ -f "$jar" && ! -L "$jar" ]] || {
      shopt -u nullglob
      fail 'Navigator Open SDK dependency is unsafe'
    }
    CLI_CLASSPATH+=":$jar"
  done
  shopt -u nullglob
  [[ "$CLI_CLASSPATH" != "$SOURCE_SDK_JAR" ]] \
    || fail 'Navigator Open SDK dependencies are unavailable'
}

run_cli() {
  local step="$1" profile="$2" extra_env_name="$3" extra_env_value="$4" log
  shift 4
  assert_private_file "$profile" || fail 'bootstrap credential carrier is unsafe'
  log="$PRIVATE_DIR/cli-${step}.log"
  create_private_file "$log"
  if [[ -n "$extra_env_name" ]]; then
    if ! env -i "PATH=$SAFE_PATH" "HOME=$PRIVATE_DIR" \
      "$extra_env_name=$extra_env_value" \
      "$CLI_JAVA" -cp "$CLI_CLASSPATH" "$CLI_MAIN_CLASS" upstream "$@" --profile "$profile" \
      > "$log" 2>&1; then
      fail "bootstrap step failed: $step"
    fi
  elif ! env -i "PATH=$SAFE_PATH" "HOME=$PRIVATE_DIR" \
    "$CLI_JAVA" -cp "$CLI_CLASSPATH" "$CLI_MAIN_CLASS" upstream "$@" --profile "$profile" \
    > "$log" 2>&1; then
    fail "bootstrap step failed: $step"
  fi
  assert_private_file "$log" || fail 'bootstrap CLI log is unsafe'
}

profile_key_allowed() {
  local candidate="$1"
  shift
  local allowed
  for allowed in "$@"; do
    [[ "$candidate" == "$allowed" ]] && return 0
  done
  return 1
}

assert_profile_schema() {
  local profile="$1"
  shift
  local line key value
  declare -A seen=()
  assert_private_file "$profile" || fail 'bootstrap credential carrier is unsafe'
  while IFS= read -r line || [[ -n "$line" ]]; do
    [[ "$line" != *$'\r'* && "$line" == *=* ]] \
      || fail 'bootstrap credential carrier has an invalid line'
    key="${line%%=*}"
    value="${line#*=}"
    [[ "$key" =~ ^[A-Z][A-Z0-9_]*$ ]] \
      || fail 'bootstrap credential carrier has an invalid field'
    profile_key_allowed "$key" "$@" \
      || fail 'bootstrap credential carrier has an unsupported field'
    [[ -z "${seen[$key]+present}" ]] \
      || fail 'bootstrap credential carrier has a duplicate field'
    seen["$key"]="$value"
  done < "$profile"
}

required_profile_value() {
  local profile="$1" requested_key="$2"
  shift 2
  local line key value result='' matches=0
  assert_profile_schema "$profile" "$@"
  while IFS= read -r line || [[ -n "$line" ]]; do
    key="${line%%=*}"
    value="${line#*=}"
    if [[ "$key" == "$requested_key" ]]; then
      result="$value"
      ((matches += 1))
    fi
  done < "$profile"
  [[ "$matches" == 1 && -n "$result" ]] \
    || fail 'bootstrap credential carrier is missing a required value'
  printf '%s' "$result"
}

assert_identifier() {
  [[ "$1" =~ ^[A-Za-z0-9._:-]{1,200}$ ]] \
    || fail 'bootstrap response contains an invalid resource identifier'
}

extract_log_identifier() {
  local log="$1" requested_key="$2" line token candidate='' matches=0
  assert_private_file "$log" || fail 'bootstrap CLI log is unsafe'
  while IFS= read -r line || [[ -n "$line" ]]; do
    local IFS=' '
    for token in $line; do
      if [[ "$token" == "$requested_key="* ]]; then
        candidate="${token#*=}"
        ((matches += 1))
      fi
    done
  done < "$log"
  [[ "$matches" == 1 && -n "$candidate" ]] \
    || fail 'bootstrap response did not contain the required resource identifier'
  assert_identifier "$candidate"
  printf '%s' "$candidate"
}

url_port() {
  local value="$1" port
  port="${value##*:}"
  [[ "$port" =~ ^[1-9][0-9]{0,4}$ ]] && ((port <= 65535)) \
    || fail 'bootstrap target URL has an invalid port'
  printf '%s' "$port"
}

write_worker_host_manifest() {
  local directory_port biz_port
  directory_port="$(url_port "${TARGET[INT001_DIRECTORY_FACADE_URL]}")"
  biz_port="$(url_port "${TARGET[INT001_BIZ_BASE_URL]}")"
  create_private_file "$WORKER_HOST_MANIFEST"
  write_private_lines "$WORKER_HOST_MANIFEST" \
    '{' \
    "  \"workerHostId\": \"$WORKER_HOST_ID\"," \
    '  "hostUrl": "http://127.0.0.1",' \
    "  \"port\": $directory_port," \
    '  "install": "none",' \
    '  "workers": {' \
    '    "claudeCode": {' \
    '      "enabled": true,' \
    "      \"name\": \"INT-001 directory facade $RUN_FINGERPRINT\"," \
    "      \"port\": $directory_port," \
    "      \"baseUrlOverride\": \"${TARGET[INT001_DIRECTORY_FACADE_URL]}\"," \
    '      "authTokenEnv": "INT001_DIRECTORY_FACADE_TOKEN"' \
    '    },' \
    '    "biz": {' \
    '      "enabled": true,' \
    "      \"workerId\": \"$BIZ_WORKER_REQUEST_ID\"," \
    "      \"name\": \"INT-001 disposable Biz $RUN_FINGERPRINT\"," \
    "      \"port\": $biz_port," \
    "      \"baseUrlOverride\": \"${TARGET[INT001_BIZ_BASE_URL]}\"," \
    '      "version": "int001"' \
    '    }' \
    '  }' \
    '}'
}

write_directory_manifest() {
  local workspace="$RUN_DIR/directory-workspaces/$RUN_FINGERPRINT-a"
  create_private_file "$A_DIRECTORY_MANIFEST"
  write_private_lines "$A_DIRECTORY_MANIFEST" \
    '{' \
    "  \"workerId\": \"$PHYSICAL_WORKER_ID\"," \
    "  \"path\": \"$workspace\"," \
    "  \"projectName\": \"int001-$RUN_FINGERPRINT-a\"," \
    '  "workspaceScope": "CLIENT_APP_SHARED",' \
    '  "resolverType": "MANAGED",' \
    "  \"rootRef\": \"$workspace\"," \
    '  "readOnly": false,' \
    '  "allowedPathPrefixes": [' \
    "    \"$workspace\"" \
    '  ],' \
    '  "files": {' \
    "    \"README.md\": \"INT-001 disposable A workspace $RUN_FINGERPRINT\"" \
    '  },' \
    '  "enabled": true' \
    '}'
}

write_agent_manifest() {
  local manifest="$1" client_app_id="$2" agent_code="$3" model_config_id="$4" label="$5"
  create_private_file "$manifest"
  write_private_lines "$manifest" \
    '{' \
    "  \"clientAppId\": \"$client_app_id\"," \
    "  \"agentId\": \"$agent_code\"," \
    "  \"agentCode\": \"$agent_code\"," \
    "  \"skillId\": \"$agent_code-skill\"," \
    "  \"name\": \"INT-001 synthetic agent $label\"," \
    '  "description": "Disposable INT-001 synthetic upstream agent. No real upstream access.",' \
    '  "status": "ENABLED",' \
    "  \"workerId\": \"$BIZ_WORKER_ID\"," \
    "  \"defaultModelConfigId\": \"$model_config_id\"," \
    "  \"defaultModel\": \"$MODEL_NAME\"," \
    '  "contextVisibility": "isolated",' \
    '  "markdownBody": "Use only the disposable INT-001 workspace. Do not access accounts, real TMS, SIM, credentials, or external services.",' \
    '  "resources": [],' \
    '  "functions": [],' \
    '  "materialize": true' \
    '}'
}

assert_runtime_projection() {
  local line key value key_count=0
  declare -A projection=()
  assert_private_file "$OUTPUT_PROFILE" || fail 'runtime child output is unsafe'
  while IFS= read -r line || [[ -n "$line" ]]; do
    [[ "$line" != *$'\r'* && "$line" == *=* ]] \
      || fail 'runtime child output has an invalid line'
    key="${line%%=*}"
    value="${line#*=}"
    [[ -n "$value" ]] || fail 'runtime child output has an empty required field'
    local allowed=0 expected
    for expected in "${OUTPUT_KEYS[@]}"; do
      [[ "$key" == "$expected" ]] && allowed=1
    done
    [[ "$allowed" == 1 && -z "${projection[$key]+present}" ]] \
      || fail 'runtime child output has an unsupported or duplicate field'
    projection["$key"]="$value"
    ((key_count += 1))
  done < "$OUTPUT_PROFILE"
  [[ "$key_count" == "$EXPECTED_OUTPUT_KEY_COUNT" ]] \
    || fail 'runtime child output must contain exactly thirteen fields'
}

write_runtime_projection() {
  local runtime_base runtime_tenant runtime_app runtime_key runtime_secret
  runtime_base="$(required_profile_value "$A_RUNTIME_PROFILE" NAVI_BASE_URL \
    NAVI_BASE_URL NAVI_TENANT_ID NAVI_CLIENT_APP_ID NAVI_CLIENT_APP_KEY NAVI_CLIENT_APP_SECRET NAVI_CLIENT_APP_ACCESS_TOKEN)"
  runtime_tenant="$(required_profile_value "$A_RUNTIME_PROFILE" NAVI_TENANT_ID \
    NAVI_BASE_URL NAVI_TENANT_ID NAVI_CLIENT_APP_ID NAVI_CLIENT_APP_KEY NAVI_CLIENT_APP_SECRET NAVI_CLIENT_APP_ACCESS_TOKEN)"
  runtime_app="$(required_profile_value "$A_RUNTIME_PROFILE" NAVI_CLIENT_APP_ID \
    NAVI_BASE_URL NAVI_TENANT_ID NAVI_CLIENT_APP_ID NAVI_CLIENT_APP_KEY NAVI_CLIENT_APP_SECRET NAVI_CLIENT_APP_ACCESS_TOKEN)"
  runtime_key="$(required_profile_value "$A_RUNTIME_PROFILE" NAVI_CLIENT_APP_KEY \
    NAVI_BASE_URL NAVI_TENANT_ID NAVI_CLIENT_APP_ID NAVI_CLIENT_APP_KEY NAVI_CLIENT_APP_SECRET NAVI_CLIENT_APP_ACCESS_TOKEN)"
  runtime_secret="$(required_profile_value "$A_RUNTIME_PROFILE" NAVI_CLIENT_APP_SECRET \
    NAVI_BASE_URL NAVI_TENANT_ID NAVI_CLIENT_APP_ID NAVI_CLIENT_APP_KEY NAVI_CLIENT_APP_SECRET NAVI_CLIENT_APP_ACCESS_TOKEN)"
  [[ "$runtime_base" == "${TARGET[INT001_NAVIGATOR_URL]}" && "$runtime_tenant" == "$A_TENANT_ID" \
    && "$runtime_app" == "$A_CLIENT_APP_ID" ]] \
    || fail 'runtime credential projection does not match the A ClientApp'
  create_private_file "$OUTPUT_PROFILE"
  write_private_lines "$OUTPUT_PROFILE" \
    'INT001_SYNTHETIC_UPSTREAM_HARNESS=true' \
    "INT001_RUN_ID=$RUN_ID" \
    "INT001_NAVI_BASE_URL=$runtime_base" \
    "INT001_A_TENANT_ID=$runtime_tenant" \
    "INT001_A_CLIENT_APP_ID=$runtime_app" \
    "INT001_A_CLIENT_APP_KEY=$runtime_key" \
    "INT001_A_CLIENT_APP_SECRET=$runtime_secret" \
    "INT001_A_AGENT_ID=$A_AGENT_ID" \
    "INT001_A_UPSTREAM_USER_ID=$A_UPSTREAM_USER_ID" \
    "INT001_A_MODEL_CONFIG_ID=$A_MODEL_CONFIG_ID" \
    "INT001_A_DIRECTORY_ID=$A_DIRECTORY_ID" \
    "INT001_B_AGENT_ID=$B_AGENT_ID" \
    "INT001_C_AGENT_ID=$C_AGENT_ID"
  assert_runtime_projection
}

set_resource_names() {
  RUN_FINGERPRINT="$(printf '%s' "$RUN_ID" | sha256sum | awk '{print substr($1, 1, 12)}')"
  [[ "$RUN_FINGERPRINT" =~ ^[a-f0-9]{12}$ ]] || fail 'cannot derive synthetic run identifiers'
  UPSTREAM_SYSTEM_ID="int001-synth-$RUN_FINGERPRINT"
  A_TENANT_ID="int001-tenant-a-$RUN_FINGERPRINT"
  C_TENANT_ID="int001-tenant-c-$RUN_FINGERPRINT"
  WORKER_HOST_ID="int001-host-$RUN_FINGERPRINT"
  BIZ_WORKER_REQUEST_ID="int001-biz-$RUN_FINGERPRINT"
  A_AGENT_CODE="int001-agent-a-$RUN_FINGERPRINT"
  B_AGENT_CODE="int001-agent-b-$RUN_FINGERPRINT"
  C_AGENT_CODE="int001-agent-c-$RUN_FINGERPRINT"
  A_UPSTREAM_USER_ID="int001-user-a-$RUN_FINGERPRINT"
  MODEL_NAME="int001-static-model-$RUN_FINGERPRINT"
}

bootstrap_upstream_admin() {
  local model_log directory_log agent_log
  PATH="$SAFE_PATH"
  export PATH
  ROOT_PROFILE="$PRIVATE_DIR/root-login.env"
  UPSTREAM_ADMIN_PROFILE="$PRIVATE_DIR/upstream-admin.env"
  A_CONTROL_PROFILE="$PRIVATE_DIR/a-control.env"
  A_RUNTIME_PROFILE="$PRIVATE_DIR/a-runtime.env"
  B_CONTROL_PROFILE="$PRIVATE_DIR/b-control.env"
  C_CONTROL_PROFILE="$PRIVATE_DIR/c-control.env"
  WORKER_HOST_MANIFEST="$PRIVATE_DIR/worker-host.json"
  A_DIRECTORY_MANIFEST="$PRIVATE_DIR/a-directory.json"
  A_AGENT_MANIFEST="$PRIVATE_DIR/a-agent.json"
  B_AGENT_MANIFEST="$PRIVATE_DIR/b-agent.json"
  C_AGENT_MANIFEST="$PRIVATE_DIR/c-agent.json"

  private_output_absent || fail 'bootstrap private output already exists; refusing retry'
  set_resource_names
  build_cli_classpath
  initialize_profile "$ROOT_PROFILE"
  initialize_profile "$UPSTREAM_ADMIN_PROFILE"

  # The root login is an operator-only bootstrap step. Its password exists
  # only in this one clean environment and never becomes part of a profile.
  run_cli root-login "$ROOT_PROFILE" INT001_BOOTSTRAP_ROOT_PASSWORD \
    "${TARGET[INT001_BOOTSTRAP_ROOT_PASSWORD]}" \
    auth login --base-url "${TARGET[INT001_NAVIGATOR_URL]}" --username "${TARGET[INT001_BOOTSTRAP_ROOT_USERNAME]}" \
    --password-env INT001_BOOTSTRAP_ROOT_PASSWORD --write-profile

  # Request/approve/claim deliberately keeps the operator login and the
  # resulting upstream-admin credential in separate private profiles.
  run_cli admin-request "$UPSTREAM_ADMIN_PROFILE" '' '' \
    admin-key request --upstream-system-id "$UPSTREAM_SYSTEM_ID" \
    --requested-tenant-id "$A_TENANT_ID" --multi-tenant \
    --reason 'INT-001 disposable synthetic harness' --applicant-label 'INT-001 synthetic harness' \
    --write-profile
  run_cli admin-approve "$ROOT_PROFILE" '' '' \
    admin-key approve --request-code "$(required_profile_value "$UPSTREAM_ADMIN_PROFILE" NAVI_ADMIN_KEY_REQUEST_CODE \
      NAVI_BASE_URL NAVI_UPSTREAM_SYSTEM_ID NAVI_REQUESTED_TENANT_ID NAVI_UPSTREAM_MULTI_TENANT NAVI_ADMIN_KEY_REQUEST_CODE NAVI_ADMIN_KEY_CLAIM_TOKEN)" \
    --authorized-tenant-ids "$A_TENANT_ID,$C_TENANT_ID" --namespace "$UPSTREAM_SYSTEM_ID" \
    --scopes "$ADMIN_SCOPES"
  run_cli admin-claim "$UPSTREAM_ADMIN_PROFILE" '' '' admin-key claim --write-profile

  # A/B share a tenant, while C is intentionally in a second tenant.  Each
  # gets its own control credential; only A receives a runtime credential.
  run_cli ensure-a "$UPSTREAM_ADMIN_PROFILE" '' '' \
    client-app ensure --target-tenant-id "$A_TENANT_ID" --upstream-ref "$UPSTREAM_SYSTEM_ID-a" \
    --name "INT-001 synthetic A $RUN_FINGERPRINT" --description 'Disposable INT-001 ClientApp A' \
    --tenant-profile "$A_CONTROL_PROFILE" --write-profile
  A_CLIENT_APP_ID="$(required_profile_value "$A_CONTROL_PROFILE" NAVI_CLIENT_APP_ID \
    NAVI_BASE_URL NAVI_TENANT_ID NAVI_CLIENT_APP_ID NAVI_UPSTREAM_SYSTEM_ID NAVI_UPSTREAM_REF)"
  assert_identifier "$A_CLIENT_APP_ID"
  run_cli issue-a-control "$UPSTREAM_ADMIN_PROFILE" '' '' \
    client-app issue-control-key --client-app-id "$A_CLIENT_APP_ID" --description 'INT-001 A control' \
    --scopes "$CONTROL_SCOPES" --tenant-profile "$A_CONTROL_PROFILE" --write-profile
  run_cli issue-a-runtime "$UPSTREAM_ADMIN_PROFILE" '' '' \
    client-app issue-runtime-key --client-app-id "$A_CLIENT_APP_ID" --description 'INT-001 A runtime' \
    --tenant-profile "$A_RUNTIME_PROFILE" --write-profile

  run_cli ensure-b "$UPSTREAM_ADMIN_PROFILE" '' '' \
    client-app ensure --target-tenant-id "$A_TENANT_ID" --upstream-ref "$UPSTREAM_SYSTEM_ID-b" \
    --name "INT-001 synthetic B $RUN_FINGERPRINT" --description 'Disposable INT-001 ClientApp B' \
    --tenant-profile "$B_CONTROL_PROFILE" --write-profile
  B_CLIENT_APP_ID="$(required_profile_value "$B_CONTROL_PROFILE" NAVI_CLIENT_APP_ID \
    NAVI_BASE_URL NAVI_TENANT_ID NAVI_CLIENT_APP_ID NAVI_UPSTREAM_SYSTEM_ID NAVI_UPSTREAM_REF)"
  assert_identifier "$B_CLIENT_APP_ID"
  run_cli issue-b-control "$UPSTREAM_ADMIN_PROFILE" '' '' \
    client-app issue-control-key --client-app-id "$B_CLIENT_APP_ID" --description 'INT-001 B control' \
    --scopes "$CONTROL_SCOPES" --tenant-profile "$B_CONTROL_PROFILE" --write-profile

  run_cli ensure-c "$UPSTREAM_ADMIN_PROFILE" '' '' \
    client-app ensure --target-tenant-id "$C_TENANT_ID" --upstream-ref "$UPSTREAM_SYSTEM_ID-c" \
    --name "INT-001 synthetic C $RUN_FINGERPRINT" --description 'Disposable INT-001 ClientApp C' \
    --tenant-profile "$C_CONTROL_PROFILE" --write-profile
  C_CLIENT_APP_ID="$(required_profile_value "$C_CONTROL_PROFILE" NAVI_CLIENT_APP_ID \
    NAVI_BASE_URL NAVI_TENANT_ID NAVI_CLIENT_APP_ID NAVI_UPSTREAM_SYSTEM_ID NAVI_UPSTREAM_REF)"
  assert_identifier "$C_CLIENT_APP_ID"
  run_cli issue-c-control "$UPSTREAM_ADMIN_PROFILE" '' '' \
    client-app issue-control-key --client-app-id "$C_CLIENT_APP_ID" --description 'INT-001 C control' \
    --scopes "$CONTROL_SCOPES" --tenant-profile "$C_CONTROL_PROFILE" --write-profile

  # This is a new disposable WorkerHost. It supplies a directory-only Claude
  # facade and one LANGGRAPH_BIZ identity; it never creates a Pool, Codex role,
  # or direct OPENAI_CODEX identity.
  write_worker_host_manifest
  run_cli worker-host-verify "$UPSTREAM_ADMIN_PROFILE" '' '' \
    worker-host verify --file "$WORKER_HOST_MANIFEST"
  run_cli worker-host-apply "$UPSTREAM_ADMIN_PROFILE" INT001_DIRECTORY_FACADE_TOKEN \
    "${TARGET[INT001_DIRECTORY_FACADE_TOKEN]}" \
    worker-host apply --file "$WORKER_HOST_MANIFEST" --target-tenant-id "$A_TENANT_ID" --write-profile
  PHYSICAL_WORKER_ID="$(required_profile_value "$UPSTREAM_ADMIN_PROFILE" NAVI_WORKER_ID \
    NAVI_BASE_URL NAVI_UPSTREAM_SYSTEM_ID NAVI_REQUESTED_TENANT_ID NAVI_UPSTREAM_MULTI_TENANT \
    NAVI_ADMIN_KEY_REQUEST_CODE NAVI_ADMIN_KEY_CLAIM_TOKEN NAVI_ADMIN_API_KEY NAVI_WORKER_HOST_ID NAVI_WORKER_ID NAVI_BIZ_WORKER_ID)"
  BIZ_WORKER_ID="$(required_profile_value "$UPSTREAM_ADMIN_PROFILE" NAVI_BIZ_WORKER_ID \
    NAVI_BASE_URL NAVI_UPSTREAM_SYSTEM_ID NAVI_REQUESTED_TENANT_ID NAVI_UPSTREAM_MULTI_TENANT \
    NAVI_ADMIN_KEY_REQUEST_CODE NAVI_ADMIN_KEY_CLAIM_TOKEN NAVI_ADMIN_API_KEY NAVI_WORKER_HOST_ID NAVI_WORKER_ID NAVI_BIZ_WORKER_ID)"
  assert_identifier "$PHYSICAL_WORKER_ID"
  assert_identifier "$BIZ_WORKER_ID"
  [[ "$BIZ_WORKER_ID" != "$PHYSICAL_WORKER_ID" ]] \
    || fail 'WorkerHost did not return a distinct Biz Worker identity'

  # Each ClientApp owns its own LANGGRAPH_BIZ model and Agent. A uses the
  # disposable directory facade plus user grant; B/C only exist as isolation
  # targets and never receive runtime credentials.
  run_cli model-a "$A_CONTROL_PROFILE" NAVI_LLM_API_KEY "int001-mock-$RUN_FINGERPRINT" \
    model create --client-app-id "$A_CLIENT_APP_ID" --name "$MODEL_NAME" --model-name "$MODEL_NAME" \
    --provider openai-compatible --api-key-env NAVI_LLM_API_KEY --model-base-url "${TARGET[INT001_MOCK_LLM_URL]}/v1" \
    --worker-backend LANGGRAPH_BIZ --available-models "$MODEL_NAME" --set-default
  model_log="$PRIVATE_DIR/cli-model-a.log"
  A_MODEL_CONFIG_ID="$(extract_log_identifier "$model_log" modelConfigId)"

  write_directory_manifest
  run_cli directory-a "$A_CONTROL_PROFILE" '' '' \
    directory client-init --client-app-id "$A_CLIENT_APP_ID" --file "$A_DIRECTORY_MANIFEST"
  directory_log="$PRIVATE_DIR/cli-directory-a.log"
  A_DIRECTORY_ID="$(extract_log_identifier "$directory_log" directoryId)"

  write_agent_manifest "$A_AGENT_MANIFEST" "$A_CLIENT_APP_ID" "$A_AGENT_CODE" "$A_MODEL_CONFIG_ID" A
  run_cli agent-a "$A_CONTROL_PROFILE" '' '' \
    agent sync --manifest "$A_AGENT_MANIFEST" --client-app-id "$A_CLIENT_APP_ID"
  agent_log="$PRIVATE_DIR/cli-agent-a.log"
  A_AGENT_ID="$(extract_log_identifier "$agent_log" agentId)"
  [[ "$A_AGENT_ID" == "$A_AGENT_CODE" ]] || fail 'A Agent response does not match the requested Agent identifier'
  run_cli agent-a-model "$A_CONTROL_PROFILE" '' '' \
    agent set-default-model --client-app-id "$A_CLIENT_APP_ID" --agent-code "$A_AGENT_CODE" \
    --model-config-id "$A_MODEL_CONFIG_ID"
  run_cli agent-a-workspace "$A_CONTROL_PROFILE" '' '' \
    agent set-default-workspace --client-app-id "$A_CLIENT_APP_ID" --agent-code "$A_AGENT_CODE" \
    --directory-id "$A_DIRECTORY_ID"
  run_cli grant-a-user "$A_CONTROL_PROFILE" '' '' \
    ensure-grant --client-app-id "$A_CLIENT_APP_ID" --upstream-user-id "$A_UPSTREAM_USER_ID"

  run_cli model-b "$B_CONTROL_PROFILE" NAVI_LLM_API_KEY "int001-mock-$RUN_FINGERPRINT" \
    model create --client-app-id "$B_CLIENT_APP_ID" --name "$MODEL_NAME-b" --model-name "$MODEL_NAME" \
    --provider openai-compatible --api-key-env NAVI_LLM_API_KEY --model-base-url "${TARGET[INT001_MOCK_LLM_URL]}/v1" \
    --worker-backend LANGGRAPH_BIZ --available-models "$MODEL_NAME" --set-default
  B_MODEL_CONFIG_ID="$(extract_log_identifier "$PRIVATE_DIR/cli-model-b.log" modelConfigId)"
  write_agent_manifest "$B_AGENT_MANIFEST" "$B_CLIENT_APP_ID" "$B_AGENT_CODE" "$B_MODEL_CONFIG_ID" B
  run_cli agent-b "$B_CONTROL_PROFILE" '' '' \
    agent sync --manifest "$B_AGENT_MANIFEST" --client-app-id "$B_CLIENT_APP_ID"
  B_AGENT_ID="$(extract_log_identifier "$PRIVATE_DIR/cli-agent-b.log" agentId)"
  [[ "$B_AGENT_ID" == "$B_AGENT_CODE" ]] || fail 'B Agent response does not match the requested Agent identifier'

  run_cli model-c "$C_CONTROL_PROFILE" NAVI_LLM_API_KEY "int001-mock-$RUN_FINGERPRINT" \
    model create --client-app-id "$C_CLIENT_APP_ID" --name "$MODEL_NAME-c" --model-name "$MODEL_NAME" \
    --provider openai-compatible --api-key-env NAVI_LLM_API_KEY --model-base-url "${TARGET[INT001_MOCK_LLM_URL]}/v1" \
    --worker-backend LANGGRAPH_BIZ --available-models "$MODEL_NAME" --set-default
  C_MODEL_CONFIG_ID="$(extract_log_identifier "$PRIVATE_DIR/cli-model-c.log" modelConfigId)"
  write_agent_manifest "$C_AGENT_MANIFEST" "$C_CLIENT_APP_ID" "$C_AGENT_CODE" "$C_MODEL_CONFIG_ID" C
  run_cli agent-c "$C_CONTROL_PROFILE" '' '' \
    agent sync --manifest "$C_AGENT_MANIFEST" --client-app-id "$C_CLIENT_APP_ID"
  C_AGENT_ID="$(extract_log_identifier "$PRIVATE_DIR/cli-agent-c.log" agentId)"
  [[ "$C_AGENT_ID" == "$C_AGENT_CODE" ]] || fail 'C Agent response does not match the requested Agent identifier'
  [[ "$A_AGENT_ID" != "$B_AGENT_ID" && "$A_AGENT_ID" != "$C_AGENT_ID" && "$B_AGENT_ID" != "$C_AGENT_ID" ]] \
    || fail 'synthetic Agent identifiers must remain distinct'

  write_runtime_projection
}

main() {
  parse_args "$@"
  assert_run_ownership
  read_strict_target_profile
  validate_target_urls
  verify_harness_owned_target
  bootstrap_upstream_admin
}

main "$@"
