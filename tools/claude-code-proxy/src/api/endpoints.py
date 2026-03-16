from fastapi import APIRouter, HTTPException, Request, Header, Depends
from fastapi.responses import JSONResponse, StreamingResponse
from datetime import datetime
import copy
import json
import uuid
from typing import Dict, Optional, Tuple

import httpx

from src.core.config import config
from src.core.logging import logger
from src.core.client import OpenAIClient
from src.core.key_pool import BackendKey
from src.core.truncation import truncate_for_retry
from src.models.claude import ClaudeMessagesRequest, ClaudeTokenCountRequest
from src.conversion.request_converter import convert_claude_to_openai
from src.conversion.response_converter import (
    convert_openai_to_claude_response,
    convert_openai_streaming_to_claude_with_cancellation,
)
from src.core.model_manager import model_manager
from src.core.fixture_capture import fixture_capture

router = APIRouter()

# Get custom headers from config
custom_headers = config.get_custom_headers()

# Build one OpenAIClient per non-passthrough backend key
openai_clients: Dict[str, OpenAIClient] = {}
for key_name, backend_key in config.key_pool.keys.items():
    if not backend_key.passthrough:
        openai_clients[key_name] = OpenAIClient(
            backend_key.api_key,
            backend_key.base_url,
            config.request_timeout,
            api_version=config.azure_api_version,
            custom_headers=custom_headers,
        )


def _extract_client_key(x_api_key: Optional[str], authorization: Optional[str]) -> Optional[str]:
    """Extract client API key from request headers."""
    if x_api_key:
        return x_api_key
    if authorization and authorization.startswith("Bearer "):
        return authorization[7:]
    return None


async def validate_api_key(
    x_api_key: Optional[str] = Header(None),
    authorization: Optional[str] = Header(None),
) -> Optional[str]:
    """Validate the client's API key. Returns the client key for downstream use."""
    client_api_key = _extract_client_key(x_api_key, authorization)

    # If KEY_MAPPING is configured, validate via key pool
    if config.key_pool.has_mapping:
        if not client_api_key or not config.key_pool.validate_client_key(client_api_key):
            logger.warning("Invalid or unmapped client API key")
            raise HTTPException(
                status_code=401,
                detail="Invalid API key. Your key is not mapped to any backend.",
            )
        return client_api_key

    # Legacy: validate against ANTHROPIC_API_KEY if set
    if config.anthropic_api_key:
        if not client_api_key or not config.validate_client_api_key(client_api_key):
            logger.warning("Invalid API key provided by client")
            raise HTTPException(
                status_code=401,
                detail="Invalid API key. Please provide a valid Anthropic API key.",
            )

    return client_api_key


def _has_image_content(body_json: dict) -> bool:
    """Detect whether the request contains image content blocks.

    Checks all messages for Claude image blocks (type="image") or
    OpenAI image_url blocks (type="image_url").
    """
    messages = body_json.get("messages")
    if not isinstance(messages, list):
        return False
    for msg in messages:
        content = msg.get("content")
        if isinstance(content, list):
            for block in content:
                if isinstance(block, dict) and block.get("type") in ("image", "image_url"):
                    return True
    return False


def _select_backend(
    client_api_key: Optional[str],
    has_image: bool = False,
) -> Tuple[BackendKey, Optional[OpenAIClient]]:
    """Select the next backend key + client via round-robin.

    If the request contains images and VISION_BACKEND is configured,
    route to the designated vision backend instead of round-robin.
    Returns (backend_key, None) for passthrough backends.
    """
    # Vision routing: if configured and request contains images
    if has_image and config.vision_backend:
        vision_key = config.key_pool.get_vision_backend(config.vision_backend)
        if vision_key:
            logger.info(
                "Image detected → routing to vision backend [%s]", config.vision_backend
            )
            client = openai_clients.get(vision_key.name)
            return vision_key, client

    backend_key = config.key_pool.get_next_key(client_api_key)
    client = openai_clients.get(backend_key.name)
    return backend_key, client


