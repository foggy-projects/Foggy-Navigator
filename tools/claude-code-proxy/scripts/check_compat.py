#!/usr/bin/env python3
"""
Anthropic API Compatibility Checker

Checks whether a given backend supports various Claude Code features
by sending minimal real requests (max_tokens=1).

Usage:
  # Single backend
  python scripts/check_compat.py --url https://example.com/v1 --key sk-xxx --model glm-5

  # All backends from .env
  python scripts/check_compat.py --env .env
"""

import argparse
import asyncio
import json
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, List, Optional, Tuple

import httpx

# ---------------------------------------------------------------------------
# Data types
# ---------------------------------------------------------------------------

@dataclass
class Backend:
    name: str
    url: str       # base URL (e.g. https://coding.dashscope.aliyuncs.com/apps/anthropic)
    key: str
    model: str


@dataclass
class CheckResult:
    name: str
    passed: bool
    detail: str = ""


# ---------------------------------------------------------------------------
# Request helpers
# ---------------------------------------------------------------------------

def _headers(api_key: str) -> dict:
    return {
        "x-api-key": api_key,
        "anthropic-version": "2023-06-01",
        "content-type": "application/json",
    }


def _base_payload(model: str) -> dict:
    return {
        "model": model,
        "max_tokens": 1,
        "messages": [{"role": "user", "content": "Hi"}],
    }


# ---------------------------------------------------------------------------
# Individual checks
# ---------------------------------------------------------------------------

async def check_basic(client: httpx.AsyncClient, url: str, key: str, model: str) -> CheckResult:
    """1. Basic completion — simplest messages request."""
    payload = _base_payload(model)
    try:
        resp = await client.post(f"{url}/v1/messages", headers=_headers(key), json=payload)
        if resp.status_code == 200:
            return CheckResult("Basic completion", True)
        return CheckResult("Basic completion", False, f"{resp.status_code}: {_err(resp)}")
    except Exception as e:
        return CheckResult("Basic completion", False, str(e))


async def check_streaming(client: httpx.AsyncClient, url: str, key: str, model: str) -> CheckResult:
    """2. Streaming — stream=true, look for SSE event."""
    payload = {**_base_payload(model), "stream": True}
    try:
        async with client.stream("POST", f"{url}/v1/messages", headers=_headers(key), json=payload) as resp:
            if resp.status_code != 200:
                body = await resp.aread()
                return CheckResult("Streaming", False, f"{resp.status_code}: {body.decode(errors='replace')[:200]}")
            # Check for at least one SSE event
            async for line in resp.aiter_lines():
                if line.startswith("event:"):
                    return CheckResult("Streaming", True)
            return CheckResult("Streaming", False, "No SSE events received")
    except Exception as e:
        return CheckResult("Streaming", False, str(e))


async def check_system(client: httpx.AsyncClient, url: str, key: str, model: str) -> CheckResult:
    """3. System message — add system field."""
    payload = {**_base_payload(model), "system": "You are a helpful assistant."}
    try:
        resp = await client.post(f"{url}/v1/messages", headers=_headers(key), json=payload)
        if resp.status_code == 200:
            return CheckResult("System message", True)
        return CheckResult("System message", False, f"{resp.status_code}: {_err(resp)}")
    except Exception as e:
        return CheckResult("System message", False, str(e))


async def check_tools(client: httpx.AsyncClient, url: str, key: str, model: str) -> CheckResult:
    """4. Tools — add tools + tool_choice."""
    payload = {
        **_base_payload(model),
        "tools": [{
            "name": "get_weather",
            "description": "Get current weather",
            "input_schema": {
                "type": "object",
                "properties": {"city": {"type": "string"}},
                "required": ["city"],
            },
        }],
        "tool_choice": {"type": "auto"},
    }
    try:
        resp = await client.post(f"{url}/v1/messages", headers=_headers(key), json=payload)
        if resp.status_code == 200:
            return CheckResult("Tools", True)
        return CheckResult("Tools", False, f"{resp.status_code}: {_err(resp)}")
    except Exception as e:
        return CheckResult("Tools", False, str(e))


async def check_thinking(client: httpx.AsyncClient, url: str, key: str, model: str) -> CheckResult:
    """5. Extended Thinking — add thinking field."""
    payload = {
        **_base_payload(model),
        "thinking": {"type": "enabled", "budget_tokens": 1024},
    }
    # Thinking requires max_tokens >= budget_tokens in real Anthropic API
    payload["max_tokens"] = 2048
    try:
        resp = await client.post(f"{url}/v1/messages", headers=_headers(key), json=payload)
        if resp.status_code == 200:
            return CheckResult("Extended Thinking", True)
        return CheckResult("Extended Thinking", False, f"{resp.status_code}: {_err(resp)}")
    except Exception as e:
        return CheckResult("Extended Thinking", False, str(e))


async def check_metadata(client: httpx.AsyncClient, url: str, key: str, model: str) -> CheckResult:
    """6. Metadata — add metadata field."""
    payload = {**_base_payload(model), "metadata": {"user_id": "test-user"}}
    try:
        resp = await client.post(f"{url}/v1/messages", headers=_headers(key), json=payload)
        if resp.status_code == 200:
            return CheckResult("Metadata", True)
        return CheckResult("Metadata", False, f"{resp.status_code}: {_err(resp)}")
    except Exception as e:
        return CheckResult("Metadata", False, str(e))


