#!/usr/bin/bash -p
# INT-001 runtime-only audit for a synthetic upstream run.
#
# This is deliberately a separate parent runner.  It never provisions a
# resource, starts/stops a stack, or reads a bootstrap/admin/control profile.
# It accepts only the runtime projection produced for an already-running,
# run-owned disposable stack.
#
# Invoke this file through its privileged shebang (or /usr/bin/bash -p), never
# through a caller-selected `bash`.  The audit handles runtime credentials, so
# shell startup files must not run before its fail-closed checks.

set -euo pipefail
IFS=$'\n\t'
umask 077

# Establish the trusted command lookup before the first path traversal and
# remove shell-startup/directory injection variables from the audit process.
readonly SAFE_PATH='/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin'
PATH="$SAFE_PATH"
export PATH
unset BASH_ENV ENV CDPATH
case "$-" in
  *p*) ;;
  *)
    printf 'INT-001 runtime audit: requires /usr/bin/bash -p\n' >&2
    exit 2
    ;;
esac

readonly SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
readonly REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd -P)"
readonly ARTIFACT_ROOT="$REPO_ROOT/temp/test-artifacts/INT-001"
readonly PRIVATE_DIRECTORY_NAME='private'
readonly BOOTSTRAP_TARGET_PROFILE_NAME='bootstrap-target.env'
readonly RUN_MANIFEST_NAME='run-manifest.json'
readonly PRIVATE_CHILD_PROFILE_NAME='runtime-child.env'
readonly RUNTIME_CLI_PROFILE_NAME='runtime-cli.env'
readonly RUNTIME_AUDIT_REPORT_NAME='runtime-audit-report.json'
# This is deliberately a root-level, redacted receipt rather than another
# carrier.  It survives the normal cleanup after the private report and all
# credential-bearing files have been removed, so a failed audit can be
# triaged without reading a private log/profile or repeating a live run.
readonly RUNTIME_AUDIT_SUMMARY_NAME='runtime-audit-summary.json'
readonly SOURCE_SDK_JAR="$REPO_ROOT/navigator-open-sdk/target/navigator-open-sdk-1.0.21.jar"
readonly SDK_LIB_DIR="$REPO_ROOT/tools/navigator-upstream/lib"
readonly TRUSTED_JAVA_LINK='/usr/bin/java'
readonly LOCAL_DOCKER_HOST='unix:///var/run/docker.sock'
readonly RUN_ID_PATTERN='^[a-z0-9][a-z0-9-]{5,63}$'
readonly RUNTIME_CHILD_KEY_COUNT=13
readonly BIZ_INGRESS_COUNTER_NAME='biz-ingress-count'
readonly BIZ_INGRESS_LOCK_NAME='biz-ingress-count.lock'

RUN_DIR=''
RUN_ID=''
ALLOW_EXECUTE=0
PRIVATE_DIR=''
RUNTIME_CHILD_PROFILE=''
RUNTIME_CLI_PROFILE=''
REPORT_FILE=''
MOCK_URL=''
NAVIGATOR_URL=''
BIZ_INGRESS_PROXY_URL=''
COMPOSE_PROJECT=''
CLI_CLASSPATH=''
CLI_JAVA=''
NODE_BIN=''
NODE_BIN_DIR=''

REPORT_ENABLED=0
REPORT_STATUS='FAIL'
FAILURE_CATEGORY='input'
# A fixed, non-sensitive subcategory used only when the immutable ownership
# preflight rejects a run-owned component.  It is intentionally never derived
# from a path, PID, Docker id, command line, profile, URL, or log content.
OWNERSHIP_FAILURE_TARGET='none'
# A fixed, non-sensitive execution-evidence target.  Unlike ownership
# failures, a nonzero ingress baseline proves that the supposedly
# non-dispatch pre-audit path contacted the Biz Worker; it must never be
# normalized away or reported as a filesystem ownership defect.
EXECUTION_FAILURE_TARGET='none'
RUNTIME_TOKEN_STATUS='NOT_RUN'
VERIFY_AGENT_READINESS_STATUS='NOT_RUN'
OWNER_SMOKE_STATUS='NOT_RUN'
CHILD_STATUS='NOT_RUN'
# These are the only child diagnostics that may cross the private Vitest log
# boundary into the root-level redacted receipt. They are fixed enums, not
# test output: raw request bodies, URLs, IDs, paths, credentials, and error
# messages must never be parsed or published here.
CHILD_PHASE='NOT_RUN'
CHILD_FAILURE_CLASS='NONE'
CHILD_POSITIVE_TASK_CREATED='false'
CHILD_DENIED_TASK_CREATED='false'
CHILD_DENY_CASES=''
POSITIVE_MODEL_SUBMISSION_COUNT=-1
POSITIVE_QUERY_INGRESS_DELTA=-1
SUMMARY_FILE=''
SUMMARY_ENABLED=0

declare -A CHILD_ENV=()
declare -a REGISTERED_TRACES=()
declare -a DENY_PROBES=(
  deny-control
  deny-admin
  deny-same-client-app
  deny-cross-tenant
  deny-model-grant
  deny-directory
  deny-upstream-user
)
declare -A DENY_MODEL_SUBMISSION_COUNTS=()
declare -A DENY_QUERY_INGRESS_DELTAS=()

usage() {
  cat <<'USAGE'
Usage:
  synthetic-upstream-runtime-audit.sh --allow-execute --run-dir <runDir>

The run directory must be an already-running, run-owned INT-001 disposable
stack.  The audit reads only private/runtime-child.env and never starts,
stops, bootstraps, or cleans up the stack.
USAGE
}

ownership_failure_target_for_output() {
  case "$OWNERSHIP_FAILURE_TARGET" in
    launcher|biz-worker|biz-ingress-proxy|directory-facade|ingress-counter|ingress-lock|mock-llm)
      printf '%s' "$OWNERSHIP_FAILURE_TARGET"
      ;;
    *)
      printf 'unknown'
      ;;
  esac
}

execution_failure_target_for_output() {
  case "$EXECUTION_FAILURE_TARGET" in
    unexpected-pre-audit-ingress|unexpected-runtime-token-ingress|unexpected-readiness-ingress|unexpected-owner-smoke-ingress|unexpected-pre-positive-ingress)
      printf '%s' "$EXECUTION_FAILURE_TARGET"
      ;;
    *)
      printf 'none'
      ;;
  esac
}

failure_target_for_output() {
  case "$FAILURE_CATEGORY" in
    ownership)
      ownership_failure_target_for_output
      ;;
    execution_evidence)
      execution_failure_target_for_output
      ;;
    *)
      printf 'none'
      ;;
  esac
}

terminal_status() {
  local status="$1" category failure_target=''
  case "$status" in
    PASS)
      ;;
    FAIL)
      # Failure categories are fixed, non-sensitive diagnostics.  Expose only
      # this allow-list so an audit failure can be triaged without printing a
      # private profile, log, request body, URL, or credential.
      case "$FAILURE_CATEGORY" in
        input|ownership|tooling|runtime_cli|execution_evidence|report) category="$FAILURE_CATEGORY" ;;
        *) category='unknown' ;;
      esac
      failure_target="$(failure_target_for_output)"
      if [[ "$failure_target" == none ]]; then
        failure_target=''
      fi
      ;;
    *)
      return 1
      ;;
  esac
  if [[ -n "$RUN_ID" ]]; then
    if [[ "$status" == FAIL ]]; then
      if [[ -n "$failure_target" ]]; then
        printf 'INT001_RUNTIME_AUDIT runId=%s status=%s failureCategory=%s failureTarget=%s\n' \
          "$RUN_ID" "$status" "$category" "$failure_target"
      else
        printf 'INT001_RUNTIME_AUDIT runId=%s status=%s failureCategory=%s\n' "$RUN_ID" "$status" "$category"
      fi
    else
      printf 'INT001_RUNTIME_AUDIT runId=%s status=%s\n' "$RUN_ID" "$status"
    fi
  else
    if [[ "$status" == FAIL ]]; then
      if [[ -n "$failure_target" ]]; then
        printf 'INT001_RUNTIME_AUDIT status=%s failureCategory=%s failureTarget=%s\n' \
          "$status" "$category" "$failure_target"
      else
        printf 'INT001_RUNTIME_AUDIT status=%s failureCategory=%s\n' "$status" "$category"
      fi
    else
      printf 'INT001_RUNTIME_AUDIT status=%s\n' "$status"
    fi
  fi
}

count_json() {
  local value="$1"
  if [[ "$value" =~ ^[0-9]+$ ]]; then
    printf '%s' "$value"
  else
    printf 'null'
  fi
}

summary_failure_category_allowed() {
  case "$1" in
    none|input|ownership|tooling|runtime_cli|execution_evidence|report) return 0 ;;
    *) return 1 ;;
  esac
}

summary_failure_target_allowed() {
  case "$1" in
    none|launcher|biz-worker|biz-ingress-proxy|directory-facade|ingress-counter|ingress-lock|mock-llm|unexpected-pre-audit-ingress|unexpected-runtime-token-ingress|unexpected-readiness-ingress|unexpected-owner-smoke-ingress|unexpected-pre-positive-ingress|unknown)
      return 0
      ;;
    *) return 1 ;;
  esac
}

summary_stage_status_allowed() {
  case "$1" in
    NOT_RUN|PASS|FAIL) return 0 ;;
    *) return 1 ;;
  esac
}

summary_child_status_allowed() {
  case "$1" in
    NOT_RUN|PASS|FAIL) return 0 ;;
    *) return 1 ;;
  esac
}