def _strip_thinking_blocks(body_json: dict):
    """Strip 'thinking' type content blocks from messages.

    Some Anthropic-compatible backends (e.g. DashScope) don't support thinking
    blocks in message content. This removes them to avoid 422 errors.
    """
    messages = body_json.get("messages")
    if not isinstance(messages, list):
        return
    stripped = 0
    for msg in messages:
        content = msg.get("content")
        if isinstance(content, list):
            original_len = len(content)
            msg["content"] = [
                block for block in content
                if not (isinstance(block, dict) and block.get("type") == "thinking")
            ]
            stripped += original_len - len(msg["content"])
            # If all blocks were stripped, replace with empty text to avoid empty content
            if not msg["content"] and original_len > 0:
                msg["content"] = [{"type": "text", "text": ""}]
    if stripped:
        logger.info(f"Passthrough stripped {stripped} thinking block(s) from messages")


def _is_input_length_error(status_code: int, error_text: str) -> bool:
    """Check if the error is an input-length-exceeded error (e.g. DashScope 169984 limit)."""
    if status_code != 400:
        return False
    lower = error_text.lower()
    return "input length" in lower or "context length" in lower or "too many tokens" in lower


async def _forward_passthrough(
    raw_body: bytes,
    backend_key: BackendKey,
    stream: bool,
    http_request: Request,
    target_path: str = "/v1/messages",
    has_image: bool = False,
):
    """Forward raw request to an Anthropic-compatible backend without format conversion.

    Model name mapping is still applied (e.g. claude-opus-4-6 -> glm-5) based on
    per-key BIG_MODEL/MIDDLE_MODEL/SMALL_MODEL config.

    Error recovery: if the backend returns a 400 error related to input length,
    the request body is truncated (tool_results first, then sliding window) and
    retried once.  If the retry also fails, the original error is returned.
    """
    url = f"{backend_key.base_url.rstrip('/')}{target_path}"

    # Parse body for model mapping and stripping unsupported fields
    # Standard Anthropic Messages API fields that backends should accept
    _ANTHROPIC_KNOWN_FIELDS = {
        "model", "messages", "system", "max_tokens", "stream",
        "temperature", "top_p", "top_k", "stop_sequences",
        "tools", "tool_choice", "metadata",
    }
    body_json = None  # keep parsed body for potential truncation retry
    try:
        body_json = json.loads(raw_body)
        # Model name mapping (e.g. claude-opus-4-6 -> glm-5)
        # Also applies vision model override when has_image=True
        if "model" in body_json:
            original_model = body_json["model"]
            mapped_model = model_manager.map_claude_model_to_openai(
                original_model, backend_key, has_image=has_image
            )
            if mapped_model != original_model:
                body_json["model"] = mapped_model
                logger.info(f"Passthrough model mapping: {original_model} -> {mapped_model}")
        # Strip fields not in standard Anthropic API (e.g. thinking, context_management)
        unknown_fields = set(body_json.keys()) - _ANTHROPIC_KNOWN_FIELDS
        if unknown_fields:
            for field in unknown_fields:
                del body_json[field]
            logger.info(f"Passthrough stripped unsupported fields: {unknown_fields}")
        # Strip thinking blocks from message content arrays
        # (assistant messages may contain {"type":"thinking",...} from previous turns)
        _strip_thinking_blocks(body_json)
        # Log sanitized body info at INFO level for diagnostics
        msg_count = len(body_json.get("messages", []))
        has_tools = "tools" in body_json and body_json["tools"]
        tool_count = len(body_json["tools"]) if has_tools else 0
        logger.info(f"Passthrough body: keys={list(body_json.keys())}, msgs={msg_count}, tools={tool_count}")
        raw_body = json.dumps(body_json, ensure_ascii=False).encode("utf-8")
    except (json.JSONDecodeError, TypeError) as e:
        logger.warning(f"Passthrough body parse failed: {e}, forwarding raw body ({len(raw_body)} bytes)")

    # Build headers: auth + content-type + passthrough relevant client headers
    headers = {
        "x-api-key": backend_key.api_key,
        "content-type": "application/json",
    }
    # Forward anthropic-version from client, or use default
    anthropic_version = http_request.headers.get("anthropic-version", "2023-06-01")
    headers["anthropic-version"] = anthropic_version
    # Forward anthropic-beta if present
    anthropic_beta = http_request.headers.get("anthropic-beta")
    if anthropic_beta:
        headers["anthropic-beta"] = anthropic_beta

    if stream:
        # Streaming: no read timeout (responses can take minutes with extended thinking)
        timeout = httpx.Timeout(None, connect=10.0)

        async def _stream_passthrough():
            try:
                async with httpx.AsyncClient(timeout=timeout) as client:
                    async with client.stream("POST", url, content=raw_body, headers=headers) as resp:
                        if resp.status_code != 200:
                            error_body = await resp.aread()
                            error_text = error_body.decode(errors="replace")
                            logger.error(f"Passthrough stream error {resp.status_code}: {error_text[:1000]}")

                            # ── Truncation retry for input-length errors ──
                            if body_json is not None and _is_input_length_error(resp.status_code, error_text):
                                logger.warning("Input length exceeded — truncating and retrying once …")
                                truncated = truncate_for_retry(
                                    copy.deepcopy(body_json),
                                    tool_result_limit=config.truncation_tool_result_limit,
                                    keep_head=config.truncation_keep_head_messages,
                                    keep_tail=config.truncation_keep_tail_messages,
                                )
                                retry_raw = json.dumps(truncated, ensure_ascii=False).encode("utf-8")
                                logger.info(
                                    "Retry body size: %d bytes (original %d bytes)",
                                    len(retry_raw), len(raw_body),
                                )
                                async with client.stream("POST", url, content=retry_raw, headers=headers) as retry_resp:
                                    if retry_resp.status_code != 200:
                                        retry_err = await retry_resp.aread()
                                        logger.error(
                                            "Truncation retry also failed %d: %s",
                                            retry_resp.status_code,
                                            retry_err.decode(errors="replace")[:500],
                                        )
                                        # Fall through to return original error below
                                    else:
                                        logger.info("Truncation retry succeeded — streaming response")
                                        async for chunk in retry_resp.aiter_bytes():
                                            if await http_request.is_disconnected():
                                                logger.info("Passthrough: client disconnected during retry stream")
                                                break
                                            yield chunk
                                        return  # success — done

                            # Extract backend error message for client
                            backend_msg = f"Backend returned {resp.status_code}"
                            try:
                                err_json = json.loads(error_text)
                                if "error" in err_json and isinstance(err_json["error"], dict):
                                    backend_msg = err_json["error"].get("message", backend_msg)
                            except (json.JSONDecodeError, TypeError):
                                pass
                            error_payload = json.dumps({
                                "type": "error",
                                "error": {"type": "api_error", "message": backend_msg},
                            })
                            yield f"event: error\ndata: {error_payload}\n\n"
                            return
                        # Forward raw bytes to preserve SSE framing exactly
                        async for chunk in resp.aiter_bytes():
                            if await http_request.is_disconnected():
                                logger.info("Passthrough: client disconnected")
                                break
                            yield chunk
            except httpx.TimeoutException as e:
                logger.error(f"Passthrough timeout: {e}")
                error_payload = json.dumps({
                    "type": "error",
                    "error": {"type": "timeout", "message": "Backend request timed out"},
                })
                yield f"event: error\ndata: {error_payload}\n\n"
            except httpx.HTTPError as e:
                logger.error(f"Passthrough HTTP error: {e}")
                error_payload = json.dumps({
                    "type": "error",
                    "error": {"type": "api_error", "message": "Backend connection failed"},
                })
                yield f"event: error\ndata: {error_payload}\n\n"

        return StreamingResponse(
            _stream_passthrough(),
            media_type="text/event-stream",
            headers={
                "Cache-Control": "no-cache",
                "Connection": "keep-alive",
                "Access-Control-Allow-Origin": "*",
                "Access-Control-Allow-Headers": "*",
            },
        )
    else:
        timeout = httpx.Timeout(config.request_timeout, connect=10.0)
        async with httpx.AsyncClient(timeout=timeout) as client:
            resp = await client.post(url, content=raw_body, headers=headers)
            try:
                body = resp.json()
            except Exception:
                error_text = resp.text[:500]
                logger.error(f"Passthrough non-JSON response {resp.status_code}: {error_text}")
                body = {"type": "error", "error": {"type": "api_error", "message": error_text}}

            # ── Truncation retry for non-streaming input-length errors ──
            if resp.status_code != 200:
                error_str = json.dumps(body, ensure_ascii=False)[:1000]
                logger.error(f"Passthrough error {resp.status_code}: {error_str}")

                if body_json is not None and _is_input_length_error(resp.status_code, error_str):
                    logger.warning("Input length exceeded (non-stream) — truncating and retrying once …")
                    truncated = truncate_for_retry(
                        copy.deepcopy(body_json),
                        tool_result_limit=config.truncation_tool_result_limit,
                        keep_head=config.truncation_keep_head_messages,
                        keep_tail=config.truncation_keep_tail_messages,
                    )
                    retry_raw = json.dumps(truncated, ensure_ascii=False).encode("utf-8")
                    retry_resp = await client.post(url, content=retry_raw, headers=headers)
                    if retry_resp.status_code == 200:
                        logger.info("Truncation retry succeeded (non-stream)")
                        try:
                            return JSONResponse(status_code=200, content=retry_resp.json())
                        except Exception:
                            return JSONResponse(status_code=200, content={"type": "message"})
                    else:
                        logger.error("Truncation retry also failed %d (non-stream)", retry_resp.status_code)

            return JSONResponse(status_code=resp.status_code, content=body)


