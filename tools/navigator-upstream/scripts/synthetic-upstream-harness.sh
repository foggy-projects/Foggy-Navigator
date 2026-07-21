#!/usr/bin/bash -p
# INT-001 synthetic upstream integration harness.
#
# This script intentionally owns only a disposable local lifecycle.  It does
# not provision Navigator resources, use a real upstream profile, or replace
# a real Worker.  A separate bootstrap implementation may consume the private
# run-owned bootstrap target only after `run` has made this stack healthy.
#
# Invoke this file directly (or through /usr/bin/bash -p), never through a
# caller-selected `bash`.  Privileged mode prevents non-interactive startup
# files from changing the lifecycle before this script can establish its
# fail-closed environment.

set -euo pipefail
# A caller can invoke Bash with `-m` even without an interactive terminal.
# In that mode a background wrapper becomes its own process-group leader;
# `setsid` then forks and the wrapper PID no longer names the owned service.
# Disable monitor mode before this script starts any background lifecycle
# child, so `setsid` can retain the PID we record and later clean up.
set +m
IFS=$'\n\t'
umask 077

# The lifecycle begins before it reads a run-owned carrier.  Do not let an
# invoking shell choose the interpreter or command lookup path, and discard
# shell startup/directory injection variables before the first `cd`.
readonly TRUSTED_BASH='/usr/bin/bash'
readonly TRUSTED_SETSID='/usr/bin/setsid'
# The Launcher must use the same host Java that doctor verifies.  Resolve the
# distribution-managed link once per lifecycle invocation, then execute only
# its canonical absolute path; never inherit a caller-selected Java from PATH.
readonly TRUSTED_JAVA_LINK='/usr/bin/java'
readonly SAFE_CHILD_PATH='/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin'
# Docker Desktop/WSL's host-published loopback path is exercised more
# reliably in this bounded non-privileged range than in the kernel's default
# high ephemeral range. Explicit operator overrides remain subject only to
# the general non-reserved port validation below.
readonly DYNAMIC_PORT_MIN=20000
readonly DYNAMIC_PORT_MAX=29999
# A forced-SIGNAL rehearsal must never leave a healthy stack running if its
# supervisor disappears.  This bounded, non-caller-controlled hold starts
# only after the owned Launcher is health-ready; expiry takes the same
# ownership-checked cleanup path and cannot produce a SIGNAL success receipt.
readonly PARENT_TERM_REHEARSAL_HOLD_SECONDS=180
readonly CLEANUP_RECEIPT_SCHEMA_VERSION=4
# `start_child` reserves this status for a process that did begin but exited
# before its ownership metadata could be recorded. It is distinct from local
# setup/exec preparation failures so the Launcher's fixed-enum classifier can
# inspect only the verified private process log when appropriate.
readonly START_CHILD_EXITED_BEFORE_METADATA=10
PATH="$SAFE_CHILD_PATH"
export PATH
unset BASH_ENV ENV CDPATH
case "$-" in
  *p*) ;;
  *)
    printf 'INT-001 synthetic harness: requires /usr/bin/bash -p\n' >&2
    exit 2
    ;;
esac

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd -P)"
HARNESS_SELF="$SCRIPT_DIR/synthetic-upstream-harness.sh"
ARTIFACT_ROOT="$REPO_ROOT/temp/test-artifacts/INT-001"
COMPOSE_FILE="$REPO_ROOT/tools/navigator-upstream/fixtures/synthetic-integration/docker-compose.yml"
RESPONSE_TEMPLATE="$REPO_ROOT/tools/navigator-upstream/fixtures/synthetic-integration/responses/static-no-tool.yaml.template"
DIRECTORY_FACADE="$REPO_ROOT/tools/navigator-upstream/fixtures/synthetic-integration/directory_facade.py"
BIZ_INGRESS_PROXY="$REPO_ROOT/tools/navigator-upstream/fixtures/synthetic-integration/biz_ingress_proxy.py"
LAUNCHER_JAR="$REPO_ROOT/launcher/target/launcher-1.0.0-SNAPSHOT.jar"
# The isolated Biz fixture must use the repository's explicit virtual
# environment.  The system Python deliberately has no LangGraph/Uvicorn
# dependencies on this host; falling back to it would turn an environment
# accident into a misleading runtime failure.
BIZ_WORKER_PYTHON="$REPO_ROOT/tools/langgraph-biz-worker/.venv/bin/python"
BOOTSTRAP_HELPER="$REPO_ROOT/tools/navigator-upstream/scripts/synthetic-upstream-bootstrap.sh"
RUNTIME_AUDIT="$REPO_ROOT/tools/navigator-upstream/scripts/synthetic-upstream-runtime-audit.sh"

# Only the normal local development ports are reserved.  INT-001 dynamically
# allocates every port instead of relying on a second fixed development stack.
readonly RESERVED_PORTS=(8112 8200 3031 3051 3061 3071 3131 3151 3161 3062 5174 5181)
readonly RUN_ID_PATTERN='^[a-z0-9][a-z0-9-]{5,63}$'
readonly SYNTHETIC_SYSTEM_ROOT_USERNAME='int001-root'
# Never project the invoking shell's PATH into an owned child.  This prevents a
# local profile or CI wrapper from changing which launcher, Python, or Docker
# binary an INT-001 lifecycle action resolves.
readonly LOCAL_DOCKER_SOCKET_PATH='/var/run/docker.sock'
readonly LOCAL_DOCKER_HOST='unix:///var/run/docker.sock'
readonly PRIVATE_DIRECTORY_NAME='private'
readonly STACK_ENV_NAME='stack.env'
readonly LAUNCHER_ENV_NAME='launcher.env'
readonly BIZ_WORKER_ENV_NAME='biz-worker.env'
readonly BIZ_INGRESS_PROXY_ENV_NAME='biz-ingress-proxy.env'
readonly DIRECTORY_FACADE_ENV_NAME='directory-facade.env'
readonly BOOTSTRAP_TARGET_PROFILE_NAME='bootstrap-target.env'
readonly RUNTIME_CHILD_PROFILE_NAME='runtime-child.env'
readonly RUNTIME_CLI_PROFILE_NAME='runtime-cli.env'
readonly RUN_MANIFEST_NAME='run-manifest.json'
readonly RUNTIME_AUDIT_REPORT_NAME='runtime-audit-report.json'
readonly LAUNCHER_LOG_NAME='launcher.log'
readonly LAUNCHER_PROCESS_LOG_NAME='launcher-process.log'
readonly BIZ_WORKER_LOG_NAME='biz-worker.log'
readonly BIZ_INGRESS_PROXY_LOG_NAME='biz-ingress-proxy.log'
readonly DIRECTORY_FACADE_LOG_NAME='directory-facade.log'
readonly BOOTSTRAP_PLAN_NAME='bootstrap-plan.txt'
readonly CLEANUP_REPORT_NAME='cleanup-report.json'
readonly BIZ_INGRESS_COUNTER_NAME='biz-ingress-count'
readonly BIZ_INGRESS_LOCK_NAME='biz-ingress-count.lock'
readonly -a LEGACY_ROOT_PRIVATE_CARRIER_NAMES=(
  "$STACK_ENV_NAME" "$LAUNCHER_ENV_NAME" "$BIZ_WORKER_ENV_NAME"
  "$BIZ_INGRESS_PROXY_ENV_NAME" "$DIRECTORY_FACADE_ENV_NAME"
  "$BOOTSTRAP_TARGET_PROFILE_NAME" "$RUNTIME_CHILD_PROFILE_NAME"
  "$RUNTIME_CLI_PROFILE_NAME" "$RUN_MANIFEST_NAME" "$RUNTIME_AUDIT_REPORT_NAME"
  "$LAUNCHER_LOG_NAME" "$LAUNCHER_PROCESS_LOG_NAME" "$BIZ_WORKER_LOG_NAME"
  "$BIZ_INGRESS_PROXY_LOG_NAME" "$DIRECTORY_FACADE_LOG_NAME" "$BOOTSTRAP_PLAN_NAME"
)

ACTION=""
RUN_ID=""
ALLOW_CREATE=0
ALLOW_EXECUTE=0
BUILD_LAUNCHER=0
# `--forced-signal-rehearsal` is accepted only by the outer one-shot exercise
# launched by BUG-009's supervisor.  It is intentionally visible in that
# parent's canonical argv.  The exact inner `run --hold-for-parent-term` argv
# is separately verified before a parent-only TERM may be forwarded.
FORCED_SIGNAL_REHEARSAL=0
HOLD_FOR_PARENT_TERM=0
NAVIGATOR_PORT=""
MYSQL_PORT=""
MOCK_LLM_PORT=""
BIZ_PORT=""
BIZ_INGRESS_PROXY_PORT=""
DIRECTORY_FACADE_PORT=""
LAUNCHER_JAVA=""

declare -A STACK_ENV=()
declare -A LAUNCHER_ENV=()
declare -A BIZ_ENV=()
declare -A BIZ_INGRESS_PROXY_ENV=()
declare -A FACADE_ENV=()
declare -A BOOTSTRAP_ENV=()
declare -A RUNTIME_CHILD_ENV=()
declare -A CHILD_META=()

# Set only while an owned cleanup is in progress.  The EXIT trap guarantees
# credential carriers are scrubbed even when a Docker/ownership check fails.
CLEANUP_SCRUB_RUN_DIR=""
CLEANUP_SCRUB_COMPLETED=0
# `run`, `bootstrap`, and `audit` are independently invokable lifecycle
# stages.  Once one has accepted a prepared run, an interrupt must follow the
# same ownership-checked cleanup path as a normal stage failure.  This state
# is deliberately process-local and is cleared on a successful stage return.
LIFECYCLE_SIGNAL_RUN_DIR=""
LIFECYCLE_SIGNAL_CLEANUP_ARMED=0
# `exercise` delegates each lifecycle stage to a separate harness process.
# Track that owned direct child so a signal addressed only to the parent can
# be forwarded to the child's dedicated session before the parent attempts to
# acquire the same run lock for its own cleanup verification.
EXERCISE_CHILD_PID=""
EXERCISE_CHILD_STAGE=""
EXERCISE_CHILD_START_TICKS=""
# The parent must retain the exact re-entry command it launched.  A PID,
# session and cwd alone are not enough to forward a signal: a recycled or
# otherwise substituted process must also match the expected Bash, harness,
# lifecycle action and current runId byte-for-byte in /proc/<pid>/cmdline.
declare -a EXERCISE_CHILD_EXPECTED_ARGV=()
# This is deliberately a fixed, non-secret diagnosis label.  It is retained
# in the root cleanup receipt after private logs, environment carriers and
# child ownership metadata are scrubbed, so a failed disposable run can be
# safely narrowed without preserving a stack trace, URL, or configuration.
LIFECYCLE_FAILURE_STAGE='NONE'
# This is intentionally a fixed, non-secret observation.  It explains only
# the Launcher readiness outcome seen by this lifecycle process; it never
# carries a URL, log excerpt, exception, environment value, or process ID.
LAUNCHER_READINESS_OBSERVATION='NOT_OBSERVED'
# This is a second, fixed diagnosis dimension.  Unlike the readiness
# observation it may classify an already-proven Launcher exit, but it must
# never retain a log line, exception text, path, URL, credential, or process
# identifier.  The classifier below only tests static literals against the
# verified private process log and writes this allow-listed enum to the root
# cleanup receipt after the private carrier is removed.
LAUNCHER_FAILURE_CLASS='NOT_APPLICABLE'
# This fixed, non-secret observation describes only the held child lifecycle
# inside the BUG-009 rehearsal. It never proves that an outer parent was
# authorized to forward TERM, that TERM was dispatched, or that cleanup was
# successful. Normal lifecycle paths retain NOT_REHEARSAL.
REHEARSAL_LIFECYCLE_OBSERVATION='NOT_REHEARSAL'

usage() {
  cat <<'USAGE'
Usage:
  synthetic-upstream-harness.sh prepare --allow-create [--run-id <id>]
      [--navigator-port <port>] [--mysql-port <port>]
      [--mock-llm-port <port>] [--biz-port <port>]
      [--biz-ingress-proxy-port <port>]
      [--directory-facade-port <port>]
  synthetic-upstream-harness.sh doctor --run-id <id>
  synthetic-upstream-harness.sh verify-running --run-id <id>
  synthetic-upstream-harness.sh bootstrap --allow-create --run-id <id>
  synthetic-upstream-harness.sh run --allow-execute --build-launcher --run-id <id>
      [--hold-for-parent-term]
  synthetic-upstream-harness.sh audit --allow-execute --run-id <id>
  synthetic-upstream-harness.sh cleanup --allow-execute --run-id <id>
  synthetic-upstream-harness.sh exercise --allow-create --allow-execute [--run-id <id>]
      [--navigator-port <port>] [--mysql-port <port>]
      [--mock-llm-port <port>] [--biz-port <port>]
      [--biz-ingress-proxy-port <port>]
      [--directory-facade-port <port>] [--build-launcher]
      [--forced-signal-rehearsal]

Safety boundary:
  - prepare and doctor never start a service or mutate Navigator.
  - verify-running is read-only; it proves that an existing RUNNING stack is
    this harness's owned target before the bootstrap helper may mutate it.
  - run starts only run-owned loopback children and this run's Compose project.
  - bootstrap can create only disposable Navigator resources after `run` has
    proved this run's stack healthy; it requires --allow-create.
  - audit can issue only the documented runtime-lane probe after bootstrap; it
    requires --allow-execute and starts its child with an explicit allow-list.
  - exercise is the recommended one-shot path. It runs prepare, doctor, run,
    bootstrap, audit, and cleanup in order. Once prepare succeeds, any later
    failure or interruption attempts the same owned-resource cleanup path.
  - --forced-signal-rehearsal is an internal BUG-009 supervisor path. It
    reaches a healthy run-owned Launcher, keeps that delegated lifecycle
    process alive, and waits only for the proven outer parent's TERM; it does
    not run bootstrap, audit, or a normal cleanup stage.
  - cleanup refuses to signal or delete anything without run-id, cwd, PID-start,
    process-group, Compose-project, and INT-001 label ownership proof.
  - generated secret carriers are 0600 files under temp/test-artifacts/INT-001.
  - NAVIGATOR_EXTERNAL_ENABLED only opens the disposable target's Open API
    routes; it does not enable Gateway external, Provider, or production.
USAGE
}

die() {
  printf 'INT-001 synthetic harness: %s\n' "$*" >&2
  exit 2
}

note() {
  printf 'INT-001 synthetic harness: %s\n' "$*"
}

cleanup_exit_trap() {
  local status="$?"
  trap - EXIT
  # Do not turn an operational cleanup failure into a credential-residue
  # failure.  This helper never follows symlinks or emits private values.
  if [[ -n "$CLEANUP_SCRUB_RUN_DIR" && "$CLEANUP_SCRUB_COMPLETED" != 1 ]]; then
    scrub_after_failed_cleanup "$CLEANUP_SCRUB_RUN_DIR" || true
  fi
  exit "$status"
}

trap cleanup_exit_trap EXIT

arm_lifecycle_signal_cleanup() {
  local run_dir="$1"
  [[ -n "$run_dir" ]] || die "cannot arm lifecycle cleanup without a run directory"
  CLEANUP_SCRUB_RUN_DIR="$run_dir"
  CLEANUP_SCRUB_COMPLETED=0
  LIFECYCLE_SIGNAL_RUN_DIR="$run_dir"
  LIFECYCLE_SIGNAL_CLEANUP_ARMED=1
  trap 'lifecycle_signal_cleanup HUP' HUP
  trap 'lifecycle_signal_cleanup INT' INT
  trap 'lifecycle_signal_cleanup TERM' TERM
}

disarm_lifecycle_signal_cleanup() {
  local run_dir="$1"
  trap - HUP INT TERM
  LIFECYCLE_SIGNAL_RUN_DIR=""
  LIFECYCLE_SIGNAL_CLEANUP_ARMED=0
  if [[ "$CLEANUP_SCRUB_RUN_DIR" == "$run_dir" ]]; then
    CLEANUP_SCRUB_RUN_DIR=""
    CLEANUP_SCRUB_COMPLETED=0
  fi
}

lifecycle_signal_cleanup() {
  local signal="$1" run_dir
  trap - HUP INT TERM
  if [[ "$LIFECYCLE_SIGNAL_CLEANUP_ARMED" == 1 && -n "$LIFECYCLE_SIGNAL_RUN_DIR" ]]; then
    run_dir="$LIFECYCLE_SIGNAL_RUN_DIR"
    LIFECYCLE_SIGNAL_CLEANUP_ARMED=0
    LIFECYCLE_SIGNAL_RUN_DIR=""
    # Record only the narrow fact that the actual held `run` lifecycle was
    # signalled after it entered the parent-TERM hold.  A signal while the
    # ordinary run is still starting, or a signal in any other action, keeps
    # its default NOT_REHEARSAL observation and cannot be relabelled later.
    if [[ "$ACTION" == run && "$HOLD_FOR_PARENT_TERM" == 1 \
      && "$REHEARSAL_LIFECYCLE_OBSERVATION" == HOLD_ENTERED ]]; then
      REHEARSAL_LIFECYCLE_OBSERVATION='HOLD_SIGNAL_RECEIVED'
    fi
    set_lifecycle_failure_stage SIGNAL
    note "lifecycle received $signal; attempting owned cleanup"
    # A signal is the lifecycle failure.  `FAILED_CLEANUP` is reserved for a
    # cleanup that itself cannot prove completion; otherwise the durable
    # receipt must distinguish the two facts.
    cleanup_run "$run_dir" CLEANED "$LIFECYCLE_FAILURE_STAGE" || true
  fi
  exit 128
}

failure_stage_allowed() {
  case "$1" in
    NONE|PREPARE|PREFLIGHT|BUILD|COMPOSE|DIRECTORY_FACADE|BIZ_WORKER|BIZ_INGRESS_PROXY|LAUNCHER|BOOTSTRAP|AUDIT|MANIFEST|SIGNAL|UNKNOWN)
      return 0
      ;;
    *)
      return 1
      ;;
  esac
}

cleanup_result_allowed() {
  case "$1" in
    CLEANED|FAILED_CLEANUP)
      return 0
      ;;
    *)
      return 1
      ;;
  esac
}

launcher_readiness_observation_allowed() {
  case "$1" in
    NOT_OBSERVED|START_FAILED|HEALTH_READY|CHILD_EXITED_BEFORE_HEALTH|CHILD_OWNERSHIP_UNPROVEN|CHILD_ALIVE_AT_HEALTH_TIMEOUT)
      return 0
      ;;
    *)
      return 1
      ;;
  esac
}

launcher_failure_class_allowed() {
  case "$1" in
    NOT_APPLICABLE|START_EXEC_FAILURE|PORT_BIND_CONFLICT|DATABASE_CONNECTIVITY|DATABASE_AUTHORIZATION|DATABASE_SCHEMA|SPRING_CONFIGURATION|JVM_OR_ARTIFACT|APPLICATION_INITIALIZATION|HEALTH_TIMEOUT|OWNERSHIP_UNPROVEN|UNKNOWN)
      return 0
      ;;
    *)
      return 1
      ;;
  esac
}

