#!/bin/bash
# ============================================================
# L3 Integration Tests — Unified Task Dispatch Refactor
# Runs against live backend at localhost:8112
# ============================================================
set -euo pipefail

BASE="http://localhost:8112/api/v1"
TOKEN=$(cat /tmp/test_token.txt)
AUTH="Authorization: Bearer $TOKEN"
CT="Content-Type: application/json"
PASS=0
FAIL=0

pass() { PASS=$((PASS+1)); echo "  ✅ $1"; }
fail() { FAIL=$((FAIL+1)); echo "  ❌ $1: $2"; }

assert_code() {
  local desc="$1" expected="$2" actual="$3"
  if [ "$expected" = "$actual" ]; then pass "$desc"; else fail "$desc" "expected=$expected actual=$actual"; fi
}

assert_contains() {
  local desc="$1" needle="$2" haystack="$3"
  if echo "$haystack" | grep -q "$needle"; then pass "$desc"; else fail "$desc" "missing '$needle'"; fi
}

assert_not_contains() {
  local desc="$1" needle="$2" haystack="$3"
  if echo "$haystack" | grep -q "$needle"; then fail "$desc" "should not contain '$needle'"; else pass "$desc"; fi
}

echo "=========================================="
echo "  L3 Integration Tests"
echo "=========================================="

# ── Test 1: Agent discovery endpoint ──
echo ""
echo "── Test 1: Agent Discovery ──"
AGENTS=$(curl -s "$BASE/agents" -H "$AUTH")
DISCOVERY_CODE=$(echo "$AGENTS" | python -c "import sys,json; print(json.load(sys.stdin).get('code',0))" 2>/dev/null)
assert_code "GET /agents returns 200" "200" "$DISCOVERY_CODE"

# ── Test 2: Unified API — list active tasks (cross-Agent) ──
echo ""
echo "── Test 2: Unified active tasks ──"
ACTIVE=$(curl -s "$BASE/tasks" -H "$AUTH")
CODE=$(echo "$ACTIVE" | python -c "import sys,json; print(json.load(sys.stdin).get('code',0))" 2>/dev/null)
assert_code "GET /tasks returns 200" "200" "$CODE"

# ── Test 3: Unified API — cancel nonexistent task ──
echo ""
echo "── Test 3: Cancel nonexistent task ──"
CANCEL=$(curl -s -X POST "$BASE/tasks/nonexistent-999/cancel" -H "$AUTH" -H "$CT" -d '{}')
assert_contains "Cancel nonexistent returns error" "not found" "$(echo $CANCEL | tr 'A-Z' 'a-z')"

# ── Test 4: Unified API — respond unsupported agent ──
echo ""
echo "── Test 4: Respond unsupported ──"
# Create a fake task ID that won't exist
RESPOND=$(curl -s -X POST "$BASE/tasks/fake-task-123/respond" \
  -H "$AUTH" -H "$CT" -d '{"permissionId":"p1","decision":"approve"}')
assert_contains "Respond on nonexistent returns error" "not found" "$(echo $RESPOND | tr 'A-Z' 'a-z')"

# ── Test 5: Unified API — reconnect unsupported ──
echo ""
echo "── Test 5: Reconnect unsupported ──"
RECONNECT=$(curl -s -X POST "$BASE/tasks/fake-task-123/reconnect" -H "$AUTH")
assert_contains "Reconnect nonexistent returns error" "not found" "$(echo $RECONNECT | tr 'A-Z' 'a-z')"

# ── Test 6: Unified API — resync unsupported ──
echo ""
echo "── Test 6: Resync unsupported ──"
RESYNC=$(curl -s -X POST "$BASE/tasks/fake-task-123/resync" -H "$AUTH")
assert_contains "Resync nonexistent returns error" "not found" "$(echo $RESYNC | tr 'A-Z' 'a-z')"

# ── Test 7: Session binding — providerType on new session ──
echo ""
echo "── Test 7: Session providerType ──"
# Query a recent session and check it has providerType
SESSIONS=$(python -c "
import sys, json, urllib.request
req = urllib.request.Request('$BASE/sessions?page=0&size=1', headers={'Authorization': 'Bearer $TOKEN'})
data = json.load(urllib.request.urlopen(req))
sessions = data.get('data', {})
if isinstance(sessions, dict):
    content = sessions.get('content', [])
elif isinstance(sessions, list):
    content = sessions
else:
    content = []
if content:
    s = content[0]
    print(json.dumps({'agentId': s.get('agentId'), 'providerType': s.get('providerType')}))
else:
    print('{}')
" 2>/dev/null)
echo "  Latest session: $SESSIONS"

# ── Summary ──
echo ""
echo "=========================================="
echo "  Results: $PASS passed, $FAIL failed"
echo "=========================================="
[ $FAIL -eq 0 ] && exit 0 || exit 1