summary_child_result_allowed() {
  # Keep status, phase, and class coupled. An allow-listed value in isolation
  # is insufficient: for example, a failed child may not claim COMPLETE/NONE.
  case "$1:$2:$3" in
    NOT_RUN:NOT_RUN:NONE|PASS:COMPLETE:NONE|FAIL:RUNNER:RESULT_PROTOCOL|\
    FAIL:RUNTIME_TOKEN:RUNTIME_TOKEN_EXCHANGE|\
    FAIL:POSITIVE_READINESS:READINESS_CONTRACT|\
    FAIL:POSITIVE_ASK:ASK_RX_REJECTED|\
    FAIL:POSITIVE_ASK:ASK_TASK_ID_MISSING|\
    FAIL:POSITIVE_ASK:ASK_SUBMISSION_ERROR|\
    FAIL:POSITIVE_TASK_TERMINAL:TASK_TERMINAL|\
    FAIL:POSITIVE_DIAGNOSTICS:DIAGNOSTICS_CONTRACT|\
    FAIL:DENY_CONTROL:DENY_CONTRACT|\
    FAIL:DENY_ADMIN:DENY_CONTRACT|\
    FAIL:DENY_SAME_CLIENT_APP:DENY_CONTRACT|\
    FAIL:DENY_CROSS_TENANT:DENY_CONTRACT|\
    FAIL:DENY_MODEL_GRANT:DENY_CONTRACT|\
    FAIL:DENY_DIRECTORY:DENY_CONTRACT|\
    FAIL:DENY_UPSTREAM_USER:DENY_CONTRACT)
      return 0
      ;;
    *)
      return 1
      ;;
  esac
}

summary_deny_probe_allowed() {
  case "$1" in
    deny-control|deny-admin|deny-same-client-app|deny-cross-tenant|deny-model-grant|deny-directory|deny-upstream-user)
      return 0
      ;;
    *) return 1 ;;
  esac
}

summary_failure_target() {
  failure_target_for_output
}

summary_file_identity() {
  local file="$1" identity
  [[ -f "$file" && ! -L "$file" ]] || return 1
  identity="$(stat -c '%d:%i' -- "$file" 2>/dev/null)" || return 1
  [[ "$identity" =~ ^[0-9]+:[0-9]+$ ]] || return 1
  printf '%s' "$identity"
}

remove_published_summary_if_owned() {
  local expected_identity="$1" actual_identity
  actual_identity="$(summary_file_identity "$SUMMARY_FILE" 2>/dev/null || true)"
  [[ -n "$actual_identity" && "$actual_identity" == "$expected_identity" ]] || return 0
  rm -f -- "$SUMMARY_FILE"
}

write_runtime_audit_summary() {
  [[ "$SUMMARY_ENABLED" == 1 && -n "$SUMMARY_FILE" ]] || return 0
  [[ "$REPORT_STATUS" == PASS || "$REPORT_STATUS" == FAIL ]] || return 1
  summary_failure_category_allowed "$FAILURE_CATEGORY" || return 1
  summary_stage_status_allowed "$RUNTIME_TOKEN_STATUS" || return 1
  summary_stage_status_allowed "$VERIFY_AGENT_READINESS_STATUS" || return 1
  summary_stage_status_allowed "$OWNER_SMOKE_STATUS" || return 1
  summary_child_status_allowed "$CHILD_STATUS" || return 1
  summary_child_result_allowed "$CHILD_STATUS" "$CHILD_PHASE" "$CHILD_FAILURE_CLASS" || return 1
  [[ "$CHILD_POSITIVE_TASK_CREATED" == true || "$CHILD_POSITIVE_TASK_CREATED" == false ]] || return 1
  [[ "$CHILD_DENIED_TASK_CREATED" == true || "$CHILD_DENIED_TASK_CREATED" == false ]] || return 1

  local target temporary temporary_identity published_identity probe first=1
  target="$(summary_failure_target)"
  summary_failure_target_allowed "$target" || return 1
  assert_private_dir "$RUN_DIR" || return 1
  [[ ! -e "$SUMMARY_FILE" && ! -L "$SUMMARY_FILE" ]] || return 1
  temporary="$(mktemp "$RUN_DIR/.runtime-audit-summary.XXXXXX")" || return 1
  chmod 600 "$temporary" || {
    rm -f -- "$temporary"
    return 1
  }
  {
    printf '{\n'
    printf '  "schemaVersion": 1,\n'
    printf '  "runId": "%s",\n' "$RUN_ID"
    printf '  "status": "%s",\n' "$REPORT_STATUS"
    printf '  "failureCategory": "%s",\n' "$FAILURE_CATEGORY"
    printf '  "failureTarget": "%s",\n' "$target"
    printf '  "cli": {"runtimeToken": "%s", "verifyAgentReadiness": "%s", "ownerSmoke": "%s"},\n' \
      "$RUNTIME_TOKEN_STATUS" "$VERIFY_AGENT_READINESS_STATUS" "$OWNER_SMOKE_STATUS"
    printf '  "child": {"status": "%s", "phase": "%s", "failureClass": "%s", "positiveTaskCreated": %s, "deniedTaskCreated": %s},\n' \
      "$CHILD_STATUS" "$CHILD_PHASE" "$CHILD_FAILURE_CLASS" "$CHILD_POSITIVE_TASK_CREATED" "$CHILD_DENIED_TASK_CREATED"
    printf '  "positiveModelSubmissionCount": %s,\n' "$(count_json "$POSITIVE_MODEL_SUBMISSION_COUNT")"
    printf '  "positiveQueryIngressDelta": %s,\n' "$(count_json "$POSITIVE_QUERY_INGRESS_DELTA")"
    printf '  "denyModelSubmissionCounts": {'
    for probe in "${DENY_PROBES[@]}"; do
      if [[ "$first" == 0 ]]; then
        printf ', '
      fi
      first=0
      printf '"%s": %s' "$probe" "$(count_json "${DENY_MODEL_SUBMISSION_COUNTS[$probe]:--1}")"
    done
    printf '},\n'
    printf '  "denyQueryIngressDeltas": {'
    first=1
    for probe in "${DENY_PROBES[@]}"; do
      if [[ "$first" == 0 ]]; then
        printf ', '
      fi
      first=0
      printf '"%s": %s' "$probe" "$(count_json "${DENY_QUERY_INGRESS_DELTAS[$probe]:--1}")"
    done
    printf '},\n'
    printf '  "denyCases": ['
    local -a cases=()
    if [[ -n "$CHILD_DENY_CASES" ]]; then
      IFS=',' read -r -a cases <<< "$CHILD_DENY_CASES"
    fi
    first=1
    local case_name
    for case_name in "${cases[@]}"; do
      [[ -n "$case_name" ]] || continue
      summary_deny_probe_allowed "$case_name" || {
        rm -f -- "$temporary"
        return 1
      }
      if [[ "$first" == 0 ]]; then
        printf ', '
      fi
      first=0
      printf '"%s"' "$case_name"
    done
    printf '],\n'
    printf '  "secretsRedacted": true\n'
    printf '}\n'
  } > "$temporary" || {
    rm -f -- "$temporary"
    return 1
  }
  # Validate the completed temporary file before it becomes durable evidence.
  # A hard-link create in the same trusted directory is both atomic and
  # no-clobber: unlike `mv -f`, a receipt that appears after the preflight
  # cannot be replaced.
  assert_private_file "$temporary" || {
    rm -f -- "$temporary"
    return 1
  }
  temporary_identity="$(summary_file_identity "$temporary")" || {
    rm -f -- "$temporary"
    return 1
  }
  ln -T -- "$temporary" "$SUMMARY_FILE" || {
    rm -f -- "$temporary"
    return 1
  }
  published_identity="$(summary_file_identity "$SUMMARY_FILE" 2>/dev/null || true)"
  [[ "$published_identity" == "$temporary_identity" ]] || {
    remove_published_summary_if_owned "$temporary_identity" || true
    rm -f -- "$temporary" || true
    return 1
  }
  rm -f -- "$temporary" || {
    remove_published_summary_if_owned "$temporary_identity" || true
    rm -f -- "$temporary" || true
    return 1
  }
  # A failed post-publication verification must not leave a misleading PASS
  # receipt behind.  Remove only the inode this invocation created.
  assert_private_file "$SUMMARY_FILE" || {
    remove_published_summary_if_owned "$temporary_identity" || true
    return 1
  }
}