@router.post("/v1/messages")
async def create_message(
    http_request: Request,
    client_api_key: Optional[str] = Depends(validate_api_key),
):
    # Read body first so we can detect image content before selecting backend
    raw_body = await http_request.body()

    # Pre-parse JSON for image detection (best-effort, don't fail here)
    pre_parsed = None
    has_image = False
    try:
        pre_parsed = json.loads(raw_body)
        has_image = _has_image_content(pre_parsed)
    except (json.JSONDecodeError, TypeError):
        pass

    # Select backend (content-aware: may route to vision backend if images detected)
    backend_key, openai_client = _select_backend(client_api_key, has_image=has_image)

    # ── Fixture Capture: start session ──
    capture = fixture_capture.start_capture("messages")

    # Passthrough mode: bypass Pydantic validation entirely, forward raw request
    if backend_key.passthrough:
        try:
            body_json = pre_parsed or json.loads(raw_body)
            model = body_json.get("model", "unknown")
            stream = body_json.get("stream", False)
            capture.save_claude_request(body_json)
            capture.meta["mode"] = "passthrough"
        except (json.JSONDecodeError, TypeError):
            model, stream = "unknown", False
        mapped = model_manager.map_claude_model_to_openai(model, backend_key, has_image=has_image)
        logger.info(
            f"Request: model={model} -> {backend_key.name}({mapped}) [PASSTHROUGH], stream={stream}"
            + (" [VISION]" if has_image else "")
        )
        capture.finish()  # passthrough 采集仅保存请求（响应在 _forward_passthrough 中直接转发）
        return await _forward_passthrough(raw_body, backend_key, stream, http_request, has_image=has_image)

    # Non-passthrough: validate request body with Pydantic
    try:
        body_json = pre_parsed or json.loads(raw_body)
        capture.save_claude_request(body_json)
        capture.meta["mode"] = "conversion"
        request = ClaudeMessagesRequest.model_validate(body_json)
    except json.JSONDecodeError as e:
        capture.finish(error=f"Invalid JSON: {e}")
        raise HTTPException(status_code=400, detail=f"Invalid JSON body: {e}")
    except Exception as e:
        capture.finish(error=f"Validation: {e}")
        raise HTTPException(status_code=422, detail=str(e))

    logger.info(
        f"Request: model={request.model} -> {backend_key.name}"
        f"({model_manager.map_claude_model_to_openai(request.model, backend_key, has_image=has_image)})"
        f", stream={request.stream}"
        + (" [VISION]" if has_image else "")
    )

    try:
        # Generate unique request ID for cancellation tracking
        request_id = str(uuid.uuid4())

        # Convert Claude request to OpenAI format (with per-key model mapping)
        openai_request = convert_claude_to_openai(request, model_manager, backend_key, has_image=has_image)
        capture.save_openai_request(openai_request)

        # Check if client disconnected before processing
        if await http_request.is_disconnected():
            capture.finish(error="Client disconnected")
            raise HTTPException(status_code=499, detail="Client disconnected")

        if request.stream:
            # Streaming response — wrap generator to capture chunks
            try:
                openai_stream = openai_client.create_chat_completion_stream(
                    openai_request, request_id
                )

                async def _capture_streaming_wrapper(inner_gen):
                    """Wrap the Claude SSE generator to capture each event."""
                    try:
                        async for event_text in inner_gen:
                            capture.save_claude_sse_event(event_text)
                            yield event_text
                    finally:
                        capture.finish()

                claude_stream = convert_openai_streaming_to_claude_with_cancellation(
                    openai_stream,
                    request,
                    logger,
                    http_request,
                    openai_client,
                    request_id,
                )
                return StreamingResponse(
                    _capture_streaming_wrapper(claude_stream),
                    media_type="text/event-stream",
                    headers={
                        "Cache-Control": "no-cache",
                        "Connection": "keep-alive",
                        "Access-Control-Allow-Origin": "*",
                        "Access-Control-Allow-Headers": "*",
                    },
                )
            except HTTPException as e:
                logger.error(f"Streaming error: {e.detail}")
                import traceback

                logger.error(traceback.format_exc())
                error_message = openai_client.classify_openai_error(e.detail)
                error_response = {
                    "type": "error",
                    "error": {"type": "api_error", "message": error_message},
                }
                capture.finish(error=f"Streaming error: {e.detail}")
                return JSONResponse(status_code=e.status_code, content=error_response)
        else:
            # Non-streaming response
            openai_response = await openai_client.create_chat_completion(
                openai_request, request_id
            )
            capture.save_openai_response(openai_response)
            claude_response = convert_openai_to_claude_response(
                openai_response, request
            )
            capture.save_claude_response(claude_response)
            capture.finish()
            return claude_response
    except HTTPException:
        capture.finish(error="HTTPException")
        raise
    except Exception as e:
        import traceback

        logger.error(f"Unexpected error processing request: {e}")
        logger.error(traceback.format_exc())
        error_message = openai_client.classify_openai_error(str(e))
        capture.finish(error=str(e))
        raise HTTPException(status_code=500, detail=error_message)