rehearsal_lifecycle_observation_allowed() {
  case "$1" in
    NOT_REHEARSAL|HOLD_ENTERED|HOLD_TIMEOUT|HOLD_WAIT_FAILURE|HOLD_SIGNAL_RECEIVED)
      return 0
      ;;
    *)
      return 1
      ;;
  esac
}

set_lifecycle_failure_stage() {
  local stage="$1"
  failure_stage_allowed "$stage" || die "lifecycle failure stage is unsafe"
  LIFECYCLE_FAILURE_STAGE="$stage"
}

fail_lifecycle_stage() {
  local run_dir="$1" stage="$2"
  shift 2
  # Call sites pass literals, but fail closed if a future caller attempts to
  # place an arbitrary message or runtime-derived value in the durable receipt.
  failure_stage_allowed "$stage" || stage='UNKNOWN'
  set_lifecycle_failure_stage "$stage"
  # A failed lifecycle may still have a valid, private Launcher process log.
  # Convert only static, pre-approved failure signatures into an enum before
  # cleanup destroys the private carrier.  The classifier is best-effort: an
  # unsafe/missing log leaves an UNKNOWN enum and must never prevent cleanup.
  if [[ "$stage" == LAUNCHER && "$LAUNCHER_FAILURE_CLASS" == NOT_APPLICABLE ]]; then
    classify_launcher_failure "$run_dir"
  fi
  trap - HUP INT TERM
  LIFECYCLE_SIGNAL_CLEANUP_ARMED=0
  LIFECYCLE_SIGNAL_RUN_DIR=""
  cleanup_run "$run_dir" CLEANED "$LIFECYCLE_FAILURE_STAGE" || true
  die "$*"
}

require_value() {
  [[ -n "${2:-}" ]] || die "$1 requires a value"
}

assert_once() {
  local label="$1"
  local value="$2"
  [[ -z "$value" ]] || die "$label may be specified only once"
}

parse_args() {
  ACTION="${1:-}"
  [[ -n "$ACTION" ]] || {
    usage >&2
    exit 2
  }
  shift || true

  case "$ACTION" in
    prepare|doctor|verify-running|bootstrap|run|audit|cleanup|exercise) ;;
    help|--help|-h)
      usage
      exit 0
      ;;
    *)
      usage >&2
      die "unknown action: $ACTION"
      ;;
  esac

  while [[ $# -gt 0 ]]; do
    case "$1" in
      --allow-create)
        [[ "$ALLOW_CREATE" == 0 ]] || die "--allow-create may be specified only once"
        ALLOW_CREATE=1
        shift
        ;;
      --allow-execute)
        [[ "$ALLOW_EXECUTE" == 0 ]] || die "--allow-execute may be specified only once"
        ALLOW_EXECUTE=1
        shift
        ;;
      --build-launcher)
        [[ "$BUILD_LAUNCHER" == 0 ]] || die "--build-launcher may be specified only once"
        BUILD_LAUNCHER=1
        shift
        ;;
      --forced-signal-rehearsal)
        [[ "$FORCED_SIGNAL_REHEARSAL" == 0 ]] || die "--forced-signal-rehearsal may be specified only once"
        FORCED_SIGNAL_REHEARSAL=1
        shift
        ;;
      --hold-for-parent-term)
        [[ "$HOLD_FOR_PARENT_TERM" == 0 ]] || die "--hold-for-parent-term may be specified only once"
        HOLD_FOR_PARENT_TERM=1
        shift
        ;;
      --run-id)
        require_value "$1" "${2:-}"
        assert_once "$1" "$RUN_ID"
        RUN_ID="$2"
        shift 2
        ;;
      --navigator-port)
        require_value "$1" "${2:-}"
        assert_once "$1" "$NAVIGATOR_PORT"
        NAVIGATOR_PORT="$2"
        shift 2
        ;;
      --mysql-port)
        require_value "$1" "${2:-}"
        assert_once "$1" "$MYSQL_PORT"
        MYSQL_PORT="$2"
        shift 2
        ;;
      --mock-llm-port)
        require_value "$1" "${2:-}"
        assert_once "$1" "$MOCK_LLM_PORT"
        MOCK_LLM_PORT="$2"
        shift 2
        ;;
      --biz-port)
        require_value "$1" "${2:-}"
        assert_once "$1" "$BIZ_PORT"
        BIZ_PORT="$2"
        shift 2
        ;;
      --biz-ingress-proxy-port)
        require_value "$1" "${2:-}"
        assert_once "$1" "$BIZ_INGRESS_PROXY_PORT"
        BIZ_INGRESS_PROXY_PORT="$2"
        shift 2
        ;;
      --directory-facade-port)
        require_value "$1" "${2:-}"
        assert_once "$1" "$DIRECTORY_FACADE_PORT"
        DIRECTORY_FACADE_PORT="$2"
        shift 2
        ;;
      --profile|--profile=*|--runtime-profile|--runtime-profile=*|--env-file|--env-file=*|--system-profile|--system-profile=*|--tenant-profile|--tenant-profile=*)
        die "external profiles are unsupported; INT-001 only uses generated run-owned files"
        ;;
      --help|-h)
        usage
        exit 0
        ;;
      *)
        die "unknown option: $1"
        ;;
    esac
  done

  case "$ACTION" in
    prepare)
      [[ "$ALLOW_CREATE" == 1 ]] || die "prepare requires --allow-create"
      [[ "$ALLOW_EXECUTE" == 0 && "$BUILD_LAUNCHER" == 0 ]] || die "prepare accepts only --allow-create and optional port/run-id values"
      [[ "$FORCED_SIGNAL_REHEARSAL" == 0 && "$HOLD_FOR_PARENT_TERM" == 0 ]] \
        || die "prepare does not accept forced-signal rehearsal options"
      ;;
    doctor)
      [[ "$ALLOW_CREATE" == 0 && "$ALLOW_EXECUTE" == 0 && "$BUILD_LAUNCHER" == 0 ]] || die "doctor has no create or execute opt-in"
      [[ -n "$RUN_ID" ]] || die "doctor requires --run-id"
      [[ "$FORCED_SIGNAL_REHEARSAL" == 0 && "$HOLD_FOR_PARENT_TERM" == 0 ]] \
        || die "doctor does not accept forced-signal rehearsal options"
      assert_no_port_arguments
      ;;
    verify-running)
      [[ "$ALLOW_CREATE" == 0 && "$ALLOW_EXECUTE" == 0 && "$BUILD_LAUNCHER" == 0 ]] || die "verify-running has no create or execute opt-in"
      [[ -n "$RUN_ID" ]] || die "verify-running requires --run-id"
      [[ "$FORCED_SIGNAL_REHEARSAL" == 0 && "$HOLD_FOR_PARENT_TERM" == 0 ]] \
        || die "verify-running does not accept forced-signal rehearsal options"
      assert_no_port_arguments
      ;;
    bootstrap)
      [[ "$ALLOW_CREATE" == 1 ]] || die "bootstrap requires --allow-create"
      [[ "$ALLOW_EXECUTE" == 0 && "$BUILD_LAUNCHER" == 0 ]] || die "bootstrap accepts only --allow-create and --run-id"
      [[ -n "$RUN_ID" ]] || die "bootstrap requires --run-id"
      [[ "$FORCED_SIGNAL_REHEARSAL" == 0 && "$HOLD_FOR_PARENT_TERM" == 0 ]] \
        || die "bootstrap does not accept forced-signal rehearsal options"
      assert_no_port_arguments
      ;;
    run)
      [[ "$ALLOW_EXECUTE" == 1 ]] || die "run requires --allow-execute"
      [[ "$ALLOW_CREATE" == 0 ]] || die "run does not create a run; call prepare first"
      [[ -n "$RUN_ID" ]] || die "run requires --run-id"
      [[ "$BUILD_LAUNCHER" == 1 ]] \
        || die "run requires --build-launcher so the Launcher and source-matched Open SDK are built from this checkout"
      [[ "$FORCED_SIGNAL_REHEARSAL" == 0 ]] || die "run does not accept --forced-signal-rehearsal"
      assert_no_port_arguments
      ;;
    audit)
      [[ "$ALLOW_EXECUTE" == 1 ]] || die "audit requires --allow-execute"
      [[ "$ALLOW_CREATE" == 0 && "$BUILD_LAUNCHER" == 0 ]] || die "audit accepts only --allow-execute and --run-id"
      [[ -n "$RUN_ID" ]] || die "audit requires --run-id"
      [[ "$FORCED_SIGNAL_REHEARSAL" == 0 && "$HOLD_FOR_PARENT_TERM" == 0 ]] \
        || die "audit does not accept forced-signal rehearsal options"
      assert_no_port_arguments
      ;;
    cleanup)
      [[ "$ALLOW_EXECUTE" == 1 ]] || die "cleanup requires --allow-execute"
      [[ "$ALLOW_CREATE" == 0 && "$BUILD_LAUNCHER" == 0 ]] || die "cleanup accepts only --allow-execute and --run-id"
      [[ -n "$RUN_ID" ]] || die "cleanup requires --run-id"
      [[ "$FORCED_SIGNAL_REHEARSAL" == 0 && "$HOLD_FOR_PARENT_TERM" == 0 ]] \
        || die "cleanup does not accept forced-signal rehearsal options"
      assert_no_port_arguments
      ;;
    exercise)
      [[ "$ALLOW_CREATE" == 1 && "$ALLOW_EXECUTE" == 1 ]] \
        || die "exercise requires both --allow-create and --allow-execute"
      [[ "$HOLD_FOR_PARENT_TERM" == 0 ]] || die "exercise delegates --hold-for-parent-term only to its proven run child"
      ;;
  esac
}

assert_no_port_arguments() {
  [[ -z "$NAVIGATOR_PORT$MYSQL_PORT$MOCK_LLM_PORT$BIZ_PORT$BIZ_INGRESS_PROXY_PORT$DIRECTORY_FACADE_PORT" ]] \
    || die "port overrides are allowed only by prepare"
}

assert_repo_layout() {
  [[ -x "$TRUSTED_BASH" && ! -L "$TRUSTED_BASH" ]] \
    || die "trusted Bash interpreter is unavailable or unsafe"
  [[ -x "$TRUSTED_SETSID" && ! -L "$TRUSTED_SETSID" ]] \
    || die "trusted setsid binary is unavailable or unsafe"
  [[ -f "$REPO_ROOT/pom.xml" ]] || die "repository root could not be verified"
  [[ -f "$COMPOSE_FILE" ]] || die "missing fixture compose file"
  [[ -f "$RESPONSE_TEMPLATE" ]] || die "missing Mock LLM response template"
  [[ -f "$DIRECTORY_FACADE" ]] || die "missing run-owned directory facade fixture"
  [[ -f "$BIZ_INGRESS_PROXY" && ! -L "$BIZ_INGRESS_PROXY" ]] \
    || die "missing run-owned Biz ingress proxy fixture"
  [[ -x "$BIZ_WORKER_PYTHON" ]] || die "missing repository LangGraph Biz Worker virtual environment"
  # The bootstrap helper is deliberately invoked through an explicit Bash
  # interpreter below.  Requiring an executable bit would turn a checkout
  # mode difference into an unsafe temptation to chmod a tracked helper.
  [[ -f "$BOOTSTRAP_HELPER" && ! -L "$BOOTSTRAP_HELPER" ]] \
    || die "missing synthetic upstream bootstrap helper"
  [[ -f "$RUNTIME_AUDIT" && ! -L "$RUNTIME_AUDIT" ]] \
    || die "missing synthetic upstream runtime audit helper"
}

assert_no_inherited_profile_or_credentials() {
  local name
  while IFS= read -r name; do
    case "$name" in
      NAVI_*|NAVIGATOR_*|BUSINESS_AGENT_*|BIZ_WORKER_*|SYSTEM_ROOT_*|SPRING_DATASOURCE_*|AGENT_LLM_*|INT001_*|DOCKER_*|COMPOSE_*)
        die "refusing inherited $name; unset Navigator, INT-001, Docker, Compose, credential, or runtime configuration variables first"
        ;;
    esac
  done < <(compgen -e | LC_ALL=C sort)
}

local_docker_home() {
  local home
  home="$(getent passwd "$(id -u)" 2>/dev/null | awk -F: 'NR == 1 { print $6 }')"
  [[ -n "$home" && -d "$home" && ! -L "$home" ]] || die "current user home cannot be safely resolved for local Docker"
  [[ "$(stat -c '%u' "$home" 2>/dev/null)" == "$(id -u)" ]] \
    || die "current user home is not owned by the invoking user"
  printf '%s' "$home"
}

assert_local_docker_socket() {
  local socket_type
  [[ "$LOCAL_DOCKER_HOST" == "unix://$LOCAL_DOCKER_SOCKET_PATH" ]] \
    || die "INT-001 Docker host must remain the fixed local Unix socket"
  # GNU stat without -L uses lstat(2).  Reject a symlink explicitly as well
  # as any endpoint whose own inode is not a Unix-domain socket.
  [[ ! -L "$LOCAL_DOCKER_SOCKET_PATH" ]] \
    || die "INT-001 refuses a symlinked Docker socket"
  socket_type="$(LC_ALL=C stat -c '%F' -- "$LOCAL_DOCKER_SOCKET_PATH" 2>/dev/null)" \
    || die "cannot inspect the fixed local Docker socket"
  [[ "$socket_type" == socket ]] \
    || die "INT-001 requires the fixed Docker endpoint to be a Unix socket"
}

docker_local() {
  local home
  assert_local_docker_socket
  home="$(local_docker_home)"
  # Run from an empty environment and use the fixed socket as an explicit
  # command-line target.  Do not select a Docker context or rely on
  # DOCKER_HOST: either can resolve through mutable context configuration.
  env -i "PATH=$SAFE_CHILD_PATH" "HOME=$home" \
    docker --host "$LOCAL_DOCKER_HOST" "$@"
}

assert_local_docker_target() {
  assert_local_docker_socket
}

docker_compose_for_run() {
  local run_dir="$1"
  shift
  [[ -n "${STACK_ENV[INT001_COMPOSE_PROJECT]:-}" ]] || die "Compose project was not loaded from the strict run profile"
  docker_local compose --env-file "$(private_file_path "$run_dir" "$STACK_ENV_NAME")" -p "${STACK_ENV[INT001_COMPOSE_PROJECT]}" -f "$COMPOSE_FILE" "$@"
}

assert_ignored_artifact_root() {
  git -C "$REPO_ROOT" check-ignore -q "temp/test-artifacts/INT-001/.ignore-probe" \
    || die "temp/test-artifacts/INT-001 is not gitignored; refusing private run files"
}

assert_artifact_root_path_chain() {
  local current="$REPO_ROOT" component
  # REPO_ROOT comes from pwd -P, but the fixed children below may still have
  # been replaced by a symlink.  Check every existing component before it is
  # used by a lifecycle command; testing only the final path would miss a
  # symlink in temp/ or test-artifacts/.
  [[ -d "$current" && ! -L "$current" ]] || die "repository root is unsafe"
  for component in temp test-artifacts INT-001; do
    current="$current/$component"
    [[ -d "$current" && ! -L "$current" ]] \
      || die "INT-001 artifact path component is absent or unsafe: $component"
  done
}

ensure_private_artifact_root() {
  local current="$REPO_ROOT" component owner
  # Create fixed path components one at a time.  Do not use mkdir -p or chmod
  # before checking a pre-existing component: either can follow a symlink and
  # mutate a target outside the INT-001 artifact namespace.
  [[ -d "$current" && ! -L "$current" ]] || die "repository root is unsafe"
  for component in temp test-artifacts INT-001; do
    current="$current/$component"
    if [[ -e "$current" || -L "$current" ]]; then
      [[ -d "$current" && ! -L "$current" ]] \
        || die "INT-001 artifact path component is unsafe: $component"
    else
      mkdir -m 700 -- "$current" \
        || die "cannot create INT-001 artifact path component: $component"
      [[ -d "$current" && ! -L "$current" ]] \
        || die "INT-001 artifact path component became unsafe: $component"
    fi
  done
  owner="$(stat -c '%u' -- "$ARTIFACT_ROOT" 2>/dev/null)" \
    || die "cannot inspect INT-001 artifact root ownership"
  [[ "$owner" == "$(id -u)" ]] || die "INT-001 artifact root is not current-user owned"
  # The leaf is now proven to be a real directory owned by this user, so it is
  # safe to restore the private mode before any file descriptor is opened.
  chmod 700 -- "$ARTIFACT_ROOT" || die "cannot protect INT-001 artifact root"
  assert_private_dir "$ARTIFACT_ROOT"
}

validate_run_id() {
  [[ "$RUN_ID" =~ $RUN_ID_PATTERN ]] || die "runId must match [a-z0-9][a-z0-9-]{5,63}"
  [[ "$RUN_ID" != *--* && "$RUN_ID" != *- ]] || die "runId must not contain repeated or trailing '-'"
}

random_hex() {
  local chars="$1"
  if command -v openssl >/dev/null 2>&1; then
    openssl rand -hex "$(((chars + 1) / 2))" | cut -c1-"$chars"
    return
  fi
  if command -v od >/dev/null 2>&1; then
    od -An -N "$(((chars + 1) / 2))" -tx1 /dev/urandom | tr -d ' \n' | cut -c1-"$chars"
    return
  fi
  die "openssl or od is required to generate disposable credentials"
}

generate_run_id() {
  printf 'int001-%s-%s\n' "$(date -u +%Y%m%dt%H%M%sz)" "$(random_hex 12)"
}

run_dir_for() {
  printf '%s/%s\n' "$ARTIFACT_ROOT" "$1"
}

assert_expected_run_path() {
  local run_dir="$1"
  local root_real run_real
  assert_artifact_root_path_chain
  assert_private_dir "$ARTIFACT_ROOT"
  root_real="$(realpath -m "$ARTIFACT_ROOT")"
  run_real="$(realpath -m "$run_dir")"
  [[ "$run_real" == "$root_real/$RUN_ID" ]] || die "run directory escaped the INT-001 artifact root"
}

assert_private_file() {
  local file="$1"
  local mode owner links
  [[ -f "$file" && ! -L "$file" ]] || die "required private file is absent or unsafe: $(basename "$file")"
  mode="$(stat -c '%a' "$file" 2>/dev/null)" || die "cannot inspect private file mode"
  owner="$(stat -c '%u' "$file" 2>/dev/null)" || die "cannot inspect private file owner"
  links="$(stat -c '%h' "$file" 2>/dev/null)" || die "cannot inspect private file links"
  [[ "$mode" == 600 && "$owner" == "$(id -u)" && "$links" == 1 ]] \
    || die "private file ownership or mode is unsafe: $(basename "$file")"
}

