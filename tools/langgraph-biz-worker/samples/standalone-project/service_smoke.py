from __future__ import annotations

import json
import os
import urllib.request
from typing import Any


BASE_URL = os.environ.get("BIZ_WORKER_BASE_URL", "http://127.0.0.1:3061").rstrip("/")
TOKEN = os.environ.get("BIZ_WORKER_WORKER_TOKEN", "")


def main() -> None:
    status = request("GET", "/api/v1/standalone/status")
    print(json.dumps(status, ensure_ascii=False, indent=2))

    result = request(
        "POST",
        "/api/v1/ask",
        {
            "skill_name": "order-assistant",
            "message": "Check order O-1001",
            "context": {"account_id": "demo-account", "order_id": "O-1001"},
        },
    )
    print(json.dumps(result, ensure_ascii=False, indent=2))


def request(method: str, path: str, payload: dict[str, Any] | None = None) -> dict[str, Any]:
    body = json.dumps(payload).encode("utf-8") if payload is not None else None
    headers = {"Content-Type": "application/json"}
    if TOKEN:
        headers["Authorization"] = f"Bearer {TOKEN}"
    req = urllib.request.Request(
        f"{BASE_URL}{path}",
        data=body,
        headers=headers,
        method=method,
    )
    with urllib.request.urlopen(req, timeout=30) as response:
        return json.loads(response.read().decode("utf-8"))


if __name__ == "__main__":
    main()