@router.post("/v1/messages/count_tokens")
async def count_tokens(
    http_request: Request,
    client_api_key: Optional[str] = Depends(validate_api_key),
):
    # Passthrough mode: forward count_tokens to backend (bypass Pydantic)
    backend_key = config.key_pool.get_next_key(client_api_key)
    raw_body = await http_request.body()

    if backend_key.passthrough:
        logger.info(f"count_tokens -> {backend_key.name} [PASSTHROUGH]")
        return await _forward_passthrough(
            raw_body, backend_key, stream=False, http_request=http_request,
            target_path="/v1/messages/count_tokens",
        )

    # Non-passthrough: validate with Pydantic
    try:
        body_json = json.loads(raw_body)
        request = ClaudeTokenCountRequest.model_validate(body_json)
    except json.JSONDecodeError as e:
        raise HTTPException(status_code=400, detail=f"Invalid JSON body: {e}")
    except Exception as e:
        raise HTTPException(status_code=422, detail=str(e))

    try:
        total_chars = 0

        # Count system message characters
        if request.system:
            if isinstance(request.system, str):
                total_chars += len(request.system)
            elif isinstance(request.system, list):
                for block in request.system:
                    if hasattr(block, "text"):
                        total_chars += len(block.text)

        # Count message characters
        for msg in request.messages:
            if msg.content is None:
                continue
            elif isinstance(msg.content, str):
                total_chars += len(msg.content)
            elif isinstance(msg.content, list):
                for block in msg.content:
                    if hasattr(block, "text") and block.text is not None:
                        total_chars += len(block.text)

        # Rough estimation: 4 characters per token
        estimated_tokens = max(1, total_chars // 4)

        return {"input_tokens": estimated_tokens}

    except Exception as e:
        logger.error(f"Error counting tokens: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/health")
async def health_check():
    """Health check endpoint"""
    key_pool = config.key_pool
    return {
        "status": "healthy",
        "timestamp": datetime.now().isoformat(),
        "backend_keys": key_pool.all_key_names(),
        "backend_count": len(key_pool.keys),
        "key_mapping_enabled": key_pool.has_mapping,
        "client_api_key_validation": key_pool.has_mapping or bool(config.anthropic_api_key),
    }


@router.get("/test-connection")
async def test_connection():
    """Test API connectivity to each backend"""
    results = []
    for key_name, backend_key in config.key_pool.keys.items():
        if backend_key.passthrough:
            results.append({
                "backend": key_name,
                "status": "skipped",
                "reason": "passthrough (test via /v1/messages directly)",
            })
            continue
        client = openai_clients[key_name]
        try:
            test_response = await client.create_chat_completion(
                {
                    "model": backend_key.small_model,
                    "messages": [{"role": "user", "content": "Hello"}],
                    "max_tokens": 5,
                }
            )
            results.append({
                "backend": key_name,
                "status": "success",
                "model_used": backend_key.small_model,
                "response_id": test_response.get("id", "unknown"),
            })
        except Exception as e:
            results.append({
                "backend": key_name,
                "status": "failed",
                "error": str(e),
            })

    all_ok = all(r["status"] in ("success", "skipped") for r in results)
    status_code = 200 if all_ok else 503
    return JSONResponse(
        status_code=status_code,
        content={
            "status": "success" if all_ok else "partial_failure",
            "timestamp": datetime.now().isoformat(),
            "backends": results,
        },
    )


@router.get("/")
async def root():
    """Root endpoint"""
    key_pool = config.key_pool
    backends_info = {}
    for name, bk in key_pool.keys.items():
        info = {"base_url": bk.base_url, "passthrough": bk.passthrough}
        if not bk.passthrough:
            info.update({
                "big_model": bk.big_model,
                "middle_model": bk.middle_model,
                "small_model": bk.small_model,
            })
        if bk.vision_model:
            info["vision_model"] = bk.vision_model
        backends_info[name] = info

    vision_info = {}
    if config.vision_backend:
        vision_info["vision_backend"] = config.vision_backend
    # Show which backends have vision models
    vision_models = {name: bk.vision_model for name, bk in key_pool.keys.items() if bk.vision_model}
    if vision_models:
        vision_info["vision_models"] = vision_models

    return {
        "message": "Claude-to-OpenAI API Proxy v1.0.0",
        "status": "running",
        "config": {
            "backend_count": len(key_pool.keys),
            "backends": backends_info,
            "key_mapping_enabled": key_pool.has_mapping,
            "max_tokens_limit": config.max_tokens_limit,
            **({"vision": vision_info} if vision_info else {}),
        },
        "endpoints": {
            "messages": "/v1/messages",
            "count_tokens": "/v1/messages/count_tokens",
            "health": "/health",
            "test_connection": "/test-connection",
        },
    }