write_report() {
  [[ "$REPORT_ENABLED" == 1 && -n "$REPORT_FILE" ]] || return 0
  [[ ! -e "$REPORT_FILE" && ! -L "$REPORT_FILE" ]] || return 1
  summary_child_result_allowed "$CHILD_STATUS" "$CHILD_PHASE" "$CHILD_FAILURE_CLASS" || return 1

  local temporary probe first=1
  # A report contains lifecycle and route evidence for this run.  Keep it in
  # the same private carrier directory as the manifest, profiles, and logs;
  # no runtime-audit artifact is published at the run root.
  assert_private_dir "$PRIVATE_DIR" || return 1
  temporary="$(mktemp "$PRIVATE_DIR/.runtime-audit-report.XXXXXX")" || return 1
  chmod 600 "$temporary" || {
    rm -f -- "$temporary"
    return 1
  }
  {
    printf '{\n'
    printf '  "schemaVersion": 1,\n'
    printf '  "runId": "%s",\n' "$RUN_ID"
    printf '  "status": "%s",\n' "$REPORT_STATUS"
    printf '  "failureCategory": "%s",\n' "$FAILURE_CATEGORY"
    printf '  "cli": {"runtimeToken": "%s", "verifyAgentReadiness": "%s", "ownerSmoke": "%s"},\n' \
      "$RUNTIME_TOKEN_STATUS" "$VERIFY_AGENT_READINESS_STATUS" "$OWNER_SMOKE_STATUS"
    printf '  "child": {"status": "%s", "phase": "%s", "failureClass": "%s", "positiveTaskCreated": %s, "deniedTaskCreated": %s},\n' \
      "$CHILD_STATUS" "$CHILD_PHASE" "$CHILD_FAILURE_CLASS" "$CHILD_POSITIVE_TASK_CREATED" "$CHILD_DENIED_TASK_CREATED"
    printf '  "positiveModelSubmissionCount": %s,\n' "$(count_json "$POSITIVE_MODEL_SUBMISSION_COUNT")"
    printf '  "positiveQueryIngressDelta": %s,\n' "$(count_json "$POSITIVE_QUERY_INGRESS_DELTA")"
    printf '  "denyModelSubmissionCounts": {'
    for probe in "${DENY_PROBES[@]}"; do
      if [[ "$first" == 0 ]]; then
        printf ', '
      fi
      first=0
      printf '"%s": %s' "$probe" "$(count_json "${DENY_MODEL_SUBMISSION_COUNTS[$probe]:--1}")"
    done
    printf '},\n'
    printf '  "denyQueryIngressDeltas": {'
    first=1
    for probe in "${DENY_PROBES[@]}"; do
      if [[ "$first" == 0 ]]; then
        printf ', '
      fi
      first=0
      printf '"%s": %s' "$probe" "$(count_json "${DENY_QUERY_INGRESS_DELTAS[$probe]:--1}")"
    done
    printf '},\n'
    printf '  "denyCases": ['
    local -a cases=()
    if [[ -n "$CHILD_DENY_CASES" ]]; then
      IFS=',' read -r -a cases <<< "$CHILD_DENY_CASES"
    fi
    first=1
    local case_name
    for case_name in "${cases[@]}"; do
      [[ -n "$case_name" ]] || continue
      if [[ "$first" == 0 ]]; then
        printf ', '
      fi
      first=0
      printf '"%s"' "$case_name"
    done
    printf '],\n'
    printf '  "secretsRedacted": true\n'
    printf '}\n'
  } > "$temporary" || {
    rm -f -- "$temporary"
    return 1
  }
  mv -f -- "$temporary" "$REPORT_FILE" || {
    rm -f -- "$temporary"
    return 1
  }
  chmod 600 "$REPORT_FILE" || return 1
  return 0
}

fail() {
  FAILURE_CATEGORY="$1"
  REPORT_STATUS='FAIL'
  write_report >/dev/null 2>&1 || true
  write_runtime_audit_summary >/dev/null 2>&1 || true
  terminal_status FAIL
  exit 2
}

cleanup_registered_scripts() {
  local trace
  [[ -n "$MOCK_URL" ]] || return 0
  for trace in "${REGISTERED_TRACES[@]}"; do
    # Do not print or persist DELETE bodies.  The Mock service removes matching
    # debug records together with the script.
    env -i "PATH=$SAFE_PATH" "HOME=$RUN_DIR/home" \
      curl --noproxy '*' --silent --show-error --max-time 5 --request DELETE \
      "$MOCK_URL/__e2e/scripts/$trace" >/dev/null 2>&1 || true
  done
}

trap cleanup_registered_scripts EXIT

require_value() {
  [[ -n "${2:-}" ]]
}

parse_args() {
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --allow-execute)
        [[ "$ALLOW_EXECUTE" == 0 ]] || return 1
        ALLOW_EXECUTE=1
        shift
        ;;
      --run-dir)
        require_value "$1" "${2:-}" || return 1
        [[ -z "$RUN_DIR" ]] || return 1
        RUN_DIR="$2"
        shift 2
        ;;
      --help|-h)
        usage
        exit 0
        ;;
      *)
        return 1
        ;;
    esac
  done
  [[ "$ALLOW_EXECUTE" == 1 && -n "$RUN_DIR" ]]
}

assert_private_file() {
  local file="$1" mode owner links
  [[ -f "$file" && ! -L "$file" ]] || return 1
  mode="$(stat -c '%a' -- "$file" 2>/dev/null)" || return 1
  owner="$(stat -c '%u' -- "$file" 2>/dev/null)" || return 1
  links="$(stat -c '%h' -- "$file" 2>/dev/null)" || return 1
  [[ "$mode" == 600 && "$owner" == "$(id -u)" && "$links" == 1 ]]
}

assert_private_dir() {
  local directory="$1" mode owner
  [[ -d "$directory" && ! -L "$directory" ]] || return 1
  mode="$(stat -c '%a' -- "$directory" 2>/dev/null)" || return 1
  owner="$(stat -c '%u' -- "$directory" 2>/dev/null)" || return 1
  [[ "$mode" == 700 && "$owner" == "$(id -u)" ]]
}

read_query_ingress_count() {
  local counter="$RUN_DIR/$PRIVATE_DIRECTORY_NAME/$BIZ_INGRESS_COUNTER_NAME"
  local lock="$RUN_DIR/$PRIVATE_DIRECTORY_NAME/$BIZ_INGRESS_LOCK_NAME" value
  # The proxy replaces the fixed counter atomically while holding the fixed
  # private lock exclusively.  Read it only while sharing that lock: otherwise
  # a valid replacement can leave an already-open old inode with nlink=0 and
  # look indistinguishable from unsafe evidence.  The Python helper validates
  # both paths and file descriptors before and after acquiring the lock, so a
  # stale path, symlink, broader mode, or multiline content cannot become
  # evidence.  Only the bounded parsed number is returned; state files,
  # request payloads, and proxy configuration are never printed.
  if ! value="$(env -i "PATH=$SAFE_PATH" python3 -c '
import fcntl
import os
import re
import stat
import sys

counter_path, lock_path = sys.argv[1], sys.argv[2]

def validate_private_regular(details):
    if (not stat.S_ISREG(details.st_mode) or stat.S_ISLNK(details.st_mode)
            or stat.S_IMODE(details.st_mode) != 0o600
            or details.st_uid != os.getuid() or details.st_nlink != 1):
        raise ValueError()

def identity(details):
    return (details.st_dev, details.st_ino)

try:
    lock_before = os.lstat(lock_path)
    validate_private_regular(lock_before)
    flags = os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0)
    lock_descriptor = os.open(lock_path, flags)
    try:
        lock_after_open = os.fstat(lock_descriptor)
        validate_private_regular(lock_after_open)
        if identity(lock_before) != identity(lock_after_open):
            raise ValueError()
        fcntl.flock(lock_descriptor, fcntl.LOCK_SH)
        try:
            lock_after_lock = os.lstat(lock_path)
            validate_private_regular(lock_after_lock)
            if identity(lock_after_lock) != identity(lock_after_open):
                raise ValueError()
            counter_before = os.lstat(counter_path)
            validate_private_regular(counter_before)
            counter_descriptor = os.open(counter_path, flags)
            try:
                counter_after_open = os.fstat(counter_descriptor)
                validate_private_regular(counter_after_open)
                if identity(counter_before) != identity(counter_after_open):
                    raise ValueError()
                raw = os.read(counter_descriptor, 32)
                if os.read(counter_descriptor, 1):
                    raise ValueError()
            finally:
                os.close(counter_descriptor)
        finally:
            fcntl.flock(lock_descriptor, fcntl.LOCK_UN)
    finally:
        os.close(lock_descriptor)
    decoded = raw.decode("ascii")
    if not re.fullmatch(r"[0-9]{1,9}\n", decoded):
        raise ValueError()
    print(decoded[:-1])
except Exception:
    raise SystemExit(2)
' "$counter" "$lock" 2>/dev/null)"; then
    return 1
  fi
  [[ "$value" =~ ^[0-9]+$ ]] || return 1
  printf '%s' "$value"
}

resolve_and_validate_run_dir() {
  local root_real run_real candidate_id
  [[ ! -L "$ARTIFACT_ROOT" ]] || return 1
  root_real="$(realpath -e -- "$ARTIFACT_ROOT" 2>/dev/null)" || return 1
  run_real="$(realpath -e -- "$RUN_DIR" 2>/dev/null)" || return 1
  candidate_id="${run_real##*/}"
  [[ "$candidate_id" =~ $RUN_ID_PATTERN ]] || return 1
  [[ "$run_real" == "$root_real/$candidate_id" ]] || return 1
  [[ -d "$run_real" && ! -L "$run_real" ]] || return 1
  RUN_DIR="$run_real"
  RUN_ID="$candidate_id"
  assert_private_dir "$RUN_DIR"
}

runtime_child_key_allowed() {
  case "$1" in
    INT001_SYNTHETIC_UPSTREAM_HARNESS|INT001_RUN_ID|INT001_NAVI_BASE_URL|INT001_A_TENANT_ID|INT001_A_CLIENT_APP_ID|INT001_A_CLIENT_APP_KEY|INT001_A_CLIENT_APP_SECRET|INT001_A_AGENT_ID|INT001_A_UPSTREAM_USER_ID|INT001_A_MODEL_CONFIG_ID|INT001_A_DIRECTORY_ID|INT001_B_AGENT_ID|INT001_C_AGENT_ID)
      return 0
      ;;
    *)
      return 1
      ;;
  esac
}

