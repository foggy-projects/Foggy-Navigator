"""
Anthropic Messages API 兼容端点

POST /v1/messages — Claude Messages API 格式
支持同步和流式响应，使用与 OpenAI 端点相同的 YAML 匹配规则。

优先使用 rule.anthropic 配置生成 Anthropic 格式响应；
如果规则没有 anthropic 配置，则将 OpenAI 格式的 response 自动转换。
"""

import json
import uuid
from pathlib import Path
from typing import Optional
from fastapi import APIRouter, Request
from fastapi.responses import StreamingResponse, JSONResponse
from ..models import (
    AnthropicMessagesRequest,
    ChatMessage,
    ResponseRule,
    AnthropicStreamConfig,
)
from ..store.yaml_store import YamlResponseStore
from ..strategies.keyword import KeywordMatchStrategy
from ..stream.anthropic_sse import (
    generate_anthropic_sse_dynamic,
    generate_anthropic_sse_from_fixture,
)

router = APIRouter()

# 全局存储和策略（由 main.py 注入）
response_store: YamlResponseStore = None
match_strategy: KeywordMatchStrategy = None
fixtures_dir: Optional[str] = None  # fixture 文件根目录


def init_router(
    store: YamlResponseStore,
    strategy: KeywordMatchStrategy,
    fixture_base_dir: Optional[str] = None,
):
    global response_store, match_strategy, fixtures_dir
    response_store = store
    match_strategy = strategy
    fixtures_dir = fixture_base_dir


@router.post("/v1/messages")
async def messages(request: Request):
    """
    Anthropic Messages API 兼容端点

    支持：
    - 同步响应（stream=false）
    - 流式响应（stream=true）— SSE 格式
    - 思维链（thinking）
    - 工具调用（tool_use）
    - JSONL fixture 文件回放
    """
    body = await request.json()
    req = AnthropicMessagesRequest(**body)

    # 将 Anthropic 消息转为 ChatMessage 格式以复用匹配逻辑
    chat_messages = _convert_to_chat_messages(req)
    rule = match_strategy.match(chat_messages, response_store.get_rules())

    # 提取 Anthropic 配置（优先）或回退到 OpenAI 配置
    thinking, content, tool_uses = _extract_response(rule)
    stream_config = _get_stream_config(rule)

    # 流式响应
    if req.stream:
        # 模式 1：JSONL fixture 回放
        if stream_config and stream_config.fixture_file:
            fixture_path = _resolve_fixture_path(stream_config.fixture_file)
            return StreamingResponse(
                generate_anthropic_sse_from_fixture(
                    fixture_path=str(fixture_path),
                    delay_ms=stream_config.delay_ms,
                ),
                media_type="text/event-stream",
                headers=_sse_headers(),
            )

        # 模式 2：动态生成
        return StreamingResponse(
            generate_anthropic_sse_dynamic(
                content=content,
                model=req.model,
                thinking=thinking,
                tool_uses=tool_uses,
                chunk_size=stream_config.chunk_size if stream_config else 20,
                delay_ms=stream_config.delay_ms if stream_config else 30,
            ),
            media_type="text/event-stream",
            headers=_sse_headers(),
        )

    # 同步响应
    return _build_sync_response(req.model, thinking, content, tool_uses)


@router.post("/v1/messages/error/{status_code}")
async def messages_error(status_code: int):
    """
    模拟 Anthropic API 错误响应

    使用方式：POST /v1/messages/error/429
    """
    error_map = {
        400: ("invalid_request_error", "messages: Required parameter is missing"),
        401: ("authentication_error", "invalid x-api-key"),
        403: ("permission_error", "Your API key does not have permission"),
        404: ("not_found_error", "The requested resource could not be found"),
        429: ("rate_limit_error", "Rate limit exceeded"),
        500: ("api_error", "An unexpected error has occurred"),
        529: ("overloaded_error", "Overloaded"),
    }

    error_type, message = error_map.get(
        status_code, ("api_error", f"Mock error: {status_code}")
    )

    return JSONResponse(
        status_code=status_code,
        content={
            "type": "error",
            "error": {"type": error_type, "message": message},
            "request_id": f"req_mock-error-{status_code}-{uuid.uuid4().hex[:6]}",
        },
    )