assert_private_dir() {
  local dir="$1"
  local mode owner
  [[ -d "$dir" && ! -L "$dir" ]] || die "run directory is absent or unsafe"
  mode="$(stat -c '%a' "$dir" 2>/dev/null)" || die "cannot inspect run directory mode"
  owner="$(stat -c '%u' "$dir" 2>/dev/null)" || die "cannot inspect run directory owner"
  [[ "$mode" == 700 && "$owner" == "$(id -u)" ]] || die "run directory ownership or mode is unsafe"
}

private_file_path() {
  local run_dir="$1" file_name="$2"
  printf '%s/%s/%s\n' "$run_dir" "$PRIVATE_DIRECTORY_NAME" "$file_name"
}

assert_private_named_run_file_path() {
  local run_dir="$1" file="$2" expected_name="$3" private_dir
  private_dir="$run_dir/$PRIVATE_DIRECTORY_NAME"
  assert_private_dir "$private_dir"
  [[ -n "$expected_name" && "$expected_name" != */* && "$file" == "$private_dir/$expected_name" ]] \
    || die "private run file is outside its fixed carrier path"
  # Existing state may be updated only after it is proven to be a single-link,
  # current-user-owned 0600 regular file.  New files are created by callers
  # with noclobber below; this helper never follows a root-level legacy path.
  if [[ -e "$file" || -L "$file" ]]; then
    assert_private_file "$file"
  fi
}

assert_no_legacy_root_private_carriers() {
  local run_dir="$1" file
  # A pre-private-layout run must never be resumed by reading its root-level
  # profiles, manifest, plans, or logs.  Cleanup may only scrub those stale
  # paths without opening them.
  for file in "${LEGACY_ROOT_PRIVATE_CARRIER_NAMES[@]}"; do
    [[ ! -e "$run_dir/$file" && ! -L "$run_dir/$file" ]] \
      || die "legacy root private carrier is forbidden: $file"
  done
}

schema_keys() {
  case "$1" in
    stack)
      printf '%s\n' INT001_RUN_ID INT001_COMPOSE_PROJECT INT001_NAVIGATOR_PORT INT001_MYSQL_PORT \
        INT001_MOCK_LLM_PORT INT001_BIZ_PORT INT001_BIZ_INGRESS_PROXY_PORT INT001_DIRECTORY_FACADE_PORT INT001_NAVIGATOR_URL \
        INT001_MOCK_LLM_URL INT001_MYSQL_DATABASE INT001_MYSQL_USER INT001_MYSQL_PASSWORD \
        INT001_MYSQL_ROOT_PASSWORD INT001_MYSQL_VOLUME INT001_MOCK_RESPONSES_DIR
      ;;
    launcher)
      printf '%s\n' SERVER_ADDRESS SERVER_PORT SPRING_DATASOURCE_URL SPRING_DATASOURCE_USERNAME \
        SPRING_DATASOURCE_PASSWORD SPRING_JPA_HIBERNATE_DDL_AUTO SYSTEM_ROOT_USERNAME \
        SYSTEM_ROOT_PASSWORD SYSTEM_ROOT_EMAIL JWT_SECRET NAVIGATOR_CREDENTIAL_KEY \
        NAVIGATOR_CREDENTIAL_SALT NAVIGATOR_EXTERNAL_ENABLED NAVIGATOR_WORKER_GATEWAY_EXTERNAL_ENABLED \
        BUSINESS_AGENT_DEV_SYNC_WORKER_URL BUSINESS_AGENT_DEV_SYNC_WORKER_TOKEN \
        AGENT_LLM_OPENAI_BASE_URL AGENT_LLM_OPENAI_API_KEY LOGGING_FILE_NAME \
        SESSION_MESSAGE_PAYLOAD_STORE_DIRECTORY
      ;;
    biz)
      printf '%s\n' BIZ_WORKER_HOST BIZ_WORKER_PORT BIZ_WORKER_WORKER_NAME BIZ_WORKER_WORKER_TOKEN \
        BIZ_WORKER_EXTERNAL_ENABLED BIZ_WORKER_NAVIGATOR_API_BASE BIZ_WORKER_LLM_PROVIDER \
        BIZ_WORKER_LLM_API_KEY BIZ_WORKER_LLM_BASE_URL BIZ_WORKER_LLM_MODEL \
        BIZ_WORKER_LLM_EXECUTE_SKILLS BIZ_WORKER_ENABLE_COMMAND BIZ_WORKER_DATA_ROOT \
        BIZ_WORKER_RUNTIME_MESSAGE_EVENT_LOG_ENABLED BIZ_WORKER_LLM_SUBMISSION_LOG_ENABLED \
        BIZ_WORKER_RUNTIME_COMPACTION_LLM_ENABLED
      ;;
    biz-ingress-proxy)
      printf '%s\n' INT001_RUN_ID INT001_BIZ_INGRESS_PROXY_HOST INT001_BIZ_INGRESS_PROXY_PORT \
        INT001_BIZ_INGRESS_UPSTREAM_URL INT001_BIZ_INGRESS_COUNTER_FILE INT001_BIZ_INGRESS_LOCK_FILE \
        INT001_BIZ_INGRESS_RUN_DIR
      ;;
    facade)
      printf '%s\n' INT001_RUN_ID INT001_DIRECTORY_FACADE_HOST INT001_DIRECTORY_FACADE_PORT \
        INT001_DIRECTORY_FACADE_ROOT INT001_DIRECTORY_FACADE_TOKEN
      ;;
    bootstrap)
      printf '%s\n' INT001_RUN_ID INT001_NAVIGATOR_URL INT001_BIZ_BASE_URL \
        INT001_DIRECTORY_FACADE_URL INT001_MOCK_LLM_URL INT001_DIRECTORY_FACADE_TOKEN \
        INT001_BOOTSTRAP_ROOT_USERNAME INT001_BOOTSTRAP_ROOT_PASSWORD
      ;;
    runtime-child)
      printf '%s\n' INT001_SYNTHETIC_UPSTREAM_HARNESS INT001_RUN_ID INT001_NAVI_BASE_URL \
        INT001_A_TENANT_ID INT001_A_CLIENT_APP_ID INT001_A_CLIENT_APP_KEY INT001_A_CLIENT_APP_SECRET \
        INT001_A_AGENT_ID INT001_A_UPSTREAM_USER_ID INT001_A_MODEL_CONFIG_ID INT001_A_DIRECTORY_ID \
        INT001_B_AGENT_ID INT001_C_AGENT_ID
      ;;
    child)
      printf '%s\n' INT001_CHILD_NAME PID START_TICKS PGID SID CWD COMMAND_FRAGMENT
      ;;
    *) die "internal unknown profile schema: $1" ;;
  esac
}

schema_allows_empty() {
  case "$1:$2" in
    launcher:BUSINESS_AGENT_DEV_SYNC_WORKER_TOKEN|biz:BIZ_WORKER_WORKER_TOKEN) return 0 ;;
  esac
  return 1
}

schema_contains_key() {
  local schema="$1" candidate="$2" key
  while IFS= read -r key; do
    [[ "$key" == "$candidate" ]] && return 0
  done < <(schema_keys "$schema")
  return 1
}

parse_strict_env() {
  local file="$1" schema="$2" target_name="$3"
  local -n target="$target_name"
  local raw key value expected line_number=0
  target=()
  assert_private_file "$file"
  while IFS= read -r raw || [[ -n "$raw" ]]; do
    ((line_number += 1))
    [[ -n "$raw" ]] || die "blank lines are not allowed in $(basename "$file")"
    [[ "$raw" != \#* && "$raw" == *=* ]] || die "malformed profile line $line_number in $(basename "$file")"
    key="${raw%%=*}"
    value="${raw#*=}"
    [[ "$key" =~ ^[A-Z][A-Z0-9_]*$ ]] || die "unsafe profile key in $(basename "$file")"
    schema_contains_key "$schema" "$key" || die "unknown profile key $key in $(basename "$file")"
    [[ -z "${target[$key]+present}" ]] || die "duplicate profile key $key in $(basename "$file")"
    [[ "$value" != *$'\r'* && "$value" != *$'\n'* ]] || die "unsafe profile value for $key"
    if [[ -z "$value" ]] && ! schema_allows_empty "$schema" "$key"; then
      die "empty profile value is not allowed for $key"
    fi
    target["$key"]="$value"
  done < "$file"
  while IFS= read -r expected; do
    [[ -n "${target[$expected]+present}" ]] || die "missing profile key $expected in $(basename "$file")"
  done < <(schema_keys "$schema")
}

validate_port() {
  local label="$1" value="$2" reserved
  [[ "$value" =~ ^[0-9]+$ ]] || die "$label must be a numeric TCP port"
  (( value >= 1025 && value <= 65535 )) || die "$label must be between 1025 and 65535"
  for reserved in "${RESERVED_PORTS[@]}"; do
    [[ "$value" != "$reserved" ]] || die "$label $value is reserved for the existing local stack"
  done
}

validate_port_set() {
  local -a values=("$NAVIGATOR_PORT" "$MYSQL_PORT" "$MOCK_LLM_PORT" "$BIZ_PORT" "$BIZ_INGRESS_PROXY_PORT" "$DIRECTORY_FACADE_PORT")
  local value
  validate_port "Navigator port" "$NAVIGATOR_PORT"
  validate_port "MySQL port" "$MYSQL_PORT"
  validate_port "Mock LLM port" "$MOCK_LLM_PORT"
  validate_port "Biz Worker port" "$BIZ_PORT"
  validate_port "Biz ingress proxy port" "$BIZ_INGRESS_PROXY_PORT"
  validate_port "directory facade port" "$DIRECTORY_FACADE_PORT"
  for value in "${values[@]}"; do
    [[ "$(printf '%s\n' "${values[@]}" | LC_ALL=C sort | uniq -d | wc -l | tr -d ' ')" == 0 ]] \
      || die "all INT-001 ports must be unique"
  done
}

port_can_bind() {
  local port="$1"
  python3 - "$port" <<'PY'
import socket
import sys

port = int(sys.argv[1])
sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
try:
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 0)
    sock.bind(("127.0.0.1", port))
finally:
    sock.close()
PY
}

port_is_used_by_other_prepared_run() {
  local candidate="$1" file other_run_dir
  local -a port_keys=(INT001_NAVIGATOR_PORT INT001_MYSQL_PORT INT001_MOCK_LLM_PORT INT001_BIZ_PORT INT001_BIZ_INGRESS_PROXY_PORT INT001_DIRECTORY_FACADE_PORT)
  local key
  local -A other_stack=()
  shopt -s nullglob
  for file in "$ARTIFACT_ROOT"/*/"$PRIVATE_DIRECTORY_NAME"/"$STACK_ENV_NAME"; do
    other_run_dir="$(dirname "$(dirname "$file")")"
    [[ "$(realpath -m "$other_run_dir")" == "$(realpath -m "$(run_dir_for "$RUN_ID")")" ]] && continue
    # Allocation can inspect another prepared run before this run has written
    # its profile. Never clobber the current lifecycle profile while doing so.
    parse_strict_env "$file" stack other_stack
    for key in "${port_keys[@]}"; do
      [[ "${other_stack[$key]}" == "$candidate" ]] && {
        shopt -u nullglob
        return 0
      }
    done
  done
  shopt -u nullglob
  return 1
}

assert_port_available() {
  local label="$1" port="$2"
  port_can_bind "$port" || die "$label port $port is already listening or cannot bind loopback"
  if port_is_used_by_other_prepared_run "$port"; then
    die "$label port $port is already reserved by another prepared INT-001 run"
  fi
  # `port_is_used_by_other_prepared_run` deliberately returns 1 when the
  # candidate is free.  Do not leak that expected negative result as this
  # helper's return status under `set -e`.
  return 0
}

allocate_port() {
  local label="$1" port attempt=0
  while (( attempt < 128 )); do
    port="$(python3 - "$DYNAMIC_PORT_MIN" "$DYNAMIC_PORT_MAX" <<'PY'
import secrets
import sys

lower = int(sys.argv[1])
upper = int(sys.argv[2])
if lower < 1025 or upper > 65535 or lower > upper:
    raise SystemExit(2)
print(lower + secrets.randbelow(upper - lower + 1))
PY
)" || die "could not choose a candidate loopback port for $label"
    if [[ "$port" != "$NAVIGATOR_PORT" && "$port" != "$MYSQL_PORT" && "$port" != "$MOCK_LLM_PORT" \
      && "$port" != "$BIZ_PORT" && "$port" != "$BIZ_INGRESS_PROXY_PORT" && "$port" != "$DIRECTORY_FACADE_PORT" ]]; then
      if validate_port "$label" "$port" && port_can_bind "$port" && ! port_is_used_by_other_prepared_run "$port"; then
        # The bind probe intentionally closes before this function returns.
        # It narrows accidental collisions but cannot reserve a host port
        # against an external racer. Compose startup and its post-start TCP
        # preflight remain fail-closed checks of the real published mapping.
        printf '%s' "$port"
        return
      fi
    fi
    ((attempt += 1))
  done
  die "could not allocate a unique loopback port for $label"
}

acquire_prepare_lock() {
  ensure_private_artifact_root
  # Lock the validated private directory itself rather than opening a mutable
  # `.prepare.lock` pathname.  This avoids a second symlink-following surface
  # while retaining a process-scoped advisory lock for concurrent prepares.
  exec 9<"$ARTIFACT_ROOT" || die "cannot open INT-001 artifact root for locking"
  flock -n 9 || die "another INT-001 prepare is in progress"
}

acquire_run_lock() {
  local run_dir="$1"
  assert_expected_run_path "$run_dir"
  assert_private_dir "$run_dir"
  # As with prepare, lock the already-validated private directory inode.  A
  # mutable `.lifecycle.lock` file could be replaced with a symlink between
  # checks and redirection; no lifecycle action needs that extra pathname.
  exec 8<"$run_dir" || die "cannot open run directory for lifecycle locking"
  flock -n 8 || die "another lifecycle command owns this runId"
}

assert_tooling() {
  assert_local_docker_target
  docker_local compose version >/dev/null 2>&1 || die "docker compose v2 is required"
  assert_launcher_java
  command -v mvn >/dev/null 2>&1 || die "mvn is required for an optional source build"
  command -v python3 >/dev/null 2>&1 || die "python3 is required for the Biz Worker and directory facade"
  command -v curl >/dev/null 2>&1 || die "curl is required for loopback health checks"
  command -v flock >/dev/null 2>&1 || die "flock is required for safe INT-001 lifecycle locking"
  [[ -x "$TRUSTED_SETSID" && ! -L "$TRUSTED_SETSID" ]] \
    || die "trusted setsid binary is required for dedicated child process groups"
  env -i "PATH=$SAFE_CHILD_PATH" "HOME=/tmp" "PYTHONPATH=$REPO_ROOT/tools/langgraph-biz-worker/src" \
    "PYTHONDONTWRITEBYTECODE=1" "$BIZ_WORKER_PYTHON" -c 'import uvicorn; import langgraph_biz_worker.main' \
    >/dev/null 2>&1 || die "repository LangGraph Biz Worker virtual environment is not runnable"
}

assert_launcher_java() {
  local resolved version_line version_value major
  [[ -x "$TRUSTED_JAVA_LINK" ]] || die "fixed Launcher Java path is required"
  resolved="$(readlink -f -- "$TRUSTED_JAVA_LINK")" || die "cannot resolve fixed Launcher Java path"
  [[ -f "$resolved" && ! -L "$resolved" && -x "$resolved" ]] \
    || die "resolved Launcher Java path is unsafe"
  version_line="$("$resolved" -version 2>&1 | sed -n '1p')" \
    || die "fixed Launcher Java version check failed"
  case "$version_line" in
    *\"*)
      version_value="${version_line#*\"}"
      version_value="${version_value%%\"*}"
      ;;
    *) die "fixed Launcher Java version cannot be parsed" ;;
  esac
  [[ "$version_value" =~ ^([0-9]+)(\.[0-9]+)*([+_-].*)?$ ]] \
    || die "fixed Launcher Java version cannot be parsed"
  major="${BASH_REMATCH[1]}"
  (( major >= 17 )) || die "fixed Launcher Java must be version 17 or newer"
  LAUNCHER_JAVA="$resolved"
}

write_stack_env() {
  local file="$1" run_dir="$2" compose_project="$3" database="$4" db_password="$5" root_password="$6"
  local volume="int001_${RUN_ID//-/_}_mysql_data"
  {
    printf 'INT001_RUN_ID=%s\n' "$RUN_ID"
    printf 'INT001_COMPOSE_PROJECT=%s\n' "$compose_project"
    printf 'INT001_NAVIGATOR_PORT=%s\n' "$NAVIGATOR_PORT"
    printf 'INT001_MYSQL_PORT=%s\n' "$MYSQL_PORT"
    printf 'INT001_MOCK_LLM_PORT=%s\n' "$MOCK_LLM_PORT"
    printf 'INT001_BIZ_PORT=%s\n' "$BIZ_PORT"
    printf 'INT001_BIZ_INGRESS_PROXY_PORT=%s\n' "$BIZ_INGRESS_PROXY_PORT"
    printf 'INT001_DIRECTORY_FACADE_PORT=%s\n' "$DIRECTORY_FACADE_PORT"
    printf 'INT001_NAVIGATOR_URL=http://127.0.0.1:%s\n' "$NAVIGATOR_PORT"
    printf 'INT001_MOCK_LLM_URL=http://127.0.0.1:%s\n' "$MOCK_LLM_PORT"
    printf 'INT001_MYSQL_DATABASE=%s\n' "$database"
    printf 'INT001_MYSQL_USER=int001\n'
    printf 'INT001_MYSQL_PASSWORD=%s\n' "$db_password"
    printf 'INT001_MYSQL_ROOT_PASSWORD=%s\n' "$root_password"
    printf 'INT001_MYSQL_VOLUME=%s\n' "$volume"
    printf 'INT001_MOCK_RESPONSES_DIR=%s\n' "$run_dir/mock-responses"
  } > "$file"
  chmod 600 "$file"
}

