#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
. "$SCRIPT_DIR/lib.sh"

BOOTSTRAP_ENV="${PLATFORM_BOOTSTRAP_ENV:-$RUNTIME_DIR/platform-bootstrap.env}"
[ -f "$BOOTSTRAP_ENV" ] || die "Missing platform bootstrap env: $BOOTSTRAP_ENV"

set -a
# shellcheck disable=SC1090
. "$BOOTSTRAP_ENV"
set +a

require_command curl

port="${BIZ_WORKER_PORT:-3061}"
api_base="${NAVIGATOR_API_BASE:-http://127.0.0.1:8112}"

log "Checking Navigator API health"
curl -fsS "$api_base/actuator/health" >/dev/null
echo "  Navigator API: OK"

log "Checking LangGraph Biz Worker health"
curl -fsS "http://127.0.0.1:$port/health" >/dev/null
echo "  LangGraph Biz Worker: OK"

if [ -f "${BOOTSTRAP_REPORT_FILE:-$RUNTIME_DIR/platform-bootstrap-report.json}" ]; then
  echo "  Report: ${BOOTSTRAP_REPORT_FILE:-$RUNTIME_DIR/platform-bootstrap-report.json}"
fi

if [ -f "${TMS_UPSTREAM_ENV_FILE:-$RUNTIME_DIR/tms-upstream.env}" ]; then
  echo "  TMS env: ${TMS_UPSTREAM_ENV_FILE:-$RUNTIME_DIR/tms-upstream.env}"
fi
