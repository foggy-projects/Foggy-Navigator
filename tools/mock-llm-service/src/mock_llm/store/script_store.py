import copy
import hashlib
import json
import re
import time
from dataclasses import dataclass
from typing import Any, Dict, List, Optional

from ..models import (
    ChatMessage,
    DebugRequestRecord,
    MockResponseConfig,
    ScriptRegistration,
    ScriptRegistrationResult,
    ScriptedTurn,
)


CURSOR_PATTERN = re.compile(r"\bnext:([A-Za-z0-9_.-]+):([0-9]{3,})\b")
SECRET_KEYS = {
    "authorization",
    "api_key",
    "apikey",
    "access_token",
    "accesstoken",
    "token",
    "secret",
    "clientappsecret",
    "task_scoped_token",
    "taskscopedtoken",
}


@dataclass
class RegisteredScript:
    trace_id: str
    scenario_id: Optional[str]
    expires_at: Optional[float]
    turns: Dict[str, List[ScriptedTurn]]


@dataclass
class ScriptMatch:
    trace_id: str
    scenario_id: Optional[str]
    cursor: str
    turn_index: Optional[str]
    response: MockResponseConfig
    request_hash: str
    repeated: bool


class ScriptStore:
    """In-memory scripted E2E response store."""

    def __init__(self) -> None:
        self._scripts: Dict[str, RegisteredScript] = {}
        self._debug_records: List[DebugRequestRecord] = []
        self._seen_requests: set[str] = set()
        self._cursor_match_counts: Dict[str, int] = {}

    def register(self, script: ScriptRegistration) -> ScriptRegistrationResult:
        trace_id = _require_text(script.traceId, "traceId is required")
        if not script.turns:
            raise ValueError("turns is required")

        turns: Dict[str, List[ScriptedTurn]] = {}
        for turn in script.turns:
            cursor_trace, _ = parse_cursor(turn.cursor)
            if cursor_trace != trace_id:
                raise ValueError(f"cursor traceId mismatch: {turn.cursor}")
            turns.setdefault(turn.cursor, []).append(turn)

        self._clear_trace_match_state(trace_id)
        ttl = script.expiresInSeconds if script.expiresInSeconds is not None else 3600
        expires_at = time.time() + ttl if ttl > 0 else None
        self._scripts[trace_id] = RegisteredScript(
            trace_id=trace_id,
            scenario_id=script.scenarioId,
            expires_at=expires_at,
            turns=turns,
        )
        return ScriptRegistrationResult(
            traceId=trace_id,
            scenarioId=script.scenarioId,
            turns=len(script.turns),
            expiresAt=expires_at,
        )

    def cleanup(self, trace_id: str) -> bool:
        removed = self._scripts.pop(trace_id, None) is not None
        self._debug_records = [r for r in self._debug_records if r.traceId != trace_id]
        self._clear_trace_match_state(trace_id)
        return removed

    def _clear_trace_match_state(self, trace_id: str) -> None:
        self._seen_requests = {k for k in self._seen_requests if not k.startswith(trace_id + "|")}
        self._cursor_match_counts = {
            key: value
            for key, value in self._cursor_match_counts.items()
            if not key.startswith(trace_id + "|")
        }

    def match(self, model: str, messages: List[ChatMessage]) -> Optional[ScriptMatch]:
        self._purge_expired()
        cursor = extract_latest_cursor(messages)
        if not cursor:
            return None
        trace_id, turn_index = parse_cursor(cursor)
        script = self._scripts.get(trace_id)
        if not script:
            return None
        turns = script.turns.get(cursor)
        if not turns:
            return None
        cursor_key = f"{trace_id}|{cursor}"
        hit_count = self._cursor_match_counts.get(cursor_key, 0)
        turn = turns[min(hit_count, len(turns) - 1)]
        self._cursor_match_counts[cursor_key] = hit_count + 1

        request_hash = hash_request({"model": model, "messages": _messages_to_data(messages)})
        seen_key = f"{trace_id}|{cursor}|{request_hash}"
        repeated = seen_key in self._seen_requests
        self._seen_requests.add(seen_key)
        return ScriptMatch(
            trace_id=trace_id,
            scenario_id=script.scenario_id,
            cursor=cursor,
            turn_index=turn_index,
            response=turn.response,
            request_hash=request_hash,
            repeated=repeated,
        )

    def record_request(
        self,
        match: ScriptMatch,
        model: str,
        request_payload: Dict[str, Any],
        response_summary: Dict[str, Any],
    ) -> None:
        self._debug_records.append(
            DebugRequestRecord(
                traceId=match.trace_id,
                scenarioId=match.scenario_id,
                cursor=match.cursor,
                turnIndex=match.turn_index,
                model=model,
                requestHash=match.request_hash,
                matched=True,
                responseSummary=_sanitize(response_summary),
                request=_sanitize(request_payload),
                createdAt=time.time(),
            )
        )

    def debug_requests(self, trace_id: str) -> List[DebugRequestRecord]:
        self._purge_expired()
        return [r for r in self._debug_records if r.traceId == trace_id]

    def _purge_expired(self) -> None:
        now = time.time()
        expired = [
            trace_id
            for trace_id, script in self._scripts.items()
            if script.expires_at is not None and script.expires_at < now
        ]
        for trace_id in expired:
            self.cleanup(trace_id)


def extract_latest_cursor(messages: List[ChatMessage]) -> Optional[str]:
    for message in reversed(messages):
        if message.role == "tool":
            cursor = _find_cursor(message.content)
            if cursor:
                return cursor
        if message.role == "assistant":
            if message.tool_calls:
                for tool_call in reversed(message.tool_calls):
                    cursor = _find_cursor(tool_call.function.arguments)
                    if cursor:
                        return cursor
            cursor = _find_cursor(message.content)
            if cursor:
                return cursor
        if message.role == "user":
            cursor = _find_cursor(message.content)
            if cursor:
                return cursor
        if message.role == "system":
            cursor = _find_cursor(message.content)
            if cursor:
                return cursor
    return None


def parse_cursor(cursor: str) -> tuple[str, str]:
    match = CURSOR_PATTERN.search(cursor or "")
    if not match:
        raise ValueError(f"invalid cursor: {cursor}")
    return match.group(1), match.group(2)


def hash_request(payload: Dict[str, Any]) -> str:
    encoded = json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(encoded.encode("utf-8")).hexdigest()


def _find_cursor(value: Any) -> Optional[str]:
    if not value:
        return None
    if isinstance(value, list):
        for item in reversed(value):
            cursor = _find_cursor(item)
            if cursor:
                return cursor
        return None
    if isinstance(value, dict):
        for item in reversed(list(value.values())):
            cursor = _find_cursor(item)
            if cursor:
                return cursor
        return None
    if not isinstance(value, str):
        value = str(value)
    matches = list(CURSOR_PATTERN.finditer(value))
    return matches[-1].group(0) if matches else None


def _messages_to_data(messages: List[ChatMessage]) -> List[Dict[str, Any]]:
    return [m.model_dump(mode="json", exclude_none=True) for m in messages]


def _sanitize(value: Any) -> Any:
    if isinstance(value, dict):
        result = {}
        for key, item in value.items():
            if key.lower() in SECRET_KEYS:
                result[key] = "***"
            else:
                result[key] = _sanitize(item)
        return result
    if isinstance(value, list):
        return [_sanitize(item) for item in value]
    return copy.deepcopy(value)


def _require_text(value: Optional[str], message: str) -> str:
    if value is None or not value.strip():
        raise ValueError(message)
    return value.strip()