write_launcher_env() {
  local file="$1" run_dir="$2" database="$3" db_password="$4" system_root_password="$5"
  {
    printf 'SERVER_ADDRESS=127.0.0.1\n'
    printf 'SERVER_PORT=%s\n' "$NAVIGATOR_PORT"
    printf 'SPRING_DATASOURCE_URL=jdbc:mysql://127.0.0.1:%s/%s?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true\n' "$MYSQL_PORT" "$database"
    printf 'SPRING_DATASOURCE_USERNAME=int001\n'
    printf 'SPRING_DATASOURCE_PASSWORD=%s\n' "$db_password"
    printf 'SPRING_JPA_HIBERNATE_DDL_AUTO=update\n'
    printf 'SYSTEM_ROOT_USERNAME=%s\n' "$SYNTHETIC_SYSTEM_ROOT_USERNAME"
    printf 'SYSTEM_ROOT_PASSWORD=%s\n' "$system_root_password"
    printf 'SYSTEM_ROOT_EMAIL=int001-root@invalid.local\n'
    printf 'JWT_SECRET=%s\n' "$(random_hex 64)"
    printf 'NAVIGATOR_CREDENTIAL_KEY=%s\n' "$(random_hex 64)"
    printf 'NAVIGATOR_CREDENTIAL_SALT=%s\n' "$(random_hex 32)"
    printf 'NAVIGATOR_EXTERNAL_ENABLED=true\n'
    printf 'NAVIGATOR_WORKER_GATEWAY_EXTERNAL_ENABLED=false\n'
    printf 'BUSINESS_AGENT_DEV_SYNC_WORKER_URL=http://127.0.0.1:%s\n' "$BIZ_INGRESS_PROXY_PORT"
    printf 'BUSINESS_AGENT_DEV_SYNC_WORKER_TOKEN=\n'
    printf 'AGENT_LLM_OPENAI_BASE_URL=http://127.0.0.1:%s/v1\n' "$MOCK_LLM_PORT"
    printf 'AGENT_LLM_OPENAI_API_KEY=mock-int001\n'
    printf 'LOGGING_FILE_NAME=%s\n' "$(private_file_path "$run_dir" "$LAUNCHER_LOG_NAME")"
    printf 'SESSION_MESSAGE_PAYLOAD_STORE_DIRECTORY=%s\n' "$run_dir/session-message-payloads"
  } > "$file"
  chmod 600 "$file"
}

write_biz_worker_env() {
  local file="$1" run_dir="$2"
  {
    printf 'BIZ_WORKER_HOST=127.0.0.1\n'
    printf 'BIZ_WORKER_PORT=%s\n' "$BIZ_PORT"
    printf 'BIZ_WORKER_WORKER_NAME=int001-%s-biz\n' "$RUN_ID"
    printf 'BIZ_WORKER_WORKER_TOKEN=\n'
    printf 'BIZ_WORKER_EXTERNAL_ENABLED=false\n'
    printf 'BIZ_WORKER_NAVIGATOR_API_BASE=http://127.0.0.1:%s\n' "$NAVIGATOR_PORT"
    printf 'BIZ_WORKER_LLM_PROVIDER=openai\n'
    printf 'BIZ_WORKER_LLM_API_KEY=mock-int001\n'
    printf 'BIZ_WORKER_LLM_BASE_URL=http://127.0.0.1:%s/v1\n' "$MOCK_LLM_PORT"
    printf 'BIZ_WORKER_LLM_MODEL=int001-mock\n'
    printf 'BIZ_WORKER_LLM_EXECUTE_SKILLS=false\n'
    printf 'BIZ_WORKER_ENABLE_COMMAND=false\n'
    printf 'BIZ_WORKER_DATA_ROOT=%s\n' "$run_dir/biz-data"
    printf 'BIZ_WORKER_RUNTIME_MESSAGE_EVENT_LOG_ENABLED=false\n'
    printf 'BIZ_WORKER_LLM_SUBMISSION_LOG_ENABLED=false\n'
    printf 'BIZ_WORKER_RUNTIME_COMPACTION_LLM_ENABLED=false\n'
  } > "$file"
  chmod 600 "$file"
}

write_biz_ingress_proxy_env() {
  local file="$1" run_dir="$2"
  {
    printf 'INT001_RUN_ID=%s\n' "$RUN_ID"
    printf 'INT001_BIZ_INGRESS_PROXY_HOST=127.0.0.1\n'
    printf 'INT001_BIZ_INGRESS_PROXY_PORT=%s\n' "$BIZ_INGRESS_PROXY_PORT"
    printf 'INT001_BIZ_INGRESS_UPSTREAM_URL=http://127.0.0.1:%s\n' "$BIZ_PORT"
    printf 'INT001_BIZ_INGRESS_COUNTER_FILE=%s\n' "$run_dir/$PRIVATE_DIRECTORY_NAME/$BIZ_INGRESS_COUNTER_NAME"
    printf 'INT001_BIZ_INGRESS_LOCK_FILE=%s\n' "$run_dir/$PRIVATE_DIRECTORY_NAME/$BIZ_INGRESS_LOCK_NAME"
    printf 'INT001_BIZ_INGRESS_RUN_DIR=%s\n' "$run_dir"
  } > "$file"
  chmod 600 "$file"
}

write_facade_env() {
  local file="$1" run_dir="$2" token="$3"
  {
    printf 'INT001_RUN_ID=%s\n' "$RUN_ID"
    printf 'INT001_DIRECTORY_FACADE_HOST=127.0.0.1\n'
    printf 'INT001_DIRECTORY_FACADE_PORT=%s\n' "$DIRECTORY_FACADE_PORT"
    printf 'INT001_DIRECTORY_FACADE_ROOT=%s\n' "$run_dir/directory-workspaces"
    printf 'INT001_DIRECTORY_FACADE_TOKEN=%s\n' "$token"
  } > "$file"
  chmod 600 "$file"
}

write_bootstrap_target_env() {
  local file="$1" token="$2" system_root_password="$3"
  {
    printf 'INT001_RUN_ID=%s\n' "$RUN_ID"
    printf 'INT001_NAVIGATOR_URL=http://127.0.0.1:%s\n' "$NAVIGATOR_PORT"
    printf 'INT001_BIZ_BASE_URL=http://127.0.0.1:%s\n' "$BIZ_INGRESS_PROXY_PORT"
    printf 'INT001_DIRECTORY_FACADE_URL=http://127.0.0.1:%s\n' "$DIRECTORY_FACADE_PORT"
    # The bootstrap uses this only to create the disposable A model config.
    # It is a run-owned loopback origin and is never projected to the
    # runtime-only child.
    printf 'INT001_MOCK_LLM_URL=http://127.0.0.1:%s\n' "$MOCK_LLM_PORT"
    printf 'INT001_DIRECTORY_FACADE_TOKEN=%s\n' "$token"
    # This is only the disposable Launcher root-login password.  It remains in
    # this run-owned 0600 bootstrap carrier and is never projected to the
    # runtime-only child or durable evidence.
    printf 'INT001_BOOTSTRAP_ROOT_USERNAME=%s\n' "$SYNTHETIC_SYSTEM_ROOT_USERNAME"
    printf 'INT001_BOOTSTRAP_ROOT_PASSWORD=%s\n' "$system_root_password"
  } > "$file"
  chmod 600 "$file"
}

write_manifest() {
  local file="$1" state="$2" run_dir="$3" project="$4"
  assert_private_named_run_file_path "$run_dir" "$file" "$RUN_MANIFEST_NAME"
  if ! {
    printf '{\n'
    printf '  "schemaVersion": 2,\n'
    printf '  "runId": "%s",\n' "$RUN_ID"
    printf '  "composeProject": "%s",\n' "$project"
    printf '  "state": "%s",\n' "$state"
    printf '  "createdAtUtc": "%s",\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    printf '  "navigatorUrl": "http://127.0.0.1:%s",\n' "$NAVIGATOR_PORT"
    printf '  "mockLlmUrl": "http://127.0.0.1:%s",\n' "$MOCK_LLM_PORT"
    printf '  "bizWorkerUrl": "http://127.0.0.1:%s",\n' "$BIZ_PORT"
    printf '  "bizIngressProxyUrl": "http://127.0.0.1:%s",\n' "$BIZ_INGRESS_PROXY_PORT"
    printf '  "directoryFacadeUrl": "http://127.0.0.1:%s",\n' "$DIRECTORY_FACADE_PORT"
    printf '  "openApiExternalEnabled": true,\n'
    printf '  "workerGatewayExternalEnabled": false,\n'
    printf '  "bizWorkerExternalEnabled": false,\n'
    # This is a relative locator only.  The manifest must not retain the
    # absolute run path of a credential carrier after the lifecycle ends.
    printf '  "bootstrapTarget": "%s/%s"\n' "$PRIVATE_DIRECTORY_NAME" "$BOOTSTRAP_TARGET_PROFILE_NAME"
    printf '}\n'
  } > "$file"; then
    return 1
  fi
  chmod 600 "$file" || return 1
  assert_private_file "$file"
}

write_bootstrap_plan() {
  local file="$1" run_dir="$2"
  assert_private_named_run_file_path "$run_dir" "$file" "$BOOTSTRAP_PLAN_NAME"
  {
    printf 'INT-001 bootstrap plan (NON-EXECUTING)\n\n'
    printf 'runId=%s\n' "$RUN_ID"
    printf 'Navigator target=http://127.0.0.1:%s\n' "$NAVIGATOR_PORT"
    printf 'Directory facade target=http://127.0.0.1:%s\n' "$DIRECTORY_FACADE_PORT"
    printf 'Biz Worker target=http://127.0.0.1:%s\n' "$BIZ_PORT"
    printf 'Biz ingress proxy target=http://127.0.0.1:%s\n\n' "$BIZ_INGRESS_PROXY_PORT"
    printf 'A bootstrap implementation may read only private/bootstrap-target.env, the strict-key carrier under this run directory.\n'
    printf 'It must create a disposable same-tenant directory-only Claude Worker using the facade URL/token, then a disposable LANGGRAPH_BIZ fixture.\n'
    printf 'It must not materialize public skills, expose a Gateway, create a Codex route, join a WorkerPool, or touch real upstream profiles.\n'
    printf 'The exact static no-tool assertion is INT001_STATIC_NO_TOOL_%s.\n' "$RUN_ID"
    printf 'This plan does not authorize a service start, Navigator mutation, or credential output.\n'
  } > "$file"
  chmod 600 "$file"
}

expected_compose_project() {
  printf 'int001_%s\n' "${RUN_ID//-/_}"
}

expected_database() {
  printf 'int001_%s\n' "${RUN_ID//-/_}"
}

expected_volume() {
  printf 'int001_%s_mysql_data\n' "${RUN_ID//-/_}"
}

assert_exact_path() {
  local actual="$1" expected="$2" label="$3"
  [[ "$(realpath -m "$actual")" == "$(realpath -m "$expected")" ]] || die "$label must remain inside this run directory"
}

load_prepared_profiles() {
  local run_dir="$1" private_dir bootstrap_target
  private_dir="$run_dir/$PRIVATE_DIRECTORY_NAME"
  bootstrap_target="$private_dir/$BOOTSTRAP_TARGET_PROFILE_NAME"
  assert_private_dir "$private_dir"
  assert_no_legacy_root_private_carriers "$run_dir"
  parse_strict_env "$private_dir/$STACK_ENV_NAME" stack STACK_ENV
  parse_strict_env "$private_dir/$LAUNCHER_ENV_NAME" launcher LAUNCHER_ENV
  parse_strict_env "$private_dir/$BIZ_WORKER_ENV_NAME" biz BIZ_ENV
  parse_strict_env "$private_dir/$BIZ_INGRESS_PROXY_ENV_NAME" biz-ingress-proxy BIZ_INGRESS_PROXY_ENV
  parse_strict_env "$private_dir/$DIRECTORY_FACADE_ENV_NAME" facade FACADE_ENV
  parse_strict_env "$bootstrap_target" bootstrap BOOTSTRAP_ENV

  [[ "${STACK_ENV[INT001_RUN_ID]}" == "$RUN_ID" ]] || die "stack.env belongs to another runId"
  [[ "${FACADE_ENV[INT001_RUN_ID]}" == "$RUN_ID" && "${BOOTSTRAP_ENV[INT001_RUN_ID]}" == "$RUN_ID" ]] \
    || die "private facade/bootstrap config belongs to another runId"
  [[ "${STACK_ENV[INT001_COMPOSE_PROJECT]}" == "$(expected_compose_project)" ]] || die "Compose project ownership is invalid"
  [[ "${STACK_ENV[INT001_MYSQL_DATABASE]}" == "$(expected_database)" ]] || die "database name ownership is invalid"
  [[ "${STACK_ENV[INT001_MYSQL_VOLUME]}" == "$(expected_volume)" ]] || die "volume ownership is invalid"

  NAVIGATOR_PORT="${STACK_ENV[INT001_NAVIGATOR_PORT]}"
  MYSQL_PORT="${STACK_ENV[INT001_MYSQL_PORT]}"
  MOCK_LLM_PORT="${STACK_ENV[INT001_MOCK_LLM_PORT]}"
  BIZ_PORT="${STACK_ENV[INT001_BIZ_PORT]}"
  BIZ_INGRESS_PROXY_PORT="${STACK_ENV[INT001_BIZ_INGRESS_PROXY_PORT]}"
  DIRECTORY_FACADE_PORT="${STACK_ENV[INT001_DIRECTORY_FACADE_PORT]}"
  validate_port_set

  [[ "${STACK_ENV[INT001_NAVIGATOR_URL]}" == "http://127.0.0.1:$NAVIGATOR_PORT" ]] || die "Navigator URL must be loopback-only"
  [[ "${STACK_ENV[INT001_MOCK_LLM_URL]}" == "http://127.0.0.1:$MOCK_LLM_PORT" ]] || die "Mock LLM URL must be loopback-only"
  [[ "${LAUNCHER_ENV[SERVER_ADDRESS]}" == 127.0.0.1 && "${LAUNCHER_ENV[SERVER_PORT]}" == "$NAVIGATOR_PORT" ]] \
    || die "Launcher must bind only its allocated loopback port"
  [[ "${LAUNCHER_ENV[NAVIGATOR_EXTERNAL_ENABLED]}" == true ]] || die "disposable Open API route gate must be explicitly true"
  [[ "${LAUNCHER_ENV[NAVIGATOR_WORKER_GATEWAY_EXTERNAL_ENABLED]}" == false ]] || die "Worker Gateway external must remain false"
  [[ "${LAUNCHER_ENV[BUSINESS_AGENT_DEV_SYNC_WORKER_TOKEN]}" == "" ]] || die "sync Worker token must be explicitly empty"
  [[ "${LAUNCHER_ENV[BUSINESS_AGENT_DEV_SYNC_WORKER_URL]}" == "http://127.0.0.1:$BIZ_INGRESS_PROXY_PORT" ]] \
    || die "Launcher Biz sync URL must target this run's ingress proxy"
  [[ "${LAUNCHER_ENV[AGENT_LLM_OPENAI_BASE_URL]}" == "http://127.0.0.1:$MOCK_LLM_PORT/v1" ]] || die "Launcher Mock LLM override must use this run's loopback port"
  [[ "${BIZ_ENV[BIZ_WORKER_HOST]}" == 127.0.0.1 && "${BIZ_ENV[BIZ_WORKER_PORT]}" == "$BIZ_PORT" ]] \
    || die "Biz Worker must bind only its allocated loopback port"
  [[ "${BIZ_ENV[BIZ_WORKER_WORKER_TOKEN]}" == "" ]] || die "Biz Worker token must be explicitly empty"
  [[ "${BIZ_ENV[BIZ_WORKER_EXTERNAL_ENABLED]}" == false ]] || die "Biz Worker external must remain false"
  [[ "${BIZ_ENV[BIZ_WORKER_NAVIGATOR_API_BASE]}" == "http://127.0.0.1:$NAVIGATOR_PORT" ]] || die "Biz Navigator URL must be loopback-only"
  [[ "${BIZ_ENV[BIZ_WORKER_LLM_BASE_URL]}" == "http://127.0.0.1:$MOCK_LLM_PORT/v1" ]] || die "Biz Mock LLM URL must be loopback-only"
  [[ "${BIZ_ENV[BIZ_WORKER_RUNTIME_MESSAGE_EVENT_LOG_ENABLED]}" == false \
    && "${BIZ_ENV[BIZ_WORKER_LLM_SUBMISSION_LOG_ENABLED]}" == false \
    && "${BIZ_ENV[BIZ_WORKER_RUNTIME_COMPACTION_LLM_ENABLED]}" == false ]] \
    || die "Biz diagnostic persistence/compaction must be disabled for INT-001"
  [[ "${BIZ_INGRESS_PROXY_ENV[INT001_RUN_ID]}" == "$RUN_ID" \
    && "${BIZ_INGRESS_PROXY_ENV[INT001_BIZ_INGRESS_PROXY_HOST]}" == 127.0.0.1 \
    && "${BIZ_INGRESS_PROXY_ENV[INT001_BIZ_INGRESS_PROXY_PORT]}" == "$BIZ_INGRESS_PROXY_PORT" \
    && "${BIZ_INGRESS_PROXY_ENV[INT001_BIZ_INGRESS_UPSTREAM_URL]}" == "http://127.0.0.1:$BIZ_PORT" \
    && "${BIZ_INGRESS_PROXY_ENV[INT001_BIZ_INGRESS_COUNTER_FILE]}" == "$private_dir/$BIZ_INGRESS_COUNTER_NAME" \
    && "${BIZ_INGRESS_PROXY_ENV[INT001_BIZ_INGRESS_LOCK_FILE]}" == "$private_dir/$BIZ_INGRESS_LOCK_NAME" \
    && "${BIZ_INGRESS_PROXY_ENV[INT001_BIZ_INGRESS_RUN_DIR]}" == "$run_dir" ]] \
    || die "Biz ingress proxy config must contain only this run's loopback/private values"
  [[ "${FACADE_ENV[INT001_DIRECTORY_FACADE_HOST]}" == 127.0.0.1 \
    && "${FACADE_ENV[INT001_DIRECTORY_FACADE_PORT]}" == "$DIRECTORY_FACADE_PORT" ]] \
    || die "directory facade must bind only its allocated loopback port"
  [[ "${BOOTSTRAP_ENV[INT001_NAVIGATOR_URL]}" == "http://127.0.0.1:$NAVIGATOR_PORT" \
    && "${BOOTSTRAP_ENV[INT001_BIZ_BASE_URL]}" == "http://127.0.0.1:$BIZ_INGRESS_PROXY_PORT" \
    && "${BOOTSTRAP_ENV[INT001_DIRECTORY_FACADE_URL]}" == "http://127.0.0.1:$DIRECTORY_FACADE_PORT" \
    && "${BOOTSTRAP_ENV[INT001_MOCK_LLM_URL]}" == "http://127.0.0.1:$MOCK_LLM_PORT" ]] \
    || die "bootstrap target must contain only this run's loopback URLs"
  [[ "${BOOTSTRAP_ENV[INT001_DIRECTORY_FACADE_TOKEN]}" == "${FACADE_ENV[INT001_DIRECTORY_FACADE_TOKEN]}" ]] \
    || die "bootstrap facade credential does not match the run-owned facade"
  [[ "${BOOTSTRAP_ENV[INT001_BOOTSTRAP_ROOT_USERNAME]}" == "${LAUNCHER_ENV[SYSTEM_ROOT_USERNAME]}" ]] \
    || die "bootstrap root username does not match the run-owned Launcher"
  [[ "${BOOTSTRAP_ENV[INT001_BOOTSTRAP_ROOT_PASSWORD]}" == "${LAUNCHER_ENV[SYSTEM_ROOT_PASSWORD]}" ]] \
    || die "bootstrap root credential does not match the run-owned Launcher"

  assert_exact_path "${STACK_ENV[INT001_MOCK_RESPONSES_DIR]}" "$run_dir/mock-responses" "Mock response directory"
  assert_exact_path "${LAUNCHER_ENV[LOGGING_FILE_NAME]}" "$private_dir/$LAUNCHER_LOG_NAME" "Launcher log path"
  assert_exact_path "${LAUNCHER_ENV[SESSION_MESSAGE_PAYLOAD_STORE_DIRECTORY]}" "$run_dir/session-message-payloads" "session payload path"
  assert_exact_path "${BIZ_ENV[BIZ_WORKER_DATA_ROOT]}" "$run_dir/biz-data" "Biz data root"
  assert_exact_path "${FACADE_ENV[INT001_DIRECTORY_FACADE_ROOT]}" "$run_dir/directory-workspaces" "directory facade root"
}