read_runtime_child_env() {
  local file="$1" target_name="$2"
  local -n target="$target_name"
  local raw key value line_number=0
  target=()
  assert_private_file "$file" || return 1
  while IFS= read -r raw || [[ -n "$raw" ]]; do
    ((line_number += 1))
    [[ -n "$raw" && "$raw" != \#* && "$raw" == *=* ]] || return 1
    key="${raw%%=*}"
    value="${raw#*=}"
    [[ "$key" =~ ^[A-Z][A-Z0-9_]*$ ]] || return 1
    [[ "$value" != *$'\r'* && "$value" != *$'\n'* && -n "$value" ]] || return 1
    [[ -z "${target[$key]+present}" ]] || return 1
    runtime_child_key_allowed "$key" || return 1
    target["$key"]="$value"
  done < "$file"
}

validate_loopback_url() {
  local url="$1"
  env -i "PATH=$SAFE_PATH" python3 -c '
import sys
from ipaddress import ip_address
from urllib.parse import urlsplit
try:
    value = urlsplit(sys.argv[1])
    if value.scheme != "http" or value.username or value.password or value.query or value.fragment:
        raise ValueError()
    if value.path not in ("", "/") or not value.hostname or value.port is None or value.port == 8112:
        raise ValueError()
    host = value.hostname.lower()
    if host != "localhost":
        parsed = ip_address(host)
        if not parsed.is_loopback:
            raise ValueError()
except Exception:
    raise SystemExit(2)
' "$url" >/dev/null 2>&1
}

validate_run_manifest() {
  local manifest="$RUN_DIR/$PRIVATE_DIRECTORY_NAME/$RUN_MANIFEST_NAME" values
  assert_private_file "$manifest" || return 1
  if ! values="$(env -i "PATH=$SAFE_PATH" python3 -c '
import json
import sys
try:
    with open(sys.argv[1], "r", encoding="utf-8") as source:
        manifest = json.load(source)
    required = ("schemaVersion", "runId", "composeProject", "state", "navigatorUrl", "mockLlmUrl", "bizIngressProxyUrl", "openApiExternalEnabled", "workerGatewayExternalEnabled", "bizWorkerExternalEnabled", "bootstrapTarget")
    if not isinstance(manifest, dict) or any(key not in manifest for key in required):
        raise ValueError()
    if type(manifest["schemaVersion"]) is not int or not isinstance(manifest["runId"], str) or not isinstance(manifest["composeProject"], str) or not isinstance(manifest["state"], str):
        raise ValueError()
    if not isinstance(manifest["navigatorUrl"], str) or not isinstance(manifest["mockLlmUrl"], str) or not isinstance(manifest["bizIngressProxyUrl"], str) or not isinstance(manifest["bootstrapTarget"], str):
        raise ValueError()
    if type(manifest["openApiExternalEnabled"]) is not bool or type(manifest["workerGatewayExternalEnabled"]) is not bool or type(manifest["bizWorkerExternalEnabled"]) is not bool:
        raise ValueError()
    print(manifest["schemaVersion"])
    print(manifest["runId"])
    print(manifest["composeProject"])
    print(manifest["state"])
    print(manifest["navigatorUrl"])
    print(manifest["mockLlmUrl"])
    print(manifest["bizIngressProxyUrl"])
    print(str(manifest["openApiExternalEnabled"]).lower())
    print(str(manifest["workerGatewayExternalEnabled"]).lower())
    print(str(manifest["bizWorkerExternalEnabled"]).lower())
    print(manifest["bootstrapTarget"])
except Exception:
    raise SystemExit(2)
' "$manifest" 2>/dev/null)"; then
    return 1
  fi
  local -a manifest_values=()
  mapfile -t manifest_values <<< "$values"
  [[ "${#manifest_values[@]}" == 11 ]] || return 1
  [[ "${manifest_values[0]}" == 2 ]] || return 1
  [[ "${manifest_values[1]}" == "$RUN_ID" ]] || return 1
  COMPOSE_PROJECT="int001_${RUN_ID//-/_}"
  [[ "${manifest_values[2]}" == "$COMPOSE_PROJECT" ]] || return 1
  [[ "${manifest_values[3]}" == RUNNING || "${manifest_values[3]}" == BOOTSTRAPPED ]] || return 1
  NAVIGATOR_URL="${manifest_values[4]}"
  MOCK_URL="${manifest_values[5]}"
  BIZ_INGRESS_PROXY_URL="${manifest_values[6]}"
  validate_loopback_url "$NAVIGATOR_URL" || return 1
  validate_loopback_url "$MOCK_URL" || return 1
  validate_loopback_url "$BIZ_INGRESS_PROXY_URL" || return 1
  [[ "$NAVIGATOR_URL" != "$MOCK_URL" && "$NAVIGATOR_URL" != "$BIZ_INGRESS_PROXY_URL" && "$MOCK_URL" != "$BIZ_INGRESS_PROXY_URL" ]] || return 1
  [[ "${manifest_values[7]}" == true && "${manifest_values[8]}" == false && "${manifest_values[9]}" == false ]] || return 1
  [[ "${manifest_values[10]}" == "$PRIVATE_DIRECTORY_NAME/$BOOTSTRAP_TARGET_PROFILE_NAME" ]] || return 1
}

child_meta_key_allowed() {
  case "$1" in
    INT001_CHILD_NAME|PID|START_TICKS|PGID|SID|CWD|COMMAND_FRAGMENT)
      return 0
      ;;
    *)
      return 1
      ;;
  esac
}

