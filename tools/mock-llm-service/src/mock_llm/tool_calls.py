import hashlib
import json
import uuid
from typing import Any, Dict, Optional


def normalize_tool_call(tool_call: Dict[str, Any], index: int, seed: Optional[str] = None) -> Dict[str, Any]:
    """Normalize scripted tool calls to OpenAI Chat Completions function-call shape."""
    if not isinstance(tool_call, dict):
        raise ValueError("tool_call must be an object")

    function = tool_call.get("function")
    if isinstance(function, dict):
        name = function.get("name")
        arguments = function.get("arguments", "")
    else:
        name = tool_call.get("name")
        arguments = tool_call.get("args", tool_call.get("arguments", ""))

    if not name:
        raise ValueError("tool_call function name is required")

    if isinstance(arguments, (dict, list)):
        arguments = json.dumps(arguments, ensure_ascii=False)
    elif arguments is None:
        arguments = ""
    elif not isinstance(arguments, str):
        arguments = str(arguments)

    return {
        "id": tool_call.get("id") or _tool_call_id(seed, index, name),
        "type": tool_call.get("type", "function"),
        "function": {
            "name": name,
            "arguments": arguments,
        },
    }


def _tool_call_id(seed: Optional[str], index: int, name: str) -> str:
    if seed:
        value = f"{seed}|{index}|{name}"
        return f"call_{hashlib.sha256(value.encode('utf-8')).hexdigest()[:12]}"
    return f"call_{uuid.uuid4().hex[:8]}"