assert_generated_response() {
  local run_dir="$1"
  local response="$run_dir/mock-responses/static-no-tool.yaml"
  assert_private_file "$response"
  grep -Fqx "      content: \"INT001_STATIC_NO_TOOL_$RUN_ID\"" "$response" \
    || die "generated Mock LLM response must contain the exact static no-tool marker"
}

manifest_state() {
  local manifest="$1" state
  assert_private_file "$manifest"
  state="$(sed -nE 's/^[[:space:]]*"state": "([A-Z_]+)"[,]?$/\1/p' "$manifest")"
  [[ "$(printf '%s\n' "$state" | wc -l | tr -d ' ')" == 1 ]] || die "run manifest state is malformed"
  case "$state" in PREPARED|RUNNING|BOOTSTRAPPED|AUDITED|CLEANED|FAILED_CLEANUP) ;; *) die "run manifest has unknown state" ;; esac
  printf '%s' "$state"
}

assert_ports_unused() {
  assert_port_available "Navigator" "$NAVIGATOR_PORT"
  assert_port_available "MySQL" "$MYSQL_PORT"
  assert_port_available "Mock LLM" "$MOCK_LLM_PORT"
  assert_port_available "Biz Worker" "$BIZ_PORT"
  assert_port_available "Biz ingress proxy" "$BIZ_INGRESS_PROXY_PORT"
  assert_port_available "directory facade" "$DIRECTORY_FACADE_PORT"
}

doctor_prepared_run() {
  local run_dir="$1" project private_dir manifest
  private_dir="$run_dir/$PRIVATE_DIRECTORY_NAME"
  manifest="$private_dir/$RUN_MANIFEST_NAME"
  assert_expected_run_path "$run_dir"
  assert_private_dir "$run_dir"
  assert_no_legacy_root_private_carriers "$run_dir"
  [[ "$(manifest_state "$manifest")" == PREPARED ]] || die "doctor only accepts a PREPARED run; cleanup stale/running runs first"
  load_prepared_profiles "$run_dir"
  assert_generated_response "$run_dir"
  assert_ports_unused
  assert_tooling
  project="${STACK_ENV[INT001_COMPOSE_PROJECT]}"
  assert_no_fresh_docker_resources "$project" \
    || die "fresh INT-001 run collides with existing Docker resources; refusing Compose startup"
  docker_compose_for_run "$run_dir" config --quiet \
    || die "Compose syntax/value validation failed"
  note "doctor=PASS runId=$RUN_ID target=http://127.0.0.1:$NAVIGATOR_PORT composeProject=${STACK_ENV[INT001_COMPOSE_PROJECT]}"
}

prepare_run() {
  local run_dir private_dir stack_env launcher_env biz_env biz_ingress_proxy_env facade_env bootstrap_env manifest compose_project database
  local db_password mysql_root_password system_root_password facade_token
  acquire_prepare_lock
  assert_tooling
  assert_ignored_artifact_root
  if [[ -z "$RUN_ID" ]]; then
    RUN_ID="$(generate_run_id)"
  fi
  validate_run_id
  run_dir="$(run_dir_for "$RUN_ID")"
  assert_expected_run_path "$run_dir"
  [[ ! -e "$run_dir" ]] || die "run directory already exists; use a new runId"

  [[ -n "$NAVIGATOR_PORT" ]] || NAVIGATOR_PORT="$(allocate_port Navigator)"
  [[ -n "$MYSQL_PORT" ]] || MYSQL_PORT="$(allocate_port MySQL)"
  [[ -n "$MOCK_LLM_PORT" ]] || MOCK_LLM_PORT="$(allocate_port MockLLM)"
  [[ -n "$BIZ_PORT" ]] || BIZ_PORT="$(allocate_port BizWorker)"
  [[ -n "$BIZ_INGRESS_PROXY_PORT" ]] || BIZ_INGRESS_PROXY_PORT="$(allocate_port BizIngressProxy)"
  [[ -n "$DIRECTORY_FACADE_PORT" ]] || DIRECTORY_FACADE_PORT="$(allocate_port DirectoryFacade)"
  validate_port_set
  assert_ports_unused

  mkdir -m 700 "$run_dir"
  # From this point a failed prepare may have generated credential carriers.
  # Arm the local-only EXIT scrub before creating any profile or secret.
  CLEANUP_SCRUB_RUN_DIR="$run_dir"
  CLEANUP_SCRUB_COMPLETED=0
  # Any unexpected post-create exit must retain a conservative, non-secret
  # diagnosis rather than the ambiguous default `NONE` receipt stage.
  set_lifecycle_failure_stage PREPARE
  mkdir -m 700 "$run_dir/$PRIVATE_DIRECTORY_NAME" "$run_dir/mock-responses" "$run_dir/biz-data" "$run_dir/directory-workspaces" \
    "$run_dir/session-message-payloads" "$run_dir/home" "$run_dir/children"
  compose_project="$(expected_compose_project)"
  database="$(expected_database)"
  db_password="$(random_hex 48)"
  mysql_root_password="$(random_hex 48)"
  system_root_password="$(random_hex 48)"
  [[ "$mysql_root_password" != "$system_root_password" ]] \
    || die "generated MySQL and Navigator system-root credentials must be distinct"
  facade_token="$(random_hex 64)"
  private_dir="$run_dir/$PRIVATE_DIRECTORY_NAME"
  stack_env="$private_dir/$STACK_ENV_NAME"
  launcher_env="$private_dir/$LAUNCHER_ENV_NAME"
  biz_env="$private_dir/$BIZ_WORKER_ENV_NAME"
  biz_ingress_proxy_env="$private_dir/$BIZ_INGRESS_PROXY_ENV_NAME"
  facade_env="$private_dir/$DIRECTORY_FACADE_ENV_NAME"
  bootstrap_env="$private_dir/$BOOTSTRAP_TARGET_PROFILE_NAME"
  manifest="$private_dir/$RUN_MANIFEST_NAME"

  write_stack_env "$stack_env" "$run_dir" "$compose_project" "$database" "$db_password" "$mysql_root_password"
  write_launcher_env "$launcher_env" "$run_dir" "$database" "$db_password" "$system_root_password"
  write_biz_worker_env "$biz_env" "$run_dir"
  write_biz_ingress_proxy_env "$biz_ingress_proxy_env" "$run_dir"
  write_facade_env "$facade_env" "$run_dir" "$facade_token"
  write_bootstrap_target_env "$bootstrap_env" "$facade_token" "$system_root_password"
  sed "s/__INT001_RUN_ID__/$RUN_ID/g" "$RESPONSE_TEMPLATE" > "$run_dir/mock-responses/static-no-tool.yaml"
  chmod 600 "$run_dir/mock-responses/static-no-tool.yaml"
  write_manifest "$manifest" PREPARED "$run_dir" "$compose_project"
  doctor_prepared_run "$run_dir"
  CLEANUP_SCRUB_COMPLETED=1
  CLEANUP_SCRUB_RUN_DIR=""
  note "prepare=PASS runId=$RUN_ID artifacts=$run_dir"
}

assert_running_run() {
  local run_dir="$1" expected_state="$2" state project private_dir
  assert_expected_run_path "$run_dir"
  assert_private_dir "$run_dir"
  private_dir="$run_dir/$PRIVATE_DIRECTORY_NAME"
  assert_no_legacy_root_private_carriers "$run_dir"
  state="$(manifest_state "$private_dir/$RUN_MANIFEST_NAME")"
  [[ "$state" == "$expected_state" ]] \
    || die "this lifecycle action requires a $expected_state run; current state is $state"
  load_prepared_profiles "$run_dir"
  assert_generated_response "$run_dir"
  assert_tooling
  project="${STACK_ENV[INT001_COMPOSE_PROJECT]}"
  assert_all_docker_resources_owned "$project"
  validate_owned_child "$run_dir" launcher launcher-1.0.0-SNAPSHOT.jar \
    || die "run-owned Launcher is not alive"
  validate_owned_child "$run_dir" biz-worker langgraph_biz_worker.main:app \
    || die "run-owned Biz Worker is not alive"
  validate_owned_child "$run_dir" biz-ingress-proxy biz_ingress_proxy.py \
    || die "run-owned Biz ingress proxy is not alive"
  validate_owned_child "$run_dir" directory-facade directory_facade.py \
    || die "run-owned directory facade is not alive"
  assert_private_dir "$run_dir/$PRIVATE_DIRECTORY_NAME"
  # The proxy atomically replaces the dynamic ingress counter under this
  # stable lock. `verify-running` proves lifecycle/health only; the runtime
  # audit is the sole counter consumer and validates its path/FD/value while
  # holding the shared lock. Never restore a lock-free counter precheck here.
  assert_private_file "$run_dir/$PRIVATE_DIRECTORY_NAME/$BIZ_INGRESS_LOCK_NAME"
  wait_for_http "$run_dir" "http://127.0.0.1:$NAVIGATOR_PORT/actuator/health" "Launcher" \
    || die "run-owned Launcher health failed"
  wait_for_http "$run_dir" "http://127.0.0.1:$BIZ_PORT/health" "Biz Worker" \
    || die "run-owned Biz Worker health failed"
  wait_for_http "$run_dir" "http://127.0.0.1:$BIZ_INGRESS_PROXY_PORT/health" "Biz ingress proxy" \
    || die "run-owned Biz ingress proxy health failed"
  wait_for_http "$run_dir" "http://127.0.0.1:$DIRECTORY_FACADE_PORT/health" "directory facade" \
    || die "run-owned directory facade health failed"
  wait_for_http "$run_dir" "http://127.0.0.1:$MOCK_LLM_PORT/admin/health" "Mock LLM" \
    || die "run-owned Mock LLM health failed"
}

assert_runtime_child_projection() {
  local run_dir="$1" projection="$run_dir/$PRIVATE_DIRECTORY_NAME/$RUNTIME_CHILD_PROFILE_NAME"
  assert_private_dir "$run_dir/$PRIVATE_DIRECTORY_NAME"
  parse_strict_env "$projection" runtime-child RUNTIME_CHILD_ENV
  [[ "${RUNTIME_CHILD_ENV[INT001_SYNTHETIC_UPSTREAM_HARNESS]}" == true ]] \
    || die "runtime child projection must require the synthetic opt-in"
  [[ "${RUNTIME_CHILD_ENV[INT001_RUN_ID]}" == "$RUN_ID" ]] \
    || die "runtime child projection belongs to another runId"
  [[ "${RUNTIME_CHILD_ENV[INT001_NAVI_BASE_URL]}" == "http://127.0.0.1:$NAVIGATOR_PORT" ]] \
    || die "runtime child projection must target only this run's Navigator URL"
  [[ "${RUNTIME_CHILD_ENV[INT001_A_TENANT_ID]}" != "${RUNTIME_CHILD_ENV[INT001_A_CLIENT_APP_ID]}" \
    && "${RUNTIME_CHILD_ENV[INT001_A_CLIENT_APP_ID]}" != "${RUNTIME_CHILD_ENV[INT001_A_AGENT_ID]}" \
    && "${RUNTIME_CHILD_ENV[INT001_A_AGENT_ID]}" != "${RUNTIME_CHILD_ENV[INT001_B_AGENT_ID]}" \
    && "${RUNTIME_CHILD_ENV[INT001_A_AGENT_ID]}" != "${RUNTIME_CHILD_ENV[INT001_C_AGENT_ID]}" \
    && "${RUNTIME_CHILD_ENV[INT001_B_AGENT_ID]}" != "${RUNTIME_CHILD_ENV[INT001_C_AGENT_ID]}" ]] \
    || die "runtime child fixture identifiers must remain distinct"
}

has_inherited_lifecycle_lock() {
  local run_dir="$1" expected actual
  # Bootstrap is invoked while the parent lifecycle keeps FD 8 locked.  The
  # verifier inherits that descriptor, so it must not try to take a second
  # independent lock that would conflict with its own parent.
  [[ -d /proc/self/fd/8 ]] || return 1
  expected="$(realpath -m -- "$run_dir")" || return 1
  actual="$(readlink -f -- /proc/self/fd/8 2>/dev/null)" || return 1
  [[ "$actual" == "$expected" ]] || return 1
  flock -n -x 8
}

verify_running_run() {
  local run_dir
  validate_run_id
  run_dir="$(run_dir_for "$RUN_ID")"
  assert_expected_run_path "$run_dir"
  # Direct read-only verification serializes against a lifecycle.  The
  # bootstrap handoff inherits the already-held run lock instead.
  if ! has_inherited_lifecycle_lock "$run_dir"; then
    acquire_run_lock "$run_dir"
  fi
  assert_running_run "$run_dir" RUNNING
  note "verify-running=PASS runId=$RUN_ID"
}

bootstrap_run() {
  local run_dir project
  validate_run_id
  run_dir="$(run_dir_for "$RUN_ID")"
  assert_expected_run_path "$run_dir"
  acquire_run_lock "$run_dir"
  arm_lifecycle_signal_cleanup "$run_dir"
  set_lifecycle_failure_stage PREFLIGHT
  if ! (trap - EXIT; assert_running_run "$run_dir" RUNNING); then
    fail_lifecycle_stage "$run_dir" PREFLIGHT "synthetic bootstrap preflight failed; owned runtime resources were sent through fail-closed cleanup"
  fi
  set_lifecycle_failure_stage BOOTSTRAP
  load_prepared_profiles "$run_dir"
  project="${STACK_ENV[INT001_COMPOSE_PROJECT]}"
  [[ -f "$BOOTSTRAP_HELPER" && ! -L "$BOOTSTRAP_HELPER" ]] \
    || fail_lifecycle_stage "$run_dir" BOOTSTRAP "synthetic upstream bootstrap helper became unsafe"
  # Bootstrap receives its complete target carrier from the run-owned 0600
  # file.  Do not inherit an operator shell, profile, credential, or Docker
  # setting into a mutation-capable helper.
  if ! env -i "PATH=$SAFE_CHILD_PATH" "HOME=$run_dir/home" \
    "$TRUSTED_BASH" -p "$BOOTSTRAP_HELPER" --run-dir "$run_dir" --allow-create; then
    fail_lifecycle_stage "$run_dir" BOOTSTRAP "synthetic bootstrap failed; owned runtime resources were sent through fail-closed cleanup"
  fi
  if ! (trap - EXIT; assert_runtime_child_projection "$run_dir"); then
    fail_lifecycle_stage "$run_dir" BOOTSTRAP "synthetic bootstrap projection validation failed; owned runtime resources were sent through fail-closed cleanup"
  fi
  set_lifecycle_failure_stage MANIFEST
  if ! write_manifest "$(private_file_path "$run_dir" "$RUN_MANIFEST_NAME")" BOOTSTRAPPED "$run_dir" "$project"; then
    fail_lifecycle_stage "$run_dir" MANIFEST "synthetic bootstrap manifest write failed; owned runtime resources were sent through fail-closed cleanup"
  fi
  disarm_lifecycle_signal_cleanup "$run_dir"
  note "bootstrap=PASS runId=$RUN_ID runtimeProjection=private/runtime-child.env"
}

audit_run() {
  local run_dir project
  validate_run_id
  run_dir="$(run_dir_for "$RUN_ID")"
  assert_expected_run_path "$run_dir"
  acquire_run_lock "$run_dir"
  arm_lifecycle_signal_cleanup "$run_dir"
  set_lifecycle_failure_stage PREFLIGHT
  if ! (trap - EXIT; assert_running_run "$run_dir" BOOTSTRAPPED); then
    fail_lifecycle_stage "$run_dir" PREFLIGHT "synthetic runtime audit preflight failed; owned runtime resources were sent through fail-closed cleanup"
  fi
  set_lifecycle_failure_stage AUDIT
  load_prepared_profiles "$run_dir"
  if ! (trap - EXIT; assert_runtime_child_projection "$run_dir"); then
    fail_lifecycle_stage "$run_dir" AUDIT "synthetic runtime projection validation failed; owned runtime resources were sent through fail-closed cleanup"
  fi
  project="${STACK_ENV[INT001_COMPOSE_PROJECT]}"
  # Audit is a separate runtime-only process.  It gets neither this
  # lifecycle's environment nor a caller-selected shell startup file.
  if ! env -i "PATH=$SAFE_CHILD_PATH" "HOME=$run_dir/home" \
    "$TRUSTED_BASH" -p "$RUNTIME_AUDIT" --run-dir "$run_dir" --allow-execute; then
    fail_lifecycle_stage "$run_dir" AUDIT "synthetic runtime audit failed; owned runtime resources were sent through fail-closed cleanup"
  fi
  set_lifecycle_failure_stage MANIFEST
  if ! write_manifest "$(private_file_path "$run_dir" "$RUN_MANIFEST_NAME")" AUDITED "$run_dir" "$project"; then
    fail_lifecycle_stage "$run_dir" MANIFEST "synthetic runtime audit manifest write failed; owned runtime resources were sent through fail-closed cleanup"
  fi
  disarm_lifecycle_signal_cleanup "$run_dir"
  note "audit=PASS runId=$RUN_ID runtimeLane=verified"
}

environment_array_from_profile() {
  local profile_name="$1" target_name="$2" key
  local -n profile="$profile_name"
  local -n target="$target_name"
  target=()
  while IFS= read -r key; do
    target+=("$key=${profile[$key]}")
  done < <(schema_keys launcher)
}

pid_start_ticks() {
  local pid="$1"
  [[ -r "/proc/$pid/stat" ]] || return 1
  awk '{print $22}' "/proc/$pid/stat"
}

write_child_meta() {
  local run_dir="$1" name="$2" pid="$3" fragment="$4"
  local meta="$run_dir/children/$name.pid"
  local start pgid sid cwd
  start="$(pid_start_ticks "$pid")" || {
    note "cannot record start ticks for $name"
    return 1
  }
  pgid="$(ps -o pgid= -p "$pid" | tr -d ' ')" || {
    note "cannot inspect process group for $name"
    return 1
  }
  sid="$(ps -o sid= -p "$pid" | tr -d ' ')" || {
    note "cannot inspect session for $name"
    return 1
  }
  cwd="$(readlink -f "/proc/$pid/cwd")" || {
    note "cannot inspect cwd for $name"
    return 1
  }
  if [[ "$pgid" != "$pid" || "$sid" != "$pid" ]]; then
    note "$name did not receive a dedicated process group/session"
    return 1
  fi
  if [[ "$cwd" != "$(realpath -m "$run_dir")" ]]; then
    note "$name did not start in its run-owned cwd"
    return 1
  fi
  {
    printf 'INT001_CHILD_NAME=%s\n' "$name"
    printf 'PID=%s\n' "$pid"
    printf 'START_TICKS=%s\n' "$start"
    printf 'PGID=%s\n' "$pgid"
    printf 'SID=%s\n' "$sid"
    printf 'CWD=%s\n' "$cwd"
    printf 'COMMAND_FRAGMENT=%s\n' "$fragment"
  } > "$meta" || {
    note "cannot write ownership metadata for $name"
    return 1
  }
  chmod 600 "$meta" || {
    rm -f -- "$meta"
    note "cannot protect ownership metadata for $name"
    return 1
  }
}