read_child_meta() {
  local file="$1" target_name="$2"
  local -n target="$target_name"
  local raw key value
  target=()
  assert_private_file "$file" || return 1
  while IFS= read -r raw || [[ -n "$raw" ]]; do
    [[ -n "$raw" && "$raw" != \#* && "$raw" == *=* ]] || return 1
    key="${raw%%=*}"
    value="${raw#*=}"
    child_meta_key_allowed "$key" || return 1
    [[ -n "$value" && "$value" != *$'\r'* && "$value" != *$'\n'* ]] || return 1
    [[ -z "${target[$key]+present}" ]] || return 1
    target["$key"]="$value"
  done < "$file"
  [[ "${#target[@]}" == 7 ]]
}

pid_start_ticks() {
  local pid="$1"
  [[ "$pid" =~ ^[0-9]+$ && -r "/proc/$pid/stat" ]] || return 1
  awk '{print $22}' "/proc/$pid/stat"
}

child_is_zombie() {
  local pid="$1" state
  state="$(ps -o stat= -p "$pid" 2>/dev/null | tr -d '[:space:]')" || return 1
  [[ "$state" == Z* ]]
}

validate_owned_child() {
  local name="$1" fragment="$2" meta
  meta="$RUN_DIR/children/$name.pid"
  local -A child_meta=()
  local pid start now_start pgid sid cwd args
  assert_private_dir "$RUN_DIR/children" || return 1
  read_child_meta "$meta" child_meta || return 1
  [[ "${child_meta[INT001_CHILD_NAME]:-}" == "$name" && "${child_meta[COMMAND_FRAGMENT]:-}" == "$fragment" ]] || return 1
  pid="${child_meta[PID]:-}"
  start="${child_meta[START_TICKS]:-}"
  pgid="${child_meta[PGID]:-}"
  sid="${child_meta[SID]:-}"
  cwd="${child_meta[CWD]:-}"
  [[ "$pid" =~ ^[0-9]+$ && "$start" =~ ^[0-9]+$ && "$pgid" == "$pid" && "$sid" == "$pid" ]] || return 1
  kill -0 "$pid" 2>/dev/null || return 1
  # `kill -0` succeeds for an exited-but-unreaped child. A zombie cannot
  # service the runtime audit target, so reject it before treating PID/start
  # metadata as evidence of a live owned process.
  child_is_zombie "$pid" && return 1
  now_start="$(pid_start_ticks "$pid")" || return 1
  [[ "$now_start" == "$start" ]] || return 1
  [[ "$(ps -o pgid= -p "$pid" | tr -d ' ')" == "$pgid" && "$(ps -o sid= -p "$pid" | tr -d ' ')" == "$sid" ]] || return 1
  [[ "$(readlink -f "/proc/$pid/cwd")" == "$RUN_DIR" && "$cwd" == "$RUN_DIR" ]] || return 1
  args="$(ps -o args= -p "$pid")"
  [[ "$args" == *"$fragment"* ]]
}

local_docker_home() {
  local home
  home="$(getent passwd "$(id -u)" 2>/dev/null | awk -F: 'NR == 1 { print $6 }')"
  [[ -n "$home" && -d "$home" && ! -L "$home" ]] || return 1
  [[ "$(stat -c '%u' -- "$home" 2>/dev/null)" == "$(id -u)" ]] || return 1
  printf '%s' "$home"
}

docker_local() {
  local home
  home="$(local_docker_home)" || return 1
  env -i "PATH=$SAFE_PATH" "HOME=$home" "DOCKER_HOST=$LOCAL_DOCKER_HOST" \
    docker --context default "$@"
}

assert_local_docker_target() {
  local selected endpoint
  selected="$(docker_local context show)" || return 1
  [[ "$selected" == default ]] || return 1
  endpoint="$(docker_local context inspect default --format '{{.Endpoints.docker.Host}}')" || return 1
  [[ "$endpoint" == "$LOCAL_DOCKER_HOST" ]]
}

loopback_url_port() {
  env -i "PATH=$SAFE_PATH" python3 -c '
from urllib.parse import urlsplit
value = urlsplit(__import__("sys").argv[1])
print(value.port)
' "$1"
}

validate_owned_mock_service() {
  local expected_port id labels ports
  local -a ids=()
  assert_local_docker_target || return 1
  mapfile -t ids < <(docker_local ps -q \
    --filter "label=com.docker.compose.project=$COMPOSE_PROJECT" \
    --filter 'label=com.foggy.navigator.int001.managed=true' \
    --filter "label=com.foggy.navigator.int001.run-id=$RUN_ID" \
    --filter 'label=com.docker.compose.service=mock-llm')
  [[ "${#ids[@]}" == 1 && -n "${ids[0]}" ]] || return 1
  id="${ids[0]}"
  labels="$(docker_local inspect --format '{{index .Config.Labels "com.docker.compose.project"}}|{{index .Config.Labels "com.foggy.navigator.int001.managed"}}|{{index .Config.Labels "com.foggy.navigator.int001.run-id"}}|{{index .Config.Labels "com.docker.compose.service"}}|{{.Config.Image}}' "$id")" || return 1
  [[ "$labels" == "$COMPOSE_PROJECT|true|$RUN_ID|mock-llm|foggy/int001-mock-llm:$RUN_ID" ]] || return 1
  expected_port="$(loopback_url_port "$MOCK_URL")" || return 1
  ports="$(docker_local inspect --format '{{json .NetworkSettings.Ports}}' "$id")" || return 1
  env -i "PATH=$SAFE_PATH" python3 -c '
import json
import sys
try:
    ports = json.loads(sys.stdin.read())
    bindings = ports.get("8200/tcp")
    if not isinstance(bindings, list) or len(bindings) != 1:
        raise ValueError()
    binding = bindings[0]
    if binding.get("HostIp") != "127.0.0.1" or binding.get("HostPort") != sys.argv[1]:
        raise ValueError()
except Exception:
    raise SystemExit(2)
' "$expected_port" <<< "$ports" >/dev/null
}

validate_owned_runtime_target() {
  local initial_query_ingress_count
  OWNERSHIP_FAILURE_TARGET='none'
  EXECUTION_FAILURE_TARGET='none'
  validate_owned_child launcher launcher-1.0.0-SNAPSHOT.jar || {
    OWNERSHIP_FAILURE_TARGET='launcher'
    return 1
  }
  validate_owned_child biz-worker langgraph_biz_worker.main:app || {
    OWNERSHIP_FAILURE_TARGET='biz-worker'
    return 1
  }
  validate_owned_child biz-ingress-proxy biz_ingress_proxy.py || {
    OWNERSHIP_FAILURE_TARGET='biz-ingress-proxy'
    return 1
  }
  validate_owned_child directory-facade directory_facade.py || {
    OWNERSHIP_FAILURE_TARGET='directory-facade'
    return 1
  }
  # Counter and lock verification is intentionally inside the shared-lock
  # reader. A lock-free `assert_private_file(counter)` can observe the old
  # inode while the proxy performs its safe atomic replacement.
  initial_query_ingress_count="$(read_query_ingress_count)" || {
    OWNERSHIP_FAILURE_TARGET='ingress-counter'
    return 1
  }
  [[ "$initial_query_ingress_count" == 0 ]] || {
    EXECUTION_FAILURE_TARGET='unexpected-pre-audit-ingress'
    return 2
  }
  validate_owned_mock_service || {
    OWNERSHIP_FAILURE_TARGET='mock-llm'
    return 1
  }
}

require_child_key() {
  [[ -n "${CHILD_ENV[$1]:-}" ]]
}

validate_runtime_child_profile() {
  local profile="$RUN_DIR/$PRIVATE_DIRECTORY_NAME/$PRIVATE_CHILD_PROFILE_NAME"
  local key
  # These are reject-only legacy checks; the audit never reads a root-level
  # bootstrap/admin/control carrier or manifest.  All runtime carriers and
  # evidence are consumed from the fixed private directory only.
  [[ ! -e "$RUN_DIR/$BOOTSTRAP_TARGET_PROFILE_NAME" && ! -L "$RUN_DIR/$BOOTSTRAP_TARGET_PROFILE_NAME" ]] || return 1
  [[ ! -e "$RUN_DIR/$RUN_MANIFEST_NAME" && ! -L "$RUN_DIR/$RUN_MANIFEST_NAME" ]] || return 1
  [[ ! -e "$RUN_DIR/$RUNTIME_AUDIT_REPORT_NAME" && ! -L "$RUN_DIR/$RUNTIME_AUDIT_REPORT_NAME" ]] || return 1
  assert_private_dir "$RUN_DIR/$PRIVATE_DIRECTORY_NAME" || return 1
  assert_private_dir "$RUN_DIR/home" || return 1
  read_runtime_child_env "$profile" CHILD_ENV || return 1
  for key in \
    INT001_SYNTHETIC_UPSTREAM_HARNESS INT001_RUN_ID INT001_NAVI_BASE_URL \
    INT001_A_TENANT_ID INT001_A_CLIENT_APP_ID INT001_A_CLIENT_APP_KEY INT001_A_CLIENT_APP_SECRET \
    INT001_A_AGENT_ID INT001_A_UPSTREAM_USER_ID INT001_A_MODEL_CONFIG_ID INT001_A_DIRECTORY_ID \
    INT001_B_AGENT_ID INT001_C_AGENT_ID; do
    require_child_key "$key" || return 1
  done
  [[ "${#CHILD_ENV[@]}" == "$RUNTIME_CHILD_KEY_COUNT" ]] || return 1
  [[ "${CHILD_ENV[INT001_SYNTHETIC_UPSTREAM_HARNESS]}" == true ]] || return 1
  [[ "${CHILD_ENV[INT001_RUN_ID]}" == "$RUN_ID" ]] || return 1
  validate_loopback_url "${CHILD_ENV[INT001_NAVI_BASE_URL]}" || return 1
  [[ "${CHILD_ENV[INT001_NAVI_BASE_URL]}" == "$NAVIGATOR_URL" ]] || return 1
  [[ "${CHILD_ENV[INT001_A_AGENT_ID]}" != "${CHILD_ENV[INT001_B_AGENT_ID]}" ]] || return 1
  [[ "${CHILD_ENV[INT001_A_AGENT_ID]}" != "${CHILD_ENV[INT001_C_AGENT_ID]}" ]] || return 1
  [[ "${CHILD_ENV[INT001_B_AGENT_ID]}" != "${CHILD_ENV[INT001_C_AGENT_ID]}" ]] || return 1
  PRIVATE_DIR="$RUN_DIR/$PRIVATE_DIRECTORY_NAME"
  RUNTIME_CHILD_PROFILE="$profile"
  RUNTIME_CLI_PROFILE="$PRIVATE_DIR/$RUNTIME_CLI_PROFILE_NAME"
  REPORT_FILE="$PRIVATE_DIR/$RUNTIME_AUDIT_REPORT_NAME"
  SUMMARY_FILE="$RUN_DIR/$RUNTIME_AUDIT_SUMMARY_NAME"
  [[ ! -e "$RUNTIME_CLI_PROFILE" && ! -L "$RUNTIME_CLI_PROFILE" ]] || return 1
  [[ ! -e "$REPORT_FILE" && ! -L "$REPORT_FILE" ]] || return 1
  [[ ! -e "$SUMMARY_FILE" && ! -L "$SUMMARY_FILE" ]] || return 1
  REPORT_ENABLED=1
  SUMMARY_ENABLED=1
}

create_private_file() {
  local target="$1" temporary
  [[ ! -e "$target" && ! -L "$target" ]] || return 1
  temporary="$(mktemp "$PRIVATE_DIR/.runtime-audit.XXXXXX")" || return 1
  chmod 600 "$temporary" || {
    rm -f -- "$temporary"
    return 1
  }
  mv -- "$temporary" "$target" || {
    rm -f -- "$temporary"
    return 1
  }
  assert_private_file "$target"
}

build_cli_classpath() {
  local jar base
  [[ -f "$SOURCE_SDK_JAR" && ! -L "$SOURCE_SDK_JAR" ]] || return 1
  [[ -d "$SDK_LIB_DIR" && ! -L "$SDK_LIB_DIR" ]] || return 1
  CLI_CLASSPATH="$SOURCE_SDK_JAR"
  shopt -s nullglob
  for jar in "$SDK_LIB_DIR"/*.jar; do
    base="${jar##*/}"
    [[ "$base" == 'navigator-open-sdk-1.0.18.jar' ]] && continue
    [[ -f "$jar" && ! -L "$jar" ]] || {
      shopt -u nullglob
      return 1
    }
    CLI_CLASSPATH+=":$jar"
  done
  shopt -u nullglob
  [[ "$CLI_CLASSPATH" != "$SOURCE_SDK_JAR" ]]
}

resolve_trusted_cli_java() {
  local resolved version_line version_value major
  [[ -x "$TRUSTED_JAVA_LINK" ]] || return 1
  resolved="$(/usr/bin/readlink -f -- "$TRUSTED_JAVA_LINK")" || return 1
  [[ -f "$resolved" && ! -L "$resolved" && -x "$resolved" ]] || return 1
  version_line="$("$resolved" -version 2>&1 | /usr/bin/sed -n '1p')" || return 1
  case "$version_line" in
    *\"*)
      version_value="${version_line#*\"}"
      version_value="${version_value%%\"*}"
      ;;
    *) return 1 ;;
  esac
  [[ "$version_value" =~ ^([0-9]+)(\.[0-9]+)*([+_-].*)?$ ]] || return 1
  major="${BASH_REMATCH[1]}"
  (( major >= 17 )) || return 1
  CLI_JAVA="$resolved"
}