def _convert_to_chat_messages(req: AnthropicMessagesRequest) -> list:
    """
    将 Anthropic 消息格式转为 ChatMessage 格式，以复用 KeywordMatchStrategy。

    Anthropic 的 content 可能是 str 或 List[ContentBlock]，需要统一提取文本。
    """
    chat_messages = []
    for msg in req.messages:
        text = ""
        if isinstance(msg.content, str):
            text = msg.content
        elif isinstance(msg.content, list):
            # 提取所有文本内容（包括 text 和 tool_result）
            texts = []
            for block in msg.content:
                if isinstance(block, dict):
                    if block.get("type") == "text":
                        texts.append(block.get("text", ""))
                    elif block.get("type") == "tool_result":
                        result_content = block.get("content", "")
                        if isinstance(result_content, str):
                            texts.append(result_content)
                elif hasattr(block, "text") and block.text:
                    texts.append(block.text)
            text = " ".join(texts)

        chat_messages.append(ChatMessage(role=msg.role, content=text))
    return chat_messages


def _extract_response(rule: Optional[ResponseRule]) -> tuple:
    """
    从规则提取响应内容。

    Returns:
        (thinking, content, tool_uses) 三元组
    """
    if rule is None:
        return None, "Mock LLM: No matching response rule found.", None

    # 优先使用 anthropic 配置
    if rule.anthropic:
        return (
            rule.anthropic.thinking,
            rule.anthropic.content,
            rule.anthropic.tool_uses,
        )

    # 回退到 OpenAI 配置：自动转换格式
    tool_uses = None
    if rule.response.tool_calls:
        tool_uses = []
        for tc in rule.response.tool_calls:
            tool_uses.append({
                "id": tc.get("id", f"toolu_mock-{uuid.uuid4().hex[:8]}"),
                "name": tc["function"]["name"],
                "input": tc["function"]["arguments"]
                if isinstance(tc["function"]["arguments"], dict)
                else json.loads(tc["function"]["arguments"]),
            })

    return None, rule.response.content, tool_uses


def _get_stream_config(rule: Optional[ResponseRule]) -> Optional[AnthropicStreamConfig]:
    """获取 Anthropic 流式配置"""
    if rule and rule.anthropic_stream:
        return rule.anthropic_stream
    # 如果没有专属配置，从 OpenAI stream config 转换
    if rule and rule.stream:
        return AnthropicStreamConfig(
            chunk_size=rule.stream.chunk_size,
            delay_ms=rule.stream.delay_ms,
        )
    return AnthropicStreamConfig()  # 使用默认值


def _resolve_fixture_path(fixture_file: str) -> Path:
    """解析 fixture 文件路径"""
    path = Path(fixture_file)
    if path.is_absolute():
        return path
    # 相对路径：先尝试 fixtures_dir，再尝试当前目录
    if fixtures_dir:
        resolved = Path(fixtures_dir) / fixture_file
        if resolved.exists():
            return resolved
    return path


def _build_sync_response(
    model: str,
    thinking: Optional[str],
    content: str,
    tool_uses: Optional[list],
) -> JSONResponse:
    """构建 Anthropic 非流式同步响应"""
    response_content = []

    # thinking 块
    if thinking:
        response_content.append({"type": "thinking", "thinking": thinking})

    # text 块
    response_content.append({"type": "text", "text": content})

    # tool_use 块
    if tool_uses:
        for tool in tool_uses:
            response_content.append({
                "type": "tool_use",
                "id": tool.get("id", f"toolu_mock-{uuid.uuid4().hex[:8]}"),
                "name": tool.get("name", "unknown"),
                "input": tool.get("input", {}),
            })

    stop_reason = "tool_use" if tool_uses else "end_turn"
    input_tokens = len(content) // 4 + 10
    output_tokens = len(content) // 4 + (len(thinking) // 4 if thinking else 0) + 10

    return JSONResponse(content={
        "id": f"msg_mock-{uuid.uuid4().hex[:12]}",
        "type": "message",
        "role": "assistant",
        "model": model,
        "content": response_content,
        "stop_reason": stop_reason,
        "stop_sequence": None,
        "usage": {
            "input_tokens": input_tokens,
            "output_tokens": output_tokens,
            "cache_creation_input_tokens": 0,
            "cache_read_input_tokens": 0,
        },
    })


def _sse_headers() -> dict:
    """SSE 响应头"""
    return {
        "Cache-Control": "no-cache",
        "Connection": "keep-alive",
        "X-Accel-Buffering": "no",
    }