start_child() {
  local run_dir="$1" name="$2" fragment="$3" log="$4"
  local pid pgid sid
  shift 4
  case "$(basename -- "$log")" in
    "$LAUNCHER_PROCESS_LOG_NAME"|"$BIZ_WORKER_LOG_NAME"|"$BIZ_INGRESS_PROXY_LOG_NAME"|"$DIRECTORY_FACADE_LOG_NAME") ;;
    *)
      note "refusing an unapproved child log name for $name"
      return 1
      ;;
  esac
  assert_private_named_run_file_path "$run_dir" "$log" "$(basename -- "$log")"
  [[ ! -e "$log" && ! -L "$log" ]] || {
    note "refusing to overwrite an existing or symlinked log for $name"
    return 1
  }
  if ! (set -o noclobber; : > "$log") 2>/dev/null; then
    note "cannot create log for $name"
    return 1
  fi
  chmod 600 "$log" || {
    note "cannot protect log for $name"
    return 1
  }
  assert_private_file "$log" || {
    note "child log is unsafe for $name"
    return 1
  }
  (
    cd "$run_dir" || exit 125
    # `flock` is held by the parent lifecycle process on FD 8 (run) or FD 9
    # (prepare). A long-lived fixture has no reason to retain either lock.
    # Close only this forked copy before exec: closing it prevents a successful
    # `run` from leaving its lock held through a Worker/Launcher child, while
    # the parent keeps its own descriptor and therefore its serialization.
    exec 8>&- 9>&-
    exec "$TRUSTED_SETSID" "$@"
  ) >> "$log" 2>&1 &
  pid=$!
  # Give setsid one scheduling turn before checking the dedicated session.
  sleep 0.1
  if ! kill -0 "$pid" 2>/dev/null; then
    note "$name exited before ownership metadata could be recorded"
    return "$START_CHILD_EXITED_BEFORE_METADATA"
  fi
  if ! write_child_meta "$run_dir" "$name" "$pid" "$fragment"; then
    # This is a direct child just spawned by this invocation. Prefer its
    # dedicated group only when it was actually established; otherwise stop
    # only the known PID and never issue an unproven broad kill.
    pgid="$(ps -o pgid= -p "$pid" 2>/dev/null | tr -d ' ' || true)"
    sid="$(ps -o sid= -p "$pid" 2>/dev/null | tr -d ' ' || true)"
    if [[ "$pgid" == "$pid" && "$sid" == "$pid" ]]; then
      kill -TERM -- "-$pid" 2>/dev/null || true
    else
      kill -TERM "$pid" 2>/dev/null || true
    fi
    wait "$pid" 2>/dev/null || true
    return 1
  fi
}

wait_for_http() {
  local run_dir="$1" url="$2" label="$3" max_attempts="${4:-60}" attempts=0
  local home="$run_dir/home"
  [[ "$max_attempts" =~ ^[1-9][0-9]*$ ]] || return 1
  assert_private_dir "$home" || {
    note "cannot use an unsafe run-owned HOME for $label health check"
    return 1
  }
  while (( attempts < max_attempts )); do
    # A loopback URL alone is not enough: curl may consume a user .curlrc or
    # proxy variables.  Keep the check in a fresh environment and force a
    # direct connection so a proxy can neither relay nor spoof local health.
    if env -i "PATH=$SAFE_CHILD_PATH" "HOME=$home" \
      curl --disable --noproxy '*' --fail --silent --show-error --connect-timeout 2 --max-time 2 -o /dev/null "$url"; then
      return 0
    fi
    sleep 1 || return 1
    ((attempts += 1))
  done
  note "$label did not become healthy on its loopback endpoint"
  return 1
}

# Docker Compose health checks happen inside the Compose network. They do not
# prove that Docker exposed the service through the host's loopback publish.
# This check deliberately opens only a TCP connection: it sends no database
# credential, SQL, HTTP request, or application payload.
wait_for_loopback_tcp() {
  local run_dir="$1" label="$2" port="$3" max_attempts="${4:-12}" attempts=0
  local home="$run_dir/home"
  [[ "$max_attempts" =~ ^[1-9][0-9]*$ ]] || return 1
  assert_private_dir "$home" || {
    note "cannot use an unsafe run-owned HOME for $label TCP preflight"
    return 1
  }
  while (( attempts < max_attempts )); do
    if env -i "PATH=$SAFE_CHILD_PATH" "HOME=$home" \
      python3 - "$port" >/dev/null 2>&1 <<'PY'
import socket
import sys

port = int(sys.argv[1])
with socket.create_connection(("127.0.0.1", port), timeout=2):
    pass
PY
    then
      return 0
    fi
    sleep 1 || return 1
    ((attempts += 1))
  done
  note "$label is not reachable through its loopback TCP publish"
  return 1
}

verify_compose_loopback_tcp() {
  local run_dir="$1"
  wait_for_loopback_tcp "$run_dir" "MySQL" "$MYSQL_PORT" \
    || return 1
  wait_for_loopback_tcp "$run_dir" "Mock LLM" "$MOCK_LLM_PORT" \
    || return 1
  return 0
}

# Launcher startup deserves a tighter readiness contract than the lightweight
# fixture services.  A failed curl alone must not cause a blind 180-second
# wait when the owned Java child has already exited, and a changed ownership
# proof must stop the lifecycle rather than be treated as an ordinary timeout.
# The durable result is deliberately one fixed enum written only to the root
# cleanup receipt; it never preserves any private Launcher diagnostic output.
wait_for_launcher_readiness() {
  local run_dir="$1" url="$2" max_attempts="${3:-180}" attempts=0
  local home="$run_dir/home" ownership_state
  [[ "$max_attempts" =~ ^[1-9][0-9]*$ ]] || return 1
  assert_private_dir "$home" || {
    note "cannot use an unsafe run-owned HOME for Launcher health check"
    return 1
  }
  while (( attempts < max_attempts )); do
    if probe_owned_child "$run_dir" launcher launcher-1.0.0-SNAPSHOT.jar; then
      :
    else
      ownership_state="$?"
      case "$ownership_state" in
        10)
          LAUNCHER_READINESS_OBSERVATION='CHILD_EXITED_BEFORE_HEALTH'
          note "Launcher child exited before health"
          return 1
          ;;
        20|21|22)
          LAUNCHER_READINESS_OBSERVATION='CHILD_OWNERSHIP_UNPROVEN'
          LAUNCHER_FAILURE_CLASS='OWNERSHIP_UNPROVEN'
          note "Launcher child ownership cannot be proven during health wait"
          return 1
          ;;
        *)
          LAUNCHER_READINESS_OBSERVATION='CHILD_OWNERSHIP_UNPROVEN'
          LAUNCHER_FAILURE_CLASS='OWNERSHIP_UNPROVEN'
          note "Launcher child ownership cannot be proven during health wait"
          return 1
          ;;
      esac
    fi
    # As with the generic fixture check, use a fresh direct-only client so a
    # caller profile or proxy cannot turn an external response into readiness.
    if env -i "PATH=$SAFE_CHILD_PATH" "HOME=$home" \
      curl --disable --noproxy '*' --fail --silent --show-error --connect-timeout 2 --max-time 2 -o /dev/null "$url"; then
      LAUNCHER_READINESS_OBSERVATION='HEALTH_READY'
      return 0
    fi
    sleep 1 || return 1
    ((attempts += 1))
  done
  # Check once more after the final failed curl so an exit at the timeout edge
  # is classified as a child failure rather than a misleading live timeout.
  if probe_owned_child "$run_dir" launcher launcher-1.0.0-SNAPSHOT.jar; then
    :
  else
    ownership_state="$?"
    case "$ownership_state" in
      10)
        LAUNCHER_READINESS_OBSERVATION='CHILD_EXITED_BEFORE_HEALTH'
        note "Launcher child exited before health"
        return 1
        ;;
      *)
        LAUNCHER_READINESS_OBSERVATION='CHILD_OWNERSHIP_UNPROVEN'
        LAUNCHER_FAILURE_CLASS='OWNERSHIP_UNPROVEN'
        note "Launcher child ownership cannot be proven during health wait"
        return 1
        ;;
    esac
  fi
  LAUNCHER_READINESS_OBSERVATION='CHILD_ALIVE_AT_HEALTH_TIMEOUT'
  LAUNCHER_FAILURE_CLASS='HEALTH_TIMEOUT'
  note "Launcher health timed out while its owned child remained alive"
  return 1
}

classify_launcher_failure() {
  # Do not inspect any private carrier, including a run-owned Launcher log.
  # The root cleanup receipt may describe only lifecycle observations already
  # known to the harness.  A child exit before health is deliberately UNKNOWN;
  # callers needing diagnostics must reproduce locally through an explicitly
  # authorized, private debugging workflow rather than use this receipt as a
  # log classifier.
  case "$LAUNCHER_READINESS_OBSERVATION" in
    START_FAILED)
      LAUNCHER_FAILURE_CLASS='START_EXEC_FAILURE'
      return 0
      ;;
    CHILD_OWNERSHIP_UNPROVEN)
      LAUNCHER_FAILURE_CLASS='OWNERSHIP_UNPROVEN'
      return 0
      ;;
    CHILD_ALIVE_AT_HEALTH_TIMEOUT)
      LAUNCHER_FAILURE_CLASS='HEALTH_TIMEOUT'
      return 0
      ;;
    CHILD_EXITED_BEFORE_HEALTH)
      LAUNCHER_FAILURE_CLASS='UNKNOWN'
      return 0
      ;;
    *)
      LAUNCHER_FAILURE_CLASS='NOT_APPLICABLE'
      return 0
      ;;
  esac
}

start_compose() {
  local run_dir="$1" project="${STACK_ENV[INT001_COMPOSE_PROJECT]}"
  # `doctor` performs the first zero-resource preflight, but source builds can
  # take long enough for a stale or external same-project resource to appear.
  # Recheck immediately before Compose is allowed to mutate the local daemon.
  if ! assert_no_fresh_docker_resources "$project"; then
    note "fresh INT-001 run collides with existing Docker resources"
    return 1
  fi
  docker_compose_for_run "$run_dir" up -d --wait \
    || {
      note "disposable Compose services did not become healthy"
      return 1
    }
  # Dynamic allocation checks a free port before Docker binds it, so it cannot
  # promise a reservation across the host/daemon race. Once Compose reports
  # healthy, prove only that both actual loopback publishes accept TCP; do not
  # turn this boundary check into a credential-bearing database probe.
  if ! verify_compose_loopback_tcp "$run_dir"; then
    note "disposable Compose loopback publishes are not reachable"
    return 1
  fi
}

run_stack() {
  local run_dir project state launcher_start_status
  local -a launcher_pairs=()
  validate_run_id
  run_dir="$(run_dir_for "$RUN_ID")"
  assert_expected_run_path "$run_dir"
  acquire_run_lock "$run_dir"
  arm_lifecycle_signal_cleanup "$run_dir"
  set_lifecycle_failure_stage PREFLIGHT
  if ! (trap - EXIT; doctor_prepared_run "$run_dir"); then
    fail_lifecycle_stage "$run_dir" PREFLIGHT "run preflight failed; owned runtime resources were sent through fail-closed cleanup"
  fi
  # Profile and manifest validation still belongs to the run preflight.  This
  # stamp covers their unwrapped `set -e` paths as well as explicit failures.
  set_lifecycle_failure_stage PREFLIGHT
  load_prepared_profiles "$run_dir"
  state="$(manifest_state "$(private_file_path "$run_dir" "$RUN_MANIFEST_NAME")")"
  [[ "$state" == PREPARED ]] || fail_lifecycle_stage "$run_dir" PREFLIGHT "run requires a PREPARED lifecycle state"
  set_lifecycle_failure_stage BUILD
  note "building Launcher and source-matched Open SDK from this checkout"
  if ! (cd "$REPO_ROOT" && mvn -pl launcher,navigator-open-sdk -am package -DskipTests); then
    fail_lifecycle_stage "$run_dir" BUILD "Launcher source build failed; owned runtime resources were sent through fail-closed cleanup"
  fi
  [[ -f "$LAUNCHER_JAR" && ! -L "$LAUNCHER_JAR" ]] \
    || fail_lifecycle_stage "$run_dir" BUILD "missing current source Launcher jar; rerun with --build-launcher"

  project="${STACK_ENV[INT001_COMPOSE_PROJECT]}"
  set_lifecycle_failure_stage COMPOSE
  if ! start_compose "$run_dir"; then
    fail_lifecycle_stage "$run_dir" COMPOSE "Compose startup failed; owned resources were sent through fail-closed cleanup"
  fi
  set_lifecycle_failure_stage DIRECTORY_FACADE
  if ! start_child "$run_dir" directory-facade directory_facade.py "$(private_file_path "$run_dir" "$DIRECTORY_FACADE_LOG_NAME")" \
    env -i "PATH=$SAFE_CHILD_PATH" "HOME=$run_dir/home" "PYTHONDONTWRITEBYTECODE=1" \
    python3 "$DIRECTORY_FACADE" --config "$(private_file_path "$run_dir" "$DIRECTORY_FACADE_ENV_NAME")"; then
    fail_lifecycle_stage "$run_dir" DIRECTORY_FACADE "directory facade startup failed"
  fi
  if ! wait_for_http "$run_dir" "http://127.0.0.1:$DIRECTORY_FACADE_PORT/health" "directory facade"; then
    fail_lifecycle_stage "$run_dir" DIRECTORY_FACADE "directory facade health failed"
  fi
  set_lifecycle_failure_stage BIZ_WORKER
  if ! start_child "$run_dir" biz-worker langgraph_biz_worker.main:app "$(private_file_path "$run_dir" "$BIZ_WORKER_LOG_NAME")" \
    env -i "PATH=$SAFE_CHILD_PATH" "HOME=$run_dir/home" "BIZ_WORKER_ENV_FILE=$(private_file_path "$run_dir" "$BIZ_WORKER_ENV_NAME")" \
    "PYTHONPATH=$REPO_ROOT/tools/langgraph-biz-worker/src" "PYTHONDONTWRITEBYTECODE=1" \
    "$BIZ_WORKER_PYTHON" -m uvicorn langgraph_biz_worker.main:app --host 127.0.0.1 --port "$BIZ_PORT"; then
    fail_lifecycle_stage "$run_dir" BIZ_WORKER "Biz Worker startup failed"
  fi
  if ! wait_for_http "$run_dir" "http://127.0.0.1:$BIZ_PORT/health" "Biz Worker"; then
    fail_lifecycle_stage "$run_dir" BIZ_WORKER "Biz Worker health failed"
  fi
  set_lifecycle_failure_stage BIZ_INGRESS_PROXY
  if ! start_child "$run_dir" biz-ingress-proxy biz_ingress_proxy.py "$(private_file_path "$run_dir" "$BIZ_INGRESS_PROXY_LOG_NAME")" \
    env -i "PATH=$SAFE_CHILD_PATH" "HOME=$run_dir/home" "PYTHONDONTWRITEBYTECODE=1" \
    python3 "$BIZ_INGRESS_PROXY" --config "$(private_file_path "$run_dir" "$BIZ_INGRESS_PROXY_ENV_NAME")"; then
    fail_lifecycle_stage "$run_dir" BIZ_INGRESS_PROXY "Biz ingress proxy startup failed"
  fi
  if ! wait_for_http "$run_dir" "http://127.0.0.1:$BIZ_INGRESS_PROXY_PORT/health" "Biz ingress proxy"; then
    fail_lifecycle_stage "$run_dir" BIZ_INGRESS_PROXY "Biz ingress proxy health failed"
  fi
  set_lifecycle_failure_stage LAUNCHER
  environment_array_from_profile LAUNCHER_ENV launcher_pairs
  # `doctor_prepared_run` runs in a defensive subshell above, so resolve the
  # exact Java executable again in this parent before it starts the Launcher.
  assert_launcher_java
  if start_child "$run_dir" launcher launcher-1.0.0-SNAPSHOT.jar "$(private_file_path "$run_dir" "$LAUNCHER_PROCESS_LOG_NAME")" \
    env -i "PATH=$SAFE_CHILD_PATH" "HOME=$run_dir/home" "${launcher_pairs[@]}" \
    "$LAUNCHER_JAVA" "-Dint001.run-id=$RUN_ID" -jar "$LAUNCHER_JAR" --spring.profiles.active=mock; then
    :
  else
    launcher_start_status="$?"
    if [[ "$launcher_start_status" == "$START_CHILD_EXITED_BEFORE_METADATA" ]]; then
      LAUNCHER_READINESS_OBSERVATION='CHILD_EXITED_BEFORE_HEALTH'
      classify_launcher_failure "$run_dir"
    else
      LAUNCHER_READINESS_OBSERVATION='START_FAILED'
      LAUNCHER_FAILURE_CLASS='START_EXEC_FAILURE'
    fi
    fail_lifecycle_stage "$run_dir" LAUNCHER "Launcher startup failed"
  fi
  # Cold disposable schema initialization can legitimately take longer than
  # the lightweight fixture services. Keep this bounded and loopback-only;
  # the child ownership proof remains in force on every failure path.
  if ! wait_for_launcher_readiness "$run_dir" "http://127.0.0.1:$NAVIGATOR_PORT/actuator/health" 180; then
    classify_launcher_failure "$run_dir"
    fail_lifecycle_stage "$run_dir" LAUNCHER "Launcher health failed"
  fi
  set_lifecycle_failure_stage MANIFEST
  if ! write_manifest "$(private_file_path "$run_dir" "$RUN_MANIFEST_NAME")" RUNNING "$run_dir" "$project"; then
    fail_lifecycle_stage "$run_dir" MANIFEST "run manifest write failed; owned runtime resources were sent through fail-closed cleanup"
  fi
  if [[ "$HOLD_FOR_PARENT_TERM" == 1 ]]; then
    hold_for_parent_term "$run_dir"
  fi
  disarm_lifecycle_signal_cleanup "$run_dir"
  note "run=PASS runId=$RUN_ID target=http://127.0.0.1:$NAVIGATOR_PORT bootstrapTarget=$PRIVATE_DIRECTORY_NAME/$BOOTSTRAP_TARGET_PROFILE_NAME"
}