resolve_node22() {
  local user_home home_real candidate candidate_real version
  local -a candidates=()
  user_home="$(getent passwd "$(id -u)" 2>/dev/null | awk -F: 'NR == 1 { print $6 }')"
  [[ -n "$user_home" && -d "$user_home" && ! -L "$user_home" ]] || return 1
  [[ "$(stat -c '%u' -- "$user_home" 2>/dev/null)" == "$(id -u)" ]] || return 1
  home_real="$(realpath -e -- "$user_home" 2>/dev/null)" || return 1

  # Do not source nvm or inherit its shell variables.  A disposable audit
  # requires exactly one current-user Node 22 binary, and invokes it through
  # env -i below.  Multiple candidates are ambiguous rather than silently
  # selecting an arbitrary developer installation.
  shopt -s nullglob
  candidates=("$home_real"/.nvm/versions/node/v22.*/bin/node)
  shopt -u nullglob
  [[ "${#candidates[@]}" == 1 ]] || return 1
  candidate="${candidates[0]}"
  [[ -f "$candidate" && -x "$candidate" && ! -L "$candidate" ]] || return 1
  candidate_real="$(realpath -e -- "$candidate" 2>/dev/null)" || return 1
  [[ "$candidate_real" == "$home_real"/.nvm/versions/node/v22.*/bin/node ]] || return 1
  version="$(env -i "PATH=${candidate_real%/*}:$SAFE_PATH" "$candidate_real" --version 2>/dev/null)" || return 1
  [[ "$version" == v22.* ]] || return 1
  NODE_BIN="$candidate_real"
  NODE_BIN_DIR="${candidate_real%/*}"
}

assert_tooling() {
  resolve_node22 || return 1
  [[ -x "$NODE_BIN" && -f "$REPO_ROOT/business-agent-module/integration-tests/node_modules/vitest/vitest.mjs" ]] || return 1
  [[ "$(env -i "PATH=$NODE_BIN_DIR:$SAFE_PATH" "$NODE_BIN" --version 2>/dev/null)" == v22.* ]] || return 1
  resolve_trusted_cli_java || return 1
  command -v curl >/dev/null 2>&1 || return 1
  command -v python3 >/dev/null 2>&1 || return 1
  command -v git >/dev/null 2>&1 || return 1
  git -C "$REPO_ROOT" check-ignore -q "temp/test-artifacts/INT-001/.runtime-audit-ignore-probe" >/dev/null 2>&1 || return 1
  build_cli_classpath
}

write_runtime_cli_profile() {
  create_private_file "$RUNTIME_CLI_PROFILE" || return 1
  {
    printf 'NAVI_BASE_URL=%s\n' "${CHILD_ENV[INT001_NAVI_BASE_URL]}"
    printf 'NAVI_TENANT_ID=%s\n' "${CHILD_ENV[INT001_A_TENANT_ID]}"
    printf 'NAVI_CLIENT_APP_ID=%s\n' "${CHILD_ENV[INT001_A_CLIENT_APP_ID]}"
    printf 'NAVI_CLIENT_APP_KEY=%s\n' "${CHILD_ENV[INT001_A_CLIENT_APP_KEY]}"
    printf 'NAVI_CLIENT_APP_SECRET=%s\n' "${CHILD_ENV[INT001_A_CLIENT_APP_SECRET]}"
    printf 'NAVI_AGENT_CODE=%s\n' "${CHILD_ENV[INT001_A_AGENT_ID]}"
    printf 'NAVI_UPSTREAM_USER_ID=%s\n' "${CHILD_ENV[INT001_A_UPSTREAM_USER_ID]}"
    printf 'NAVI_MODEL_CONFIG_ID=%s\n' "${CHILD_ENV[INT001_A_MODEL_CONFIG_ID]}"
    printf 'NAVI_DIRECTORY_ID=%s\n' "${CHILD_ENV[INT001_A_DIRECTORY_ID]}"
  } > "$RUNTIME_CLI_PROFILE" || return 1
  chmod 600 "$RUNTIME_CLI_PROFILE" || return 1
  assert_private_file "$RUNTIME_CLI_PROFILE"
}

runtime_cli_key_allowed() {
  case "$1" in
    NAVI_BASE_URL|NAVI_TENANT_ID|NAVI_CLIENT_APP_ID|NAVI_CLIENT_APP_KEY|NAVI_CLIENT_APP_SECRET|NAVI_CLIENT_APP_ACCESS_TOKEN|NAVI_AGENT_CODE|NAVI_UPSTREAM_USER_ID|NAVI_MODEL_CONFIG_ID|NAVI_DIRECTORY_ID)
      return 0
      ;;
    *)
      return 1
      ;;
  esac
}

validate_runtime_cli_profile() {
  local expect_secret="$1" raw key value
  local -A seen=()
  assert_private_file "$RUNTIME_CLI_PROFILE" || return 1
  while IFS= read -r raw || [[ -n "$raw" ]]; do
    [[ -n "$raw" && "$raw" != \#* && "$raw" == *=* ]] || return 1
    key="${raw%%=*}"
    value="${raw#*=}"
    runtime_cli_key_allowed "$key" || return 1
    [[ -n "$value" && "$value" != *$'\r'* && "$value" != *$'\n'* ]] || return 1
    [[ -z "${seen[$key]+present}" ]] || return 1
    seen["$key"]=1
  done < "$RUNTIME_CLI_PROFILE"
  local required
  for required in NAVI_BASE_URL NAVI_TENANT_ID NAVI_CLIENT_APP_ID NAVI_CLIENT_APP_KEY NAVI_AGENT_CODE NAVI_UPSTREAM_USER_ID NAVI_MODEL_CONFIG_ID NAVI_DIRECTORY_ID NAVI_CLIENT_APP_ACCESS_TOKEN; do
    [[ -n "${seen[$required]+present}" ]] || return 1
  done
  if [[ "$expect_secret" == true ]]; then
    [[ -n "${seen[NAVI_CLIENT_APP_SECRET]+present}" ]] || return 1
  else
    [[ -z "${seen[NAVI_CLIENT_APP_SECRET]+present}" ]] || return 1
  fi
}

strip_runtime_cli_secret() {
  local temporary raw
  temporary="$(mktemp "$PRIVATE_DIR/.runtime-cli-redacted.XXXXXX")" || return 1
  chmod 600 "$temporary" || {
    rm -f -- "$temporary"
    return 1
  }
  while IFS= read -r raw || [[ -n "$raw" ]]; do
    [[ "$raw" == NAVI_CLIENT_APP_SECRET=* ]] && continue
    printf '%s\n' "$raw"
  done < "$RUNTIME_CLI_PROFILE" > "$temporary" || {
    rm -f -- "$temporary"
    return 1
  }
  mv -f -- "$temporary" "$RUNTIME_CLI_PROFILE" || {
    rm -f -- "$temporary"
    return 1
  }
  chmod 600 "$RUNTIME_CLI_PROFILE" || return 1
  validate_runtime_cli_profile false
}

run_source_cli() {
  local log_file="$1"
  shift
  (
    cd "$REPO_ROOT"
    exec env -i "PATH=$SAFE_PATH" "HOME=$RUN_DIR/home" \
      "$CLI_JAVA" -cp "$CLI_CLASSPATH" com.foggy.navigator.sdk.cli.UpstreamCli "$@"
  ) > "$log_file" 2>&1
}

run_runtime_cli_audit() {
  local runtime_token_log="$PRIVATE_DIR/runtime-token-cli.log"
  local readiness_log="$PRIVATE_DIR/verify-agent-readiness-cli.log"
  local owner_smoke_log="$PRIVATE_DIR/owner-smoke-cli.log"
  create_private_file "$runtime_token_log" || return 1
  create_private_file "$readiness_log" || return 1
  create_private_file "$owner_smoke_log" || return 1
  write_runtime_cli_profile || return 1
  if ! run_source_cli "$runtime_token_log" runtime-token --write-profile --profile "$RUNTIME_CLI_PROFILE"; then
    RUNTIME_TOKEN_STATUS='FAIL'
    return 1
  fi
  grep -Fqx 'runtime-token ok' "$runtime_token_log" || {
    RUNTIME_TOKEN_STATUS='FAIL'
    return 1
  }
  assert_private_file "$RUNTIME_CLI_PROFILE" || {
    RUNTIME_TOKEN_STATUS='FAIL'
    return 1
  }
  validate_runtime_cli_profile true || {
    RUNTIME_TOKEN_STATUS='FAIL'
    return 1
  }
  strip_runtime_cli_secret || {
    RUNTIME_TOKEN_STATUS='FAIL'
    return 1
  }
  RUNTIME_TOKEN_STATUS='PASS'
  assert_no_pre_execution_ingress runtime-token || return $?
  if ! run_source_cli "$readiness_log" verify-agent-readiness --profile "$RUNTIME_CLI_PROFILE" \
    --agent-code "${CHILD_ENV[INT001_A_AGENT_ID]}" \
    --upstream-user-id "${CHILD_ENV[INT001_A_UPSTREAM_USER_ID]}" \
    --model-config-id "${CHILD_ENV[INT001_A_MODEL_CONFIG_ID]}" \
    --directory-id "${CHILD_ENV[INT001_A_DIRECTORY_ID]}"; then
    VERIFY_AGENT_READINESS_STATUS='FAIL'
    return 1
  fi
  grep -Fqx 'verify-agent-readiness OK' "$readiness_log" || {
    VERIFY_AGENT_READINESS_STATUS='FAIL'
    return 1
  }
  VERIFY_AGENT_READINESS_STATUS='PASS'
  assert_no_pre_execution_ingress readiness || return $?
  if ! run_source_cli "$owner_smoke_log" owner-smoke --profile "$RUNTIME_CLI_PROFILE" \
    --agent-code "${CHILD_ENV[INT001_A_AGENT_ID]}" \
    --upstream-user-id "${CHILD_ENV[INT001_A_UPSTREAM_USER_ID]}" \
    --model-config-id "${CHILD_ENV[INT001_A_MODEL_CONFIG_ID]}" \
    --directory-id "${CHILD_ENV[INT001_A_DIRECTORY_ID]}"; then
    OWNER_SMOKE_STATUS='FAIL'
    return 1
  fi
  grep -Fqx 'owner-smoke profileGitIgnored=true' "$owner_smoke_log" || {
    OWNER_SMOKE_STATUS='FAIL'
    return 1
  }
  grep -Fqx 'owner-smoke ready' "$owner_smoke_log" || {
    OWNER_SMOKE_STATUS='FAIL'
    return 1
  }
  OWNER_SMOKE_STATUS='PASS'
  assert_no_pre_execution_ingress owner-smoke || return $?
}

assert_no_pre_execution_ingress() {
  local stage="$1" count target
  case "$stage" in
    runtime-token) target='unexpected-runtime-token-ingress' ;;
    readiness) target='unexpected-readiness-ingress' ;;
    owner-smoke) target='unexpected-owner-smoke-ingress' ;;
    pre-positive) target='unexpected-pre-positive-ingress' ;;
    *) return 1 ;;
  esac
  count="$(read_query_ingress_count)" || {
    OWNERSHIP_FAILURE_TARGET='ingress-counter'
    return 1
  }
  [[ "$count" == 0 ]] || {
    EXECUTION_FAILURE_TARGET="$target"
    return 2
  }
}

probe_trace() {
  printf 'int001-%s-%s' "$1" "$RUN_ID"
}

probe_cursor() {
  printf 'next:%s:001' "$(probe_trace "$1")"
}

register_mock_script() {
  local probe="$1" trace cursor content register_log payload
  trace="$(probe_trace "$probe")"
  cursor="$(probe_cursor "$probe")"
  if [[ "$probe" == positive ]]; then
    content="INT001_STATIC_NO_TOOL_$RUN_ID"
  else
    content="INT001_UNEXPECTED_MODEL_SUBMISSION_${probe}_$RUN_ID"
  fi
  register_log="$PRIVATE_DIR/mock-register-$probe.log"
  create_private_file "$register_log" || return 1
  payload="{\"traceId\":\"$trace\",\"scenarioId\":\"int001-$probe\",\"expiresInSeconds\":600,\"turns\":[{\"cursor\":\"$cursor\",\"response\":{\"content\":\"$content\",\"tool_calls\":[]}}]}"
  if ! env -i "PATH=$SAFE_PATH" "HOME=$RUN_DIR/home" \
    curl --noproxy '*' --silent --show-error --fail --max-time 15 \
    --header 'Content-Type: application/json' --data "$payload" \
    "$MOCK_URL/__e2e/scripts" > "$register_log" 2>&1; then
    return 1
  fi
  REGISTERED_TRACES+=("$trace")
}

build_runtime_child_pairs() {
  RUNTIME_CHILD_PAIRS=(
    'INT001_SYNTHETIC_UPSTREAM_HARNESS=true'
    "INT001_RUN_ID=${CHILD_ENV[INT001_RUN_ID]}"
    "INT001_NAVI_BASE_URL=${CHILD_ENV[INT001_NAVI_BASE_URL]}"
    "INT001_A_TENANT_ID=${CHILD_ENV[INT001_A_TENANT_ID]}"
    "INT001_A_CLIENT_APP_ID=${CHILD_ENV[INT001_A_CLIENT_APP_ID]}"
    "INT001_A_CLIENT_APP_KEY=${CHILD_ENV[INT001_A_CLIENT_APP_KEY]}"
    "INT001_A_CLIENT_APP_SECRET=${CHILD_ENV[INT001_A_CLIENT_APP_SECRET]}"
    "INT001_A_AGENT_ID=${CHILD_ENV[INT001_A_AGENT_ID]}"
    "INT001_A_UPSTREAM_USER_ID=${CHILD_ENV[INT001_A_UPSTREAM_USER_ID]}"
    "INT001_A_MODEL_CONFIG_ID=${CHILD_ENV[INT001_A_MODEL_CONFIG_ID]}"
    "INT001_A_DIRECTORY_ID=${CHILD_ENV[INT001_A_DIRECTORY_ID]}"
    "INT001_B_AGENT_ID=${CHILD_ENV[INT001_B_AGENT_ID]}"
    "INT001_C_AGENT_ID=${CHILD_ENV[INT001_C_AGENT_ID]}"
  )
}

record_child_result_protocol_failure() {
  # This is intentionally the only fallback for a missing, duplicate, unsafe,
  # or exit-status-inconsistent child result. Do not derive a class from the
  # child log; it may contain request/response content or credential-adjacent
  # test diagnostics that must remain private.
  CHILD_STATUS='FAIL'
  CHILD_PHASE='RUNNER'
  CHILD_FAILURE_CLASS='RESULT_PROTOCOL'
}

record_child_result_failure() {
  # Callers below use hard-coded, reviewed enum literals only. Keeping the
  # assignment in one place avoids accidentally copying a parsed suffix into
  # the root receipt.
  CHILD_STATUS='FAIL'
  CHILD_PHASE="$1"
  CHILD_FAILURE_CLASS="$2"
}

parse_runtime_child_result() {
  local child_log="$1" probe="$2" line='' candidate='' suffix='' count=0
  case "$probe" in
    positive|deny-control|deny-admin|deny-same-client-app|deny-cross-tenant|deny-model-grant|deny-directory|deny-upstream-user) ;;
    *) return 1 ;;
  esac
  assert_private_file "$child_log" || return 1

  # Scan only the fixed result marker. We never print, serialize, or otherwise
  # propagate an unmatched line from the private Vitest log.
  while IFS= read -r candidate || [[ -n "$candidate" ]]; do
    [[ "$candidate" == 'INT001_CHILD_RESULT '* ]] || continue
    ((count += 1))
    [[ "$count" == 1 ]] || return 1
    line="$candidate"
  done < "$child_log"
  [[ "$count" == 1 ]] || return 1

  if [[ "$line" == "INT001_CHILD_RESULT runId=$RUN_ID probe=$probe status=PASS phase=COMPLETE failureClass=NONE" ]]; then
    CHILD_STATUS='PASS'
    CHILD_PHASE='COMPLETE'
    CHILD_FAILURE_CLASS='NONE'
    return 0
  fi

  # The only parsing permitted for a failed child is removal of a trusted,
  # current-run prefix followed by a finite case table. No identifier or
  # arbitrary child text is assigned to a field that reaches the root receipt.
  local prefix="INT001_CHILD_RESULT runId=$RUN_ID probe=$probe status=FAIL phase="
  [[ "$line" == "$prefix"* ]] || return 1
  suffix="${line#"$prefix"}"
  case "$probe:$suffix" in
    positive:RUNTIME_TOKEN\ failureClass=RUNTIME_TOKEN_EXCHANGE)
      record_child_result_failure RUNTIME_TOKEN RUNTIME_TOKEN_EXCHANGE
      ;;
    positive:POSITIVE_READINESS\ failureClass=READINESS_CONTRACT)
      record_child_result_failure POSITIVE_READINESS READINESS_CONTRACT
      ;;
    positive:POSITIVE_ASK\ failureClass=ASK_RX_REJECTED)
      record_child_result_failure POSITIVE_ASK ASK_RX_REJECTED
      ;;
    positive:POSITIVE_ASK\ failureClass=ASK_TASK_ID_MISSING)
      record_child_result_failure POSITIVE_ASK ASK_TASK_ID_MISSING
      ;;
    positive:POSITIVE_ASK\ failureClass=ASK_SUBMISSION_ERROR)
      record_child_result_failure POSITIVE_ASK ASK_SUBMISSION_ERROR
      ;;
    positive:POSITIVE_TASK_TERMINAL\ failureClass=TASK_TERMINAL)
      record_child_result_failure POSITIVE_TASK_TERMINAL TASK_TERMINAL
      ;;
    positive:POSITIVE_DIAGNOSTICS\ failureClass=DIAGNOSTICS_CONTRACT)
      record_child_result_failure POSITIVE_DIAGNOSTICS DIAGNOSTICS_CONTRACT
      ;;
    deny-control:RUNTIME_TOKEN\ failureClass=RUNTIME_TOKEN_EXCHANGE)
      record_child_result_failure RUNTIME_TOKEN RUNTIME_TOKEN_EXCHANGE
      ;;
    deny-control:DENY_CONTROL\ failureClass=DENY_CONTRACT)
      record_child_result_failure DENY_CONTROL DENY_CONTRACT
      ;;
    deny-admin:RUNTIME_TOKEN\ failureClass=RUNTIME_TOKEN_EXCHANGE)
      record_child_result_failure RUNTIME_TOKEN RUNTIME_TOKEN_EXCHANGE
      ;;
    deny-admin:DENY_ADMIN\ failureClass=DENY_CONTRACT)
      record_child_result_failure DENY_ADMIN DENY_CONTRACT
      ;;
    deny-same-client-app:RUNTIME_TOKEN\ failureClass=RUNTIME_TOKEN_EXCHANGE)
      record_child_result_failure RUNTIME_TOKEN RUNTIME_TOKEN_EXCHANGE
      ;;
    deny-same-client-app:DENY_SAME_CLIENT_APP\ failureClass=DENY_CONTRACT)
      record_child_result_failure DENY_SAME_CLIENT_APP DENY_CONTRACT
      ;;
    deny-cross-tenant:RUNTIME_TOKEN\ failureClass=RUNTIME_TOKEN_EXCHANGE)
      record_child_result_failure RUNTIME_TOKEN RUNTIME_TOKEN_EXCHANGE
      ;;
    deny-cross-tenant:DENY_CROSS_TENANT\ failureClass=DENY_CONTRACT)
      record_child_result_failure DENY_CROSS_TENANT DENY_CONTRACT
      ;;
    deny-model-grant:RUNTIME_TOKEN\ failureClass=RUNTIME_TOKEN_EXCHANGE)
      record_child_result_failure RUNTIME_TOKEN RUNTIME_TOKEN_EXCHANGE
      ;;
    deny-model-grant:DENY_MODEL_GRANT\ failureClass=DENY_CONTRACT)
      record_child_result_failure DENY_MODEL_GRANT DENY_CONTRACT
      ;;
    deny-directory:RUNTIME_TOKEN\ failureClass=RUNTIME_TOKEN_EXCHANGE)
      record_child_result_failure RUNTIME_TOKEN RUNTIME_TOKEN_EXCHANGE
      ;;
    deny-directory:DENY_DIRECTORY\ failureClass=DENY_CONTRACT)
      record_child_result_failure DENY_DIRECTORY DENY_CONTRACT
      ;;
    deny-upstream-user:RUNTIME_TOKEN\ failureClass=RUNTIME_TOKEN_EXCHANGE)
      record_child_result_failure RUNTIME_TOKEN RUNTIME_TOKEN_EXCHANGE
      ;;
    deny-upstream-user:DENY_UPSTREAM_USER\ failureClass=DENY_CONTRACT)
      record_child_result_failure DENY_UPSTREAM_USER DENY_CONTRACT
      ;;
    *)
      return 1
      ;;
  esac
}

run_runtime_child() {
  local probe="$1" child_log child_exit=0
  case "$probe" in
    positive|deny-control|deny-admin|deny-same-client-app|deny-cross-tenant|deny-model-grant|deny-directory|deny-upstream-user) ;;
    *) return 1 ;;
  esac
  CHILD_STATUS='NOT_RUN'
  CHILD_PHASE='NOT_RUN'
  CHILD_FAILURE_CLASS='NONE'
  child_log="$PRIVATE_DIR/runtime-child-$probe-vitest.log"
  create_private_file "$child_log" || return 1
  build_runtime_child_pairs
  if (
    cd "$REPO_ROOT/business-agent-module/integration-tests"
    exec env -i \
      "PATH=$NODE_BIN_DIR:$SAFE_PATH" \
      "HOME=$RUN_DIR/home" \
      "INT001_RUNTIME_PROBE=$probe" \
      "${RUNTIME_CHILD_PAIRS[@]}" \
      "$NODE_BIN" ./node_modules/vitest/vitest.mjs run \
        --config vitest.synthetic.config.ts \
        tests/05-synthetic-upstream-runtime-harness.test.ts \
        --reporter=verbose --no-file-parallelism
  ) > "$child_log" 2>&1; then
    :
  else
    child_exit=1
  fi
  if ! parse_runtime_child_result "$child_log" "$probe"; then
    record_child_result_protocol_failure
    return 1
  fi

  # A PASS marker is trustworthy only when Vitest exited cleanly; a FAIL marker
  # is trustworthy only when Vitest failed. Any mismatch is a protocol defect,
  # not a reason to expose the child runner's diagnostic text.
  if [[ "$child_exit" == 0 && "$CHILD_STATUS" == PASS ]]; then
    :
  elif [[ "$child_exit" == 1 && "$CHILD_STATUS" == FAIL ]]; then
    return 1
  else
    record_child_result_protocol_failure
    return 1
  fi

  if [[ "$probe" == positive ]]; then
    CHILD_POSITIVE_TASK_CREATED='true'
  else
    CHILD_DENIED_TASK_CREATED='false'
    if [[ -n "$CHILD_DENY_CASES" ]]; then
      CHILD_DENY_CASES+=",$probe"
    else
      CHILD_DENY_CASES="$probe"
    fi
  fi
}

validate_mock_debug_records() {
  local probe="$1" expected_count="$2" trace cursor debug_log raw
  trace="$(probe_trace "$probe")"
  cursor="$(probe_cursor "$probe")"
  debug_log="$PRIVATE_DIR/mock-debug-$probe.log"
  create_private_file "$debug_log" || return 1
  if ! raw="$(env -i "PATH=$SAFE_PATH" "HOME=$RUN_DIR/home" \
    curl --noproxy '*' --silent --show-error --fail --max-time 15 \
    --get --data-urlencode "traceId=$trace" "$MOCK_URL/__debug/requests" 2>> "$debug_log")"; then
    return 1
  fi
  # Raw debug records include request bodies.  They remain only in this shell
  # variable and are parsed without an intermediate file or terminal output.
  env -i "PATH=$SAFE_PATH" python3 -c '
import json
import sys
try:
    trace, cursor, expected = sys.argv[1], sys.argv[2], int(sys.argv[3])
    records = json.load(sys.stdin)
    if not isinstance(records, list) or len(records) != expected:
        raise ValueError()
    if expected == 1:
        record = records[0]
        if not isinstance(record, dict):
            raise ValueError()
        if record.get("traceId") != trace or record.get("cursor") != cursor or record.get("matched") is not True:
            raise ValueError()
        response = record.get("responseSummary")
        if not isinstance(response, dict) or response.get("toolCalls") != []:
            raise ValueError()
except Exception:
    raise SystemExit(2)
' "$trace" "$cursor" "$expected_count" <<< "$raw" >/dev/null 2>> "$debug_log"
}

run_positive_probe() {
  local before after delta
  register_mock_script positive || return 1
  assert_no_pre_execution_ingress pre-positive || return $?
  before="$(read_query_ingress_count)" || return 1
  # CLI runtime-token/readiness/owner-smoke ran before this point. A nonzero
  # counter would prove that a supposedly non-dispatch step already contacted
  # the Biz Worker, so do not normalize it away as a later positive success.
  [[ "$before" == 0 ]] || return 1
  run_runtime_child positive || return 1
  after="$(read_query_ingress_count)" || return 1
  delta=$((after - before))
  [[ "$delta" == 1 ]] || return 1
  validate_mock_debug_records positive 1 || return 1
  POSITIVE_MODEL_SUBMISSION_COUNT=1
  POSITIVE_QUERY_INGRESS_DELTA="$delta"
}

run_deny_probe() {
  local probe="$1" before after delta
  register_mock_script "$probe" || return 1
  before="$(read_query_ingress_count)" || return 1
  run_runtime_child "$probe" || return 1
  after="$(read_query_ingress_count)" || return 1
  delta=$((after - before))
  # Check the per-probe delta instead of accepting a global counter left by
  # the positive ask. This detects a deny path that accidentally dispatches
  # and then gets hidden by a later cumulative assertion.
  [[ "$delta" == 0 ]] || return 1
  validate_mock_debug_records "$probe" 0 || return 1
  DENY_MODEL_SUBMISSION_COUNTS["$probe"]=0
  DENY_QUERY_INGRESS_DELTAS["$probe"]="$delta"
}

run_execution_probes() {
  local probe
  # Mock records prove deterministic model submission; the run-owned proxy
  # proves the actual request reached the real, run-owned Biz Worker.
  run_positive_probe || return 1
  for probe in "${DENY_PROBES[@]}"; do
    run_deny_probe "$probe" || return 1
  done
}

print_success_summary() {
  local probe
  printf 'INT001_RUNTIME_AUDIT runId=%s probe=positive modelSubmissionCount=%s queryIngressDelta=%s status=PASS\n' \
    "$RUN_ID" "$POSITIVE_MODEL_SUBMISSION_COUNT" "$POSITIVE_QUERY_INGRESS_DELTA"
  for probe in "${DENY_PROBES[@]}"; do
    printf 'INT001_RUNTIME_AUDIT runId=%s probe=%s modelSubmissionCount=%s queryIngressDelta=%s status=PASS\n' \
      "$RUN_ID" "$probe" "${DENY_MODEL_SUBMISSION_COUNTS[$probe]}" "${DENY_QUERY_INGRESS_DELTAS[$probe]}"
  done
  terminal_status PASS
}

main() {
  local runtime_target_status runtime_cli_status execution_probe_status
  parse_args "$@" || {
    usage >&2
    terminal_status FAIL
    return 2
  }
  resolve_and_validate_run_dir || fail input
  validate_run_manifest || fail input
  validate_runtime_child_profile || fail input
  if validate_owned_runtime_target; then
    :
  else
    runtime_target_status=$?
    case "$runtime_target_status" in
      1) fail ownership ;;
      2) fail execution_evidence ;;
      *) fail ownership ;;
    esac
  fi
  assert_tooling || fail tooling
  if run_runtime_cli_audit; then
    :
  else
    runtime_cli_status=$?
    case "$runtime_cli_status" in
      1) fail runtime_cli ;;
      2) fail execution_evidence ;;
      *) fail runtime_cli ;;
    esac
  fi
  if run_execution_probes; then
    :
  else
    execution_probe_status=$?
    case "$execution_probe_status" in
      1|2) fail execution_evidence ;;
      *) fail execution_evidence ;;
    esac
  fi
  REPORT_STATUS='PASS'
  FAILURE_CATEGORY='none'
  write_report || fail report
  write_runtime_audit_summary || fail report
  print_success_summary
}

main "$@"