async def check_token_counting(client: httpx.AsyncClient, url: str, key: str, model: str) -> CheckResult:
    """7. Token Counting — POST /v1/messages/count_tokens."""
    payload = {
        "model": model,
        "messages": [{"role": "user", "content": "Hi"}],
    }
    try:
        resp = await client.post(f"{url}/v1/messages/count_tokens", headers=_headers(key), json=payload)
        if resp.status_code == 200:
            return CheckResult("Token Counting", True)
        return CheckResult("Token Counting", False, f"{resp.status_code}: {_err(resp)}")
    except Exception as e:
        return CheckResult("Token Counting", False, str(e))


# All checks in order
ALL_CHECKS = [
    check_basic,
    check_streaming,
    check_system,
    check_tools,
    check_thinking,
    check_metadata,
    check_token_counting,
]


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _err(resp: httpx.Response) -> str:
    """Extract short error message from response."""
    try:
        body = resp.json()
        if "error" in body:
            err = body["error"]
            if isinstance(err, dict):
                return err.get("message", json.dumps(err))[:200]
            return str(err)[:200]
        return resp.text[:200]
    except Exception:
        return resp.text[:200]


# ---------------------------------------------------------------------------
# .env parser
# ---------------------------------------------------------------------------

def parse_env_backends(env_path: str) -> List[Backend]:
    """Parse .env file and extract passthrough backends."""
    try:
        from dotenv import dotenv_values
    except ImportError:
        print("Error: python-dotenv is required. Install with: pip install python-dotenv")
        sys.exit(1)

    values = dotenv_values(env_path)
    # Strip quotes that dotenv might leave
    cleaned: Dict[str, str] = {}
    for k, v in values.items():
        if v is not None:
            cleaned[k] = v.strip().strip('"').strip("'")

    # Find named keys: OPENAI_API_KEY_<NAME>
    prefix = "OPENAI_API_KEY_"
    backends: List[Backend] = []
    for env_key, env_value in cleaned.items():
        if not env_key.startswith(prefix) or not env_value:
            continue
        name = env_key[len(prefix):]
        if not name:
            continue
        api_key = env_value
        base_url = cleaned.get(f"OPENAI_BASE_URL_{name}", "https://api.openai.com/v1")
        # Use BIG_MODEL_<NAME> as the test model (most representative)
        model = cleaned.get(f"BIG_MODEL_{name}", cleaned.get("BIG_MODEL", "gpt-4o"))
        backends.append(Backend(name=name, url=base_url.rstrip("/"), key=api_key, model=model))

    # Fallback: single-key mode
    if not backends:
        single_key = cleaned.get("OPENAI_API_KEY", "")
        if single_key:
            base_url = cleaned.get("OPENAI_BASE_URL", "https://api.openai.com/v1")
            model = cleaned.get("BIG_MODEL", "gpt-4o")
            backends.append(Backend(name="DEFAULT", url=base_url.rstrip("/"), key=single_key, model=model))

    return backends


# ---------------------------------------------------------------------------
# Runner
# ---------------------------------------------------------------------------

async def run_checks(backend: Backend) -> List[CheckResult]:
    """Run all compatibility checks against a single backend."""
    timeout = httpx.Timeout(30.0, connect=10.0)
    results: List[CheckResult] = []
    async with httpx.AsyncClient(timeout=timeout) as client:
        for check_fn in ALL_CHECKS:
            result = await check_fn(client, backend.url, backend.key, backend.model)
            results.append(result)
    return results


def print_results(backend: Backend, results: List[CheckResult]):
    """Print formatted results for a backend."""
    print(f"\n=== Compatibility Check: {backend.name} ({backend.url}) ===")
    print(f"Model: {backend.model}\n")

    max_name_len = max(len(r.name) for r in results)
    passed = 0
    total = len(results)

    for r in results:
        dots = "." * (max_name_len + 6 - len(r.name))
        if r.passed:
            mark = "\u2713"
            detail = ""
            passed += 1
        else:
            mark = "\u2717"
            detail = f" ({r.detail})" if r.detail else ""
        print(f"  {r.name} {dots} {mark}{detail}")

    print(f"\nSummary: {passed}/{total} features supported")

    # Recommendation
    if passed == total:
        print("Recommendation: Full compatibility, all features supported")
    elif passed >= 4:
        unsupported = [r.name for r in results if not r.passed]
        print(f"Recommendation: Use with PASSTHROUGH=true, unsupported features ({', '.join(unsupported)}) will be auto-stripped")
    elif results[0].passed:
        print("Recommendation: Basic functionality works, but many advanced features are unsupported")
    else:
        print("Recommendation: Backend is not reachable or authentication failed, check URL and API key")


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def main():
    parser = argparse.ArgumentParser(
        description="Check Anthropic API compatibility of a backend",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  python scripts/check_compat.py --url https://example.com/apps/anthropic --key sk-xxx --model glm-5
  python scripts/check_compat.py --env .env
        """,
    )
    parser.add_argument("--url", help="Backend base URL (e.g. https://coding.dashscope.aliyuncs.com/apps/anthropic)")
    parser.add_argument("--key", help="API key for the backend")
    parser.add_argument("--model", help="Model name to test with")
    parser.add_argument("--env", help="Path to .env file (checks all backends defined in it)")
    args = parser.parse_args()

    if args.env:
        env_path = Path(args.env)
        if not env_path.exists():
            print(f"Error: .env file not found: {args.env}")
            sys.exit(1)
        backends = parse_env_backends(str(env_path))
        if not backends:
            print("No backends found in .env file")
            sys.exit(1)
    elif args.url and args.key and args.model:
        backends = [Backend(name="CLI", url=args.url.rstrip("/"), key=args.key, model=args.model)]
    else:
        parser.print_help()
        print("\nError: Provide either --env or all of --url, --key, --model")
        sys.exit(1)

    for backend in backends:
        results = asyncio.run(run_checks(backend))
        print_results(backend, results)

    print()


if __name__ == "__main__":
    main()