hold_for_parent_term() {
  local run_dir="$1" deadline
  [[ "$ACTION" == run && "$HOLD_FOR_PARENT_TERM" == 1 ]] \
    || die "parent TERM hold may run only inside the exact run lifecycle"
  [[ "$LIFECYCLE_SIGNAL_CLEANUP_ARMED" == 1 && "$LIFECYCLE_SIGNAL_RUN_DIR" == "$run_dir" ]] \
    || die "parent TERM hold requires armed owned cleanup"
  REHEARSAL_LIFECYCLE_OBSERVATION='HOLD_ENTERED'
  deadline=$((SECONDS + PARENT_TERM_REHEARSAL_HOLD_SECONDS))
  note "run=HOLDING_PARENT_TERM runId=$RUN_ID"
  while (( SECONDS < deadline )); do
    if ! sleep 1; then
      REHEARSAL_LIFECYCLE_OBSERVATION='HOLD_WAIT_FAILURE'
      fail_lifecycle_stage "$run_dir" UNKNOWN "parent TERM rehearsal hold wait failed; owned runtime resources were sent through fail-closed cleanup"
    fi
  done
  REHEARSAL_LIFECYCLE_OBSERVATION='HOLD_TIMEOUT'
  fail_lifecycle_stage "$run_dir" UNKNOWN "parent TERM rehearsal timed out; owned runtime resources were sent through fail-closed cleanup"
}

parse_child_meta() {
  local file="$1"
  parse_strict_env "$file" child CHILD_META
}

child_is_alive() {
  local pid="$1"
  kill -0 "$pid" 2>/dev/null || return 1
  # `kill -0` succeeds for an exited but unreaped child.  Treat a zombie as
  # terminated, not as a healthy Launcher: it cannot bind a port or service a
  # request, and keeping it in the health loop would turn an immediate exit
  # into a misleading timeout.  A successful `ps` state probe is required so
  # an inaccessible process is still handled as unproven by the caller.
  ! child_is_zombie "$pid"
}

child_is_zombie() {
  local pid="$1" state
  state="$(ps -o stat= -p "$pid" 2>/dev/null | tr -d '[:space:]')" || return 1
  [[ "$state" == Z* ]]
}

child_is_proven_dead() {
  local pid="$1"
  # `kill -0` also returns non-zero for EPERM.  Treat that as unproven rather
  # than dead: otherwise a PID reused by another user could lose the only
  # remediation metadata even though it is still live.
  if child_is_zombie "$pid"; then
    return 0
  fi
  ! child_is_alive "$pid" && [[ ! -e "/proc/$pid" ]]
}

parse_child_meta_for_probe() {
  local file="$1" raw key value expected line_count=0 key_count=0
  local mode owner links
  [[ -f "$file" && ! -L "$file" ]] || return 21
  mode="$(stat -c '%a' "$file" 2>/dev/null)" || return 21
  owner="$(stat -c '%u' "$file" 2>/dev/null)" || return 21
  links="$(stat -c '%h' "$file" 2>/dev/null)" || return 21
  [[ "$mode" == 600 && "$owner" == "$(id -u)" && "$links" == 1 ]] || return 21

  CHILD_META=()
  while IFS= read -r raw || [[ -n "$raw" ]]; do
    ((line_count += 1))
    [[ -n "$raw" && "$raw" != \#* && "$raw" == *=* && "$raw" != *$'\r'* ]] || return 21
    key="${raw%%=*}"
    value="${raw#*=}"
    [[ "$key" =~ ^[A-Z][A-Z0-9_]*$ ]] || return 21
    schema_contains_key child "$key" || return 21
    [[ -z "${CHILD_META[$key]+present}" && -n "$value" ]] || return 21
    CHILD_META["$key"]="$value"
    ((key_count += 1))
  done < "$file"
  [[ "$line_count" == 7 && "$key_count" == 7 ]] || return 21
  while IFS= read -r expected; do
    [[ -n "${CHILD_META[$expected]+present}" ]] || return 21
  done < <(schema_keys child)
}

# Return values are intentionally distinct so cleanup can remove only a
# proven-dead child metadata file.  A live PID whose ownership proof has
# changed is not a successful cleanup: leave it untouched and fail closed.
#   0 = live and proven owned; 10 = proven metadata but dead PID;
#   20 = no metadata; 21/22 = malformed or live-but-unprovable.
probe_owned_child() {
  local run_dir="$1" name="$2" fragment="$3"
  local meta="$run_dir/children/$name.pid"
  local pid start now_start pgid sid cwd args current_pgid current_sid current_cwd
  [[ ! -e "$meta" && ! -L "$meta" ]] && return 20
  parse_child_meta_for_probe "$meta" || return 21
  [[ "${CHILD_META[INT001_CHILD_NAME]}" == "$name" && "${CHILD_META[COMMAND_FRAGMENT]}" == "$fragment" ]] \
    || return 21
  pid="${CHILD_META[PID]}"
  start="${CHILD_META[START_TICKS]}"
  pgid="${CHILD_META[PGID]}"
  sid="${CHILD_META[SID]}"
  cwd="${CHILD_META[CWD]}"
  [[ "$pid" =~ ^[0-9]+$ && "$start" =~ ^[0-9]+$ && "$pgid" == "$pid" && "$sid" == "$pid" ]] \
    || return 21
  if ! child_is_alive "$pid"; then
    child_is_proven_dead "$pid" && return 10
    return 22
  fi
  now_start="$(pid_start_ticks "$pid")" || return 22
  [[ "$now_start" == "$start" ]] || return 22
  current_pgid="$(ps -o pgid= -p "$pid" 2>/dev/null | tr -d ' ')" || return 22
  current_sid="$(ps -o sid= -p "$pid" 2>/dev/null | tr -d ' ')" || return 22
  [[ "$current_pgid" == "$pgid" && "$current_sid" == "$sid" ]] || return 22
  current_cwd="$(readlink -f "/proc/$pid/cwd" 2>/dev/null)" || return 22
  [[ "$current_cwd" == "$(realpath -m "$run_dir")" && "$cwd" == "$(realpath -m "$run_dir")" ]] \
    || return 22
  args="$(ps -o args= -p "$pid")"
  [[ "$args" == *"$fragment"* ]] || return 22
  return 0
}

validate_owned_child() {
  probe_owned_child "$@"
}

stop_owned_child() {
  local run_dir="$1" name="$2" fragment="$3"
  local meta="$run_dir/children/$name.pid"
  local pid pgid attempt=0 ownership_state
  if probe_owned_child "$run_dir" "$name" "$fragment"; then
    :
  else
    ownership_state="$?"
    case "$ownership_state" in
      10)
        # Only a strictly parsed, current-user-owned metadata file may be
        # removed after its recorded PID is proven dead.
        rm -f -- "$meta" || return 1
        return 0
        ;;
      20)
        return 0
        ;;
      *)
        note "cleanup cannot prove ownership of live or malformed child $name"
        return 1
        ;;
    esac
  fi
  pid="${CHILD_META[PID]}"
  pgid="${CHILD_META[PGID]}"
  kill -TERM -- "-$pgid" || return 1
  while ! child_is_proven_dead "$pid" && (( attempt < 20 )); do
    sleep 0.25 || return 1
    ((attempt += 1))
  done
  if ! child_is_proven_dead "$pid"; then
    validate_owned_child "$run_dir" "$name" "$fragment" || return 1
    kill -KILL -- "-$pgid" || return 1
    sleep 0.25 || return 1
    child_is_proven_dead "$pid" || return 1
  fi
  rm -f -- "$meta" || return 1
  return 0
}

append_unique() {
  local array_name="$1" value="$2"
  local -n array="$array_name"
  local item
  for item in "${array[@]:-}"; do [[ "$item" == "$value" ]] && return; done
  [[ -n "$value" ]] && array+=("$value")
  return 0
}

collect_docker_ids() {
  local project="$1" run_id="$2" kind="$3" id output
  local -a result=()
  case "$kind" in
    container)
      output="$(docker_local ps -aq --filter "label=com.docker.compose.project=$project")" || return 1
      while IFS= read -r id; do append_unique result "$id" || return 1; done <<< "$output"
      output="$(docker_local ps -aq --filter "label=com.foggy.navigator.int001.run-id=$run_id")" || return 1
      while IFS= read -r id; do append_unique result "$id" || return 1; done <<< "$output"
      ;;
    network)
      output="$(docker_local network ls -q --filter "label=com.docker.compose.project=$project")" || return 1
      while IFS= read -r id; do append_unique result "$id" || return 1; done <<< "$output"
      output="$(docker_local network ls -q --filter "label=com.foggy.navigator.int001.run-id=$run_id")" || return 1
      while IFS= read -r id; do append_unique result "$id" || return 1; done <<< "$output"
      ;;
    volume)
      output="$(docker_local volume ls -q --filter "label=com.docker.compose.project=$project")" || return 1
      while IFS= read -r id; do append_unique result "$id" || return 1; done <<< "$output"
      output="$(docker_local volume ls -q --filter "label=com.foggy.navigator.int001.run-id=$run_id")" || return 1
      while IFS= read -r id; do append_unique result "$id" || return 1; done <<< "$output"
      ;;
    *) die "internal unknown Docker resource kind" ;;
  esac
  printf '%s\n' "${result[@]:-}"
}

assert_owned_docker_resource() {
  local kind="$1" id="$2" project="$3" labels
  case "$kind" in
    container)
      labels="$(docker_local inspect --format '{{index .Config.Labels "com.docker.compose.project"}}|{{index .Config.Labels "com.foggy.navigator.int001.managed"}}|{{index .Config.Labels "com.foggy.navigator.int001.run-id"}}' "$id")" || return 1
      ;;
    network)
      labels="$(docker_local network inspect --format '{{index .Labels "com.docker.compose.project"}}|{{index .Labels "com.foggy.navigator.int001.managed"}}|{{index .Labels "com.foggy.navigator.int001.run-id"}}' "$id")" || return 1
      ;;
    volume)
      labels="$(docker_local volume inspect --format '{{index .Labels "com.docker.compose.project"}}|{{index .Labels "com.foggy.navigator.int001.managed"}}|{{index .Labels "com.foggy.navigator.int001.run-id"}}' "$id")" || return 1
      ;;
    *) return 1 ;;
  esac
  [[ "$labels" == "$project|true|$RUN_ID" ]] || return 1
  return 0
}

assert_all_docker_resources_owned() {
  local project="$1" kind id ids
  for kind in container network volume; do
    ids="$(collect_docker_ids "$project" "$RUN_ID" "$kind")" || return 1
    while IFS= read -r id; do
      [[ -n "$id" ]] || continue
      assert_owned_docker_resource "$kind" "$id" "$project" || return 1
    done <<< "$ids"
  done
  return 0
}

assert_no_fresh_docker_resources() {
  local project="$1" kind id ids
  # A PREPARED run has not started Compose yet.  Any resource matching either
  # its deterministic project name or its run label is stale/foreign until
  # proven otherwise, so fail before `docker compose up` can attach to or
  # mutate it.  Cleanup remains intentionally separate and will never remove
  # a collision merely to make a fresh run pass.
  for kind in container network volume; do
    ids="$(collect_docker_ids "$project" "$RUN_ID" "$kind")" || return 1
    while IFS= read -r id; do
      [[ -n "$id" ]] || continue
      note "fresh run found pre-existing Docker $kind resource $id for project/run ownership"
      return 1
    done <<< "$ids"
  done
  return 0
}

assert_no_docker_resources_remain() {
  local project="$1" kind id ids
  for kind in container network volume; do
    ids="$(collect_docker_ids "$project" "$RUN_ID" "$kind")" || return 1
    while IFS= read -r id; do
      [[ -n "$id" ]] && return 1
    done <<< "$ids"
  done
  return 0
}

safe_remove_run_path() {
  local run_dir="$1" relative="$2"
  local target
  target="$run_dir/$relative"
  [[ "$(realpath -m "$target")" == "$(realpath -m "$run_dir")"/* ]] || die "cleanup path escaped run directory"
  [[ ! -L "$target" ]] || die "refusing to remove symlinked run artifact"
  rm -rf --one-file-system -- "$target" || return 1
  return 0
}

write_cleanup_report() {
  local run_dir="$1" result="$2" failure_stage="${3:-NONE}" file
  cleanup_result_allowed "$result" || die "cleanup receipt result is unsafe"
  failure_stage_allowed "$failure_stage" || die "cleanup receipt failure stage is unsafe"
  launcher_readiness_observation_allowed "$LAUNCHER_READINESS_OBSERVATION" \
    || die "cleanup receipt Launcher readiness observation is unsafe"
  launcher_failure_class_allowed "$LAUNCHER_FAILURE_CLASS" \
    || die "cleanup receipt Launcher failure class is unsafe"
  rehearsal_lifecycle_observation_allowed "$REHEARSAL_LIFECYCLE_OBSERVATION" \
    || die "cleanup receipt rehearsal lifecycle observation is unsafe"
  file="$run_dir/$CLEANUP_REPORT_NAME"
  assert_private_dir "$run_dir"
  [[ ! -e "$file" && ! -L "$file" ]] || die "cleanup report already exists or is unsafe"
  if ! (set -o noclobber; {
    printf '{\n'
    printf '  "schemaVersion": %s,\n' "$CLEANUP_RECEIPT_SCHEMA_VERSION"
    printf '  "runId": "%s",\n' "$RUN_ID"
    printf '  "result": "%s",\n' "$result"
    printf '  "failureStage": "%s",\n' "$failure_stage"
    printf '  "rehearsalLifecycleObservation": "%s",\n' "$REHEARSAL_LIFECYCLE_OBSERVATION"
    printf '  "launcherReadinessObservation": "%s",\n' "$LAUNCHER_READINESS_OBSERVATION"
    printf '  "launcherFailureClass": "%s",\n' "$LAUNCHER_FAILURE_CLASS"
    printf '  "finishedAtUtc": "%s",\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    printf '  "secretsRedacted": true\n'
    printf '}\n'
  } > "$file") 2>/dev/null; then
    return 1
  fi
  chmod 600 "$file" || return 1
  assert_private_file "$file"
}

# The parent one-shot lifecycle may observe a child that has already removed
# its private carriers.  Treat its root receipt as a security boundary, not as
# a mere presence marker: only the exact fixed schema for this run and a
# positively completed cleanup can satisfy the parent cleanup invariant.
assert_cleaned_cleanup_receipt() {
  local file="$1" expected_failure_stage="${2:-}"
  # A parent lifecycle may only adopt a child cleanup receipt for the same
  # fixed failure stage.  In particular, a parent TERM cannot turn an older
  # successful cleanup such as CLEANED/PREPARE into evidence for
  # CLEANED/SIGNAL.
  [[ -z "$expected_failure_stage" ]] || failure_stage_allowed "$expected_failure_stage" \
    || die "cleanup receipt expected failure stage is unsafe"
  if ! (trap - EXIT; assert_private_file "$file"); then
    return 1
  fi
  python3 - "$file" "$RUN_ID" "$expected_failure_stage" <<'PY' || return 1
import json
import re
import sys

path, expected_run_id, expected_failure_stage = sys.argv[1:]

def reject_duplicate_object_keys(pairs):
    value = {}
    for key, item in pairs:
        if key in value:
            raise ValueError("duplicate JSON object key")
        value[key] = item
    return value

try:
    with open(path, encoding="utf-8") as handle:
        value = json.load(handle, object_pairs_hook=reject_duplicate_object_keys)
except (OSError, json.JSONDecodeError, ValueError):
    raise SystemExit(1)

if not isinstance(value, dict) or set(value) != {
    "schemaVersion", "runId", "result", "failureStage", "rehearsalLifecycleObservation",
    "launcherReadinessObservation", "launcherFailureClass", "finishedAtUtc", "secretsRedacted"
}:
    raise SystemExit(1)
if type(value["schemaVersion"]) is not int or value["schemaVersion"] != 4:
    raise SystemExit(1)
if (
    type(value["runId"]) is not str
    or value["runId"] != expected_run_id
    or type(value["result"]) is not str
    or value["result"] != "CLEANED"
):
    raise SystemExit(1)
if type(value["failureStage"]) is not str or value["failureStage"] not in {
    "NONE", "PREPARE", "PREFLIGHT", "BUILD", "COMPOSE", "DIRECTORY_FACADE",
    "BIZ_WORKER", "BIZ_INGRESS_PROXY", "LAUNCHER", "BOOTSTRAP", "AUDIT",
    "MANIFEST", "SIGNAL", "UNKNOWN"
}:
    raise SystemExit(1)
if expected_failure_stage and value["failureStage"] != expected_failure_stage:
    raise SystemExit(1)
if type(value["rehearsalLifecycleObservation"]) is not str or value["rehearsalLifecycleObservation"] not in {
    "NOT_REHEARSAL", "HOLD_ENTERED", "HOLD_TIMEOUT", "HOLD_WAIT_FAILURE", "HOLD_SIGNAL_RECEIVED"
}:
    raise SystemExit(1)
if type(value["launcherReadinessObservation"]) is not str or value["launcherReadinessObservation"] not in {
    "NOT_OBSERVED", "START_FAILED", "HEALTH_READY", "CHILD_EXITED_BEFORE_HEALTH",
    "CHILD_OWNERSHIP_UNPROVEN", "CHILD_ALIVE_AT_HEALTH_TIMEOUT"
}:
    raise SystemExit(1)
if type(value["launcherFailureClass"]) is not str or value["launcherFailureClass"] not in {
    "NOT_APPLICABLE", "START_EXEC_FAILURE", "PORT_BIND_CONFLICT", "DATABASE_CONNECTIVITY",
    "DATABASE_AUTHORIZATION", "DATABASE_SCHEMA", "SPRING_CONFIGURATION", "JVM_OR_ARTIFACT",
    "APPLICATION_INITIALIZATION", "HEALTH_TIMEOUT", "OWNERSHIP_UNPROVEN", "UNKNOWN"
}:
    raise SystemExit(1)
if type(value["secretsRedacted"]) is not bool or value["secretsRedacted"] is not True:
    raise SystemExit(1)
if not isinstance(value["finishedAtUtc"], str) or not re.fullmatch(
    r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z", value["finishedAtUtc"]
):
    raise SystemExit(1)
PY
  return 0
}

scrub_legacy_root_private_carriers() {
  local run_dir="$1" file target
  # This is a single-node scrub only.  It never parses, sources, stats, or
  # follows an old root carrier; a symlink is unlinked as a symlink, and a
  # directory at a file-only carrier name is left for explicit remediation.
  for file in "${LEGACY_ROOT_PRIVATE_CARRIER_NAMES[@]}"; do
    target="$run_dir/$file"
    [[ ! -d "$target" || -L "$target" ]] \
      || die "refusing to recursively scrub a legacy root carrier directory: $file"
    rm -f -- "$target" || return 1
  done
  return 0
}

delete_verified_private_directory() {
  local run_dir="$1" private_dir
  private_dir="$run_dir/$PRIVATE_DIRECTORY_NAME"
  if [[ -e "$private_dir" || -L "$private_dir" ]]; then
    assert_private_dir "$private_dir"
    safe_remove_run_path "$run_dir" "$PRIVATE_DIRECTORY_NAME" || return 1
  fi
  return 0
}

delete_private_run_artifacts() {
  local run_dir="$1" preserve_child_metadata="${2:-false}"
  delete_verified_private_directory "$run_dir" || return 1
  scrub_legacy_root_private_carriers "$run_dir" || return 1
  safe_remove_run_path "$run_dir" mock-responses || return 1
  safe_remove_run_path "$run_dir" biz-data || return 1
  safe_remove_run_path "$run_dir" directory-workspaces || return 1
  safe_remove_run_path "$run_dir" session-message-payloads || return 1
  safe_remove_run_path "$run_dir" home || return 1
  if [[ "$preserve_child_metadata" != true ]]; then
    safe_remove_run_path "$run_dir" children || return 1
  fi
  return 0
}

scrub_after_failed_cleanup() {
  local run_dir="$1"
  # This path is deliberately local-file-only.  Docker/process ownership has
  # already become uncertain, so do not attempt another external action; erase
  # the run-owned credential carriers and leave a redacted report instead.
  [[ -n "$RUN_ID" ]] || return 1
  assert_expected_run_path "$run_dir" || return 1
  assert_private_dir "$run_dir" || return 1
  # When a child is still live but ownership cannot be proven, retain only its
  # non-secret PID proof for explicit remediation.  Removing it would make a
  # failed cleanup look successful and eliminate the information needed to
  # safely resolve the process later.
  delete_private_run_artifacts "$run_dir" true || return 1
  write_cleanup_report "$run_dir" FAILED_CLEANUP "$LIFECYCLE_FAILURE_STAGE" || return 1
  CLEANUP_SCRUB_COMPLETED=1
  note "cleanup=FAILED_CLEANUP runId=$RUN_ID localSecretsScrubbed=true"
  return 0
}

cleanup_run() {
  local run_dir="$1" cleanup_result="${2:-CLEANED}" failure_stage="${3:-NONE}" project manifest
  cleanup_result_allowed "$cleanup_result" || die "cleanup result is unsafe"
  failure_stage_allowed "$failure_stage" || die "cleanup failure stage is unsafe"
  run_dir="$(realpath -m "$run_dir")"
  assert_expected_run_path "$run_dir"
  assert_private_dir "$run_dir"
  CLEANUP_SCRUB_RUN_DIR="$run_dir"
  CLEANUP_SCRUB_COMPLETED=0
  load_prepared_profiles "$run_dir" || return 1
  project="${STACK_ENV[INT001_COMPOSE_PROJECT]}"
  manifest="$(private_file_path "$run_dir" "$RUN_MANIFEST_NAME")"

  # Process-group signal is allowed only after all live PID ownership proofs.
  stop_owned_child "$run_dir" launcher launcher-1.0.0-SNAPSHOT.jar || return 1
  stop_owned_child "$run_dir" biz-ingress-proxy biz_ingress_proxy.py || return 1
  stop_owned_child "$run_dir" biz-worker langgraph_biz_worker.main:app || return 1
  stop_owned_child "$run_dir" directory-facade directory_facade.py || return 1

  assert_all_docker_resources_owned "$project" || return 1
  docker_compose_for_run "$run_dir" down --volumes --remove-orphans || return 1
  assert_no_docker_resources_remain "$project" || return 1

  write_manifest "$manifest" "$cleanup_result" "$run_dir" "$project" || return 1
  delete_private_run_artifacts "$run_dir" || return 1
  write_cleanup_report "$run_dir" "$cleanup_result" "$failure_stage" || return 1
  CLEANUP_SCRUB_COMPLETED=1
  CLEANUP_SCRUB_RUN_DIR=""
  note "cleanup=PASS runId=$RUN_ID result=$cleanup_result"
  return 0
}

cleanup_command() {
  local run_dir
  validate_run_id
  run_dir="$(run_dir_for "$RUN_ID")"
  assert_expected_run_path "$run_dir"
  acquire_run_lock "$run_dir"
  cleanup_run "$run_dir" CLEANED
}

exercise_cleanup_after_failure() {
  local run_dir="$1" failure_stage="$2" artifact
  # This is process-local control flow, not a public CLI option: a caller
  # cannot forge a durable phase label through arguments.  A child lifecycle
  # that already cleaned up retains its own receipt and is never overwritten.
  failure_stage_allowed "$failure_stage" || failure_stage='UNKNOWN'
  assert_expected_run_path "$run_dir"
  if [[ -f "$(private_file_path "$run_dir" "$STACK_ENV_NAME")" && ! -L "$(private_file_path "$run_dir" "$STACK_ENV_NAME")" ]]; then
    note "exercise=cleanup-on-failure runId=$RUN_ID"
    acquire_run_lock "$run_dir"
    set_lifecycle_failure_stage "$failure_stage"
    cleanup_run "$run_dir" CLEANED "$failure_stage"
    return
  fi

  # `run`, `bootstrap`, and `audit` individually fail closed and can already
  # have removed their private profiles before returning to the one-shot
  # parent.  Accept that case only when their redacted local cleanup receipt is
  # present and no credential carrier remains for this run.
  if ! (trap - EXIT; assert_private_dir "$run_dir"); then
    note "exercise cleanup receipt is under an unsafe run directory"
    return 1
  fi
  for artifact in "${LEGACY_ROOT_PRIVATE_CARRIER_NAMES[@]}" "$PRIVATE_DIRECTORY_NAME"; do
    [[ ! -e "$run_dir/$artifact" && ! -L "$run_dir/$artifact" ]] || {
      note "exercise cleanup cannot prove the state of $(basename "$artifact")"
      return 1
    }
  done
  [[ -f "$run_dir/cleanup-report.json" && ! -L "$run_dir/cleanup-report.json" ]] || {
    note "exercise cleanup receipt is absent after a failed lifecycle stage"
    return 1
  }
  if ! assert_cleaned_cleanup_receipt "$run_dir/cleanup-report.json" "$failure_stage"; then
    note "exercise cleanup receipt is not a verified CLEANED result for this run"
    return 1
  fi
  note "exercise=cleanup-already-completed runId=$RUN_ID"
}

exercise_fail_after_prepared() {
  local stage="$1" failure_stage="$2" run_dir="$3"
  if ! exercise_cleanup_after_failure "$run_dir" "$failure_stage"; then
    die "exercise failed during $stage and cleanup could not be independently verified"
  fi
  die "exercise failed during $stage; owned disposable resources were cleaned"
}

exercise_signal_cleanup() {
  local signal="$1" run_dir="$2"
  trap - HUP INT TERM
  note "exercise received $signal; attempting owned cleanup"
  if exercise_child_is_live_and_owned; then
    note "exercise forwarding $signal cleanup to active $EXERCISE_CHILD_STAGE lifecycle child"
    # Each delegated child is launched with setsid, so this reaches a source
    # build or other foreground descendant as well as the child harness trap.
    # TERM is deliberate: child lifecycle handlers classify it as SIGNAL and
    # execute the ownership-checked cleanup while holding their run lock.
    kill -TERM -- "-$EXERCISE_CHILD_PID" || \
      note "exercise could not signal its lifecycle child; proceeding with fail-closed verification"
    if wait "$EXERCISE_CHILD_PID"; then
      :
    else
      :
    fi
  elif [[ -n "$EXERCISE_CHILD_PID" ]]; then
    # Never signal a PID after its start-tick/session/cwd proof changes.  A
    # child that has already exited can still be reaped safely; a live but
    # unprovable one is deliberately left for manual remediation rather than
    # risking a host process outside this disposable run.
    note "exercise cannot prove active lifecycle child ownership; not signaling it"
    if wait "$EXERCISE_CHILD_PID"; then
      :
    else
      :
    fi
  fi
  EXERCISE_CHILD_PID=""
  EXERCISE_CHILD_STAGE=""
  EXERCISE_CHILD_START_TICKS=""
  EXERCISE_CHILD_EXPECTED_ARGV=()
  exercise_cleanup_after_failure "$run_dir" SIGNAL || \
    note "exercise cleanup could not be independently verified after $signal"
  exit 128
}

exercise_child_argv_is_canonical() {
  local stage="$1"
  local -a actual_argv expected_argv
  local index
  shift
  actual_argv=("$@")
  case "$stage" in
    prepare)
      expected_argv=("$TRUSTED_BASH" -p "$HARNESS_SELF" prepare --allow-create --run-id "$RUN_ID")
      [[ -n "$NAVIGATOR_PORT" ]] && expected_argv+=(--navigator-port "$NAVIGATOR_PORT")
      [[ -n "$MYSQL_PORT" ]] && expected_argv+=(--mysql-port "$MYSQL_PORT")
      [[ -n "$MOCK_LLM_PORT" ]] && expected_argv+=(--mock-llm-port "$MOCK_LLM_PORT")
      [[ -n "$BIZ_PORT" ]] && expected_argv+=(--biz-port "$BIZ_PORT")
      [[ -n "$BIZ_INGRESS_PROXY_PORT" ]] && expected_argv+=(--biz-ingress-proxy-port "$BIZ_INGRESS_PROXY_PORT")
      [[ -n "$DIRECTORY_FACADE_PORT" ]] && expected_argv+=(--directory-facade-port "$DIRECTORY_FACADE_PORT")
      ;;
    doctor)
      expected_argv=("$TRUSTED_BASH" -p "$HARNESS_SELF" doctor --run-id "$RUN_ID")
      ;;
    run)
      expected_argv=("$TRUSTED_BASH" -p "$HARNESS_SELF" run --allow-execute --build-launcher --run-id "$RUN_ID")
      ;;
    run-hold)
      expected_argv=("$TRUSTED_BASH" -p "$HARNESS_SELF" run --allow-execute --build-launcher --run-id "$RUN_ID" --hold-for-parent-term)
      ;;
    bootstrap)
      expected_argv=("$TRUSTED_BASH" -p "$HARNESS_SELF" bootstrap --allow-create --run-id "$RUN_ID")
      ;;
    audit)
      expected_argv=("$TRUSTED_BASH" -p "$HARNESS_SELF" audit --allow-execute --run-id "$RUN_ID")
      ;;
    cleanup)
      expected_argv=("$TRUSTED_BASH" -p "$HARNESS_SELF" cleanup --allow-execute --run-id "$RUN_ID")
      ;;
    *)
      return 1
      ;;
  esac
  [[ "${#actual_argv[@]}" == "${#expected_argv[@]}" ]] || return 1
  for ((index = 0; index < ${#expected_argv[@]}; index += 1)); do
    [[ "${actual_argv[index]}" == "${expected_argv[index]}" ]] || return 1
  done
  return 0
}

exercise_child_is_live_and_owned() {
  local pid="$EXERCISE_CHILD_PID" start="$EXERCISE_CHILD_START_TICKS"
  local current_start final_start pgid sid cwd uid argument
  local -a actual_argv
  [[ "$pid" =~ ^[0-9]+$ && "$start" =~ ^[0-9]+$ ]] || return 1
  [[ -n "$EXERCISE_CHILD_STAGE" ]] || return 1
  exercise_child_argv_is_canonical "$EXERCISE_CHILD_STAGE" "${EXERCISE_CHILD_EXPECTED_ARGV[@]}" || return 1
  child_is_alive "$pid" || return 1
  current_start="$(pid_start_ticks "$pid")" || return 1
  [[ "$current_start" == "$start" ]] || return 1
  uid="$(stat -c '%u' -- "/proc/$pid" 2>/dev/null)" || return 1
  [[ "$uid" == "$(id -u)" ]] || return 1
  pgid="$(ps -o pgid= -p "$pid" 2>/dev/null | tr -d ' ')" || return 1
  sid="$(ps -o sid= -p "$pid" 2>/dev/null | tr -d ' ')" || return 1
  [[ "$pgid" == "$pid" && "$sid" == "$pid" ]] || return 1
  cwd="$(readlink -f "/proc/$pid/cwd" 2>/dev/null)" || return 1
  [[ "$cwd" == "$REPO_ROOT" ]] || return 1
  [[ -r "/proc/$pid/cmdline" ]] || return 1
  actual_argv=()
  while IFS= read -r -d '' argument; do
    actual_argv+=("$argument")
  done < "/proc/$pid/cmdline"
  exercise_child_argv_is_canonical "$EXERCISE_CHILD_STAGE" "${actual_argv[@]}" || return 1
  final_start="$(pid_start_ticks "$pid")" || return 1
  [[ "$final_start" == "$start" ]] || return 1
  return 0
}

exercise_invoke_child() {
  local stage="$1" status home
  local -a child_argv
  shift
  [[ -z "$EXERCISE_CHILD_PID" ]] || return 1
  child_argv=("$TRUSTED_BASH" -p "$HARNESS_SELF" "$@")
  exercise_child_argv_is_canonical "$stage" "${child_argv[@]}" || return 1
  home="$(local_docker_home)" || return 1
  # A dedicated session makes a parent-only signal observable by all direct
  # descendants of this lifecycle child.  It never grants ownership over any
  # existing host process: the child still verifies runId/cwd/PID metadata
  # before it signals or removes a resource.  The re-entry gets a tiny,
  # explicit environment so a PATH shim or BASH_ENV/ENV/CDPATH carrier from
  # the exercise caller cannot run before it verifies ownership.
  (
    cd "$REPO_ROOT"
    exec env -i "PATH=$SAFE_CHILD_PATH" "HOME=$home" \
      "$TRUSTED_SETSID" "${child_argv[@]}"
  ) &
  EXERCISE_CHILD_PID="$!"
  EXERCISE_CHILD_STAGE="$stage"
  EXERCISE_CHILD_EXPECTED_ARGV=("${child_argv[@]}")
  EXERCISE_CHILD_START_TICKS="$(pid_start_ticks "$EXERCISE_CHILD_PID")" || {
    if wait "$EXERCISE_CHILD_PID"; then
      :
    else
      :
    fi
    EXERCISE_CHILD_PID=""
    EXERCISE_CHILD_STAGE=""
    EXERCISE_CHILD_START_TICKS=""
    EXERCISE_CHILD_EXPECTED_ARGV=()
    return 1
  }
  if wait "$EXERCISE_CHILD_PID"; then
    status=0
  else
    status="$?"
  fi
  EXERCISE_CHILD_PID=""
  EXERCISE_CHILD_STAGE=""
  EXERCISE_CHILD_START_TICKS=""
  EXERCISE_CHILD_EXPECTED_ARGV=()
  return "$status"
}

exercise_run() {
  local run_dir
  local -a prepare_args run_args

  if [[ -z "$RUN_ID" ]]; then
    RUN_ID="$(generate_run_id)"
  fi
  validate_run_id
  run_dir="$(run_dir_for "$RUN_ID")"
  assert_expected_run_path "$run_dir"
  [[ ! -e "$run_dir" ]] || die "exercise run directory already exists; use a new runId"
  [[ -x "$HARNESS_SELF" && ! -L "$HARNESS_SELF" ]] \
    || die "exercise cannot safely re-invoke this harness"

  prepare_args=(prepare --allow-create --run-id "$RUN_ID")
  [[ -n "$NAVIGATOR_PORT" ]] && prepare_args+=(--navigator-port "$NAVIGATOR_PORT")
  [[ -n "$MYSQL_PORT" ]] && prepare_args+=(--mysql-port "$MYSQL_PORT")
  [[ -n "$MOCK_LLM_PORT" ]] && prepare_args+=(--mock-llm-port "$MOCK_LLM_PORT")
  [[ -n "$BIZ_PORT" ]] && prepare_args+=(--biz-port "$BIZ_PORT")
  [[ -n "$BIZ_INGRESS_PROXY_PORT" ]] && prepare_args+=(--biz-ingress-proxy-port "$BIZ_INGRESS_PROXY_PORT")
  [[ -n "$DIRECTORY_FACADE_PORT" ]] && prepare_args+=(--directory-facade-port "$DIRECTORY_FACADE_PORT")

  # A fresh harness must never rely on an arbitrary pre-existing target jar.
  # `run` rebuilds both the Launcher and Open SDK before it starts anything.
  run_args=(run --allow-execute --build-launcher --run-id "$RUN_ID")
  trap 'exercise_signal_cleanup HUP "$run_dir"' HUP
  trap 'exercise_signal_cleanup INT "$run_dir"' INT
  trap 'exercise_signal_cleanup TERM "$run_dir"' TERM

  if ! exercise_invoke_child prepare "${prepare_args[@]}"; then
    trap - HUP INT TERM
    die "exercise prepare failed before a disposable lifecycle could be established"
  fi
  if ! exercise_invoke_child doctor doctor --run-id "$RUN_ID"; then
    trap - HUP INT TERM
    exercise_fail_after_prepared doctor PREFLIGHT "$run_dir"
  fi
  if [[ "$FORCED_SIGNAL_REHEARSAL" == 1 ]]; then
    if ! exercise_invoke_child run-hold "${run_args[@]}" --hold-for-parent-term; then
      trap - HUP INT TERM
      # A held child must be ended by the proven outer parent's signal.  Any
      # other return remains fail closed and cannot masquerade as a controlled
      # parent TERM cleanup.
      exercise_fail_after_prepared forced-signal-rehearsal UNKNOWN "$run_dir"
    fi
    trap - HUP INT TERM
    exercise_fail_after_prepared forced-signal-rehearsal UNKNOWN "$run_dir"
  fi
  if ! exercise_invoke_child run "${run_args[@]}"; then
    trap - HUP INT TERM
    # A normal `run` failure records its precise child phase itself.  This
    # conservative fallback only applies if no child receipt exists.
    exercise_fail_after_prepared run PREFLIGHT "$run_dir"
  fi
  if ! exercise_invoke_child bootstrap bootstrap --allow-create --run-id "$RUN_ID"; then
    trap - HUP INT TERM
    exercise_fail_after_prepared bootstrap BOOTSTRAP "$run_dir"
  fi
  if ! exercise_invoke_child audit audit --allow-execute --run-id "$RUN_ID"; then
    trap - HUP INT TERM
    exercise_fail_after_prepared audit AUDIT "$run_dir"
  fi
  if ! exercise_invoke_child cleanup cleanup --allow-execute --run-id "$RUN_ID"; then
    trap - HUP INT TERM
    die "exercise reached audit success but final cleanup could not be independently verified"
  fi
  trap - HUP INT TERM
  note "exercise=PASS runId=$RUN_ID lifecycle=prepare,doctor,run,bootstrap,audit,cleanup"
}

main() {
  parse_args "$@"
  assert_repo_layout
  assert_no_inherited_profile_or_credentials
  case "$ACTION" in
    prepare) prepare_run ;;
    doctor)
      validate_run_id
      doctor_prepared_run "$(run_dir_for "$RUN_ID")"
      ;;
    verify-running) verify_running_run ;;
    bootstrap) bootstrap_run ;;
    run) run_stack ;;
    audit) audit_run ;;
    cleanup) cleanup_command ;;
    exercise) exercise_run ;;
  esac
}

main "$@"
