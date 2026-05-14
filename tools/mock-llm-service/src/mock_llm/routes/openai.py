import time
import uuid
from fastapi import APIRouter
from fastapi.responses import StreamingResponse
from ..models import (
    ChatCompletionRequest,
    ChatCompletionResponse,
    ChatChoice,
    ChatMessage,
    ToolCall,
    FunctionCall,
    Usage,
    StreamConfig,
)
from ..store.yaml_store import YamlResponseStore
from ..store.script_store import ScriptMatch, ScriptStore
from ..strategies.keyword import KeywordMatchStrategy
from ..stream.sse import generate_sse_stream
from ..tool_calls import normalize_tool_call

router = APIRouter()

# 全局存储和策略（由 main.py 注入）
response_store: YamlResponseStore = None
match_strategy: KeywordMatchStrategy = None
script_store: ScriptStore = None


def init_router(store: YamlResponseStore, strategy: KeywordMatchStrategy, scripts: ScriptStore = None):
    global response_store, match_strategy, script_store
    response_store = store
    match_strategy = strategy
    script_store = scripts


@router.post("/v1/chat/completions")
async def chat_completions(request: ChatCompletionRequest):
    """
    OpenAI Chat Completions API 兼容端点

    支持：
    - 同步响应
    - 流式响应（stream=true）
    - 关键词匹配
    """
    script_match = script_store.match(request.model, request.messages) if script_store else None

    # 匹配响应规则
    rule = None if script_match else match_strategy.match(request.messages, response_store.get_rules())

    if script_match:
        content = script_match.response.content
    elif rule is None:
        content = "Mock LLM: No matching response rule found."
    else:
        content = rule.response.content

    # 流式响应
    if request.stream:
        stream_config = rule.stream if rule and rule.stream else StreamConfig()
        tool_calls = _normalized_tool_calls(script_match, rule)
        _record_debug_request(
            script_match,
            request,
            {
                "stream": True,
                "toolCalls": [tc["function"]["name"] for tc in tool_calls],
            },
        )
        return StreamingResponse(
            generate_sse_stream(content, request.model, stream_config, tool_calls),
            media_type="text/event-stream",
            headers={
                "Cache-Control": "no-cache",
                "Connection": "keep-alive",
                "X-Accel-Buffering": "no",
            },
        )

    # 同步响应
    # 构建响应消息
    response_message = ChatMessage(role="assistant", content=content)
    finish_reason = "stop"

    configured_tool_calls = _normalized_tool_calls(script_match, rule)
    # 如果规则配置了 tool_calls，添加到响应中
    if configured_tool_calls:
        tool_calls = []
        for i, tc in enumerate(configured_tool_calls):
            tool_calls.append(
                ToolCall(
                    id=tc.get("id") or _deterministic_tool_call_id(script_match, i, tc),
                    type=tc.get("type", "function"),
                    function=FunctionCall(
                        name=tc["function"]["name"],
                        arguments=tc["function"]["arguments"],
                    ),
                )
            )
        response_message = ChatMessage(
            role="assistant", content=content, tool_calls=tool_calls
        )
        finish_reason = "tool_calls"

    response = ChatCompletionResponse(
        id=_completion_id(script_match),
        created=int(time.time()),
        model=request.model,
        choices=[
            ChatChoice(
                index=0,
                message=response_message,
                finish_reason=finish_reason,
            )
        ],
        usage=Usage(
            prompt_tokens=_estimate_tokens(request.messages),
            completion_tokens=len(content) // 4,
            total_tokens=_estimate_tokens(request.messages) + len(content) // 4,
        ),
    )
    _record_debug_request(
        script_match,
        request,
        {
            "finishReason": finish_reason,
            "contentLength": len(content or ""),
            "toolCalls": [
                tc.function.name for tc in (response_message.tool_calls or [])
            ],
        },
    )
    return response


def _estimate_tokens(messages: list) -> int:
    """估算 token 数量"""
    return sum(len(m.content or "") // 4 for m in messages)


def _response_tool_calls(script_match: ScriptMatch, rule):
    if script_match:
        return script_match.response.tool_calls
    return rule.response.tool_calls if rule and rule.response.tool_calls else None


def _normalized_tool_calls(script_match: ScriptMatch, rule):
    tool_calls = _response_tool_calls(script_match, rule) or []
    seed = script_match.cursor if script_match else None
    return [normalize_tool_call(tc, i, seed) for i, tc in enumerate(tool_calls)]


def _completion_id(script_match: ScriptMatch) -> str:
    if script_match:
        return f"chatcmpl-{script_match.request_hash[:12]}"
    return f"chatcmpl-{uuid.uuid4().hex[:8]}"


def _deterministic_tool_call_id(script_match: ScriptMatch, index: int, tool_call: dict) -> str:
    if not script_match:
        return f"call_{uuid.uuid4().hex[:8]}"
    name = tool_call.get("function", {}).get("name", "tool")
    seed = f"{script_match.cursor}|{index}|{name}"
    import hashlib

    return f"call_{hashlib.sha256(seed.encode('utf-8')).hexdigest()[:12]}"


def _record_debug_request(script_match: ScriptMatch, request: ChatCompletionRequest, response_summary: dict) -> None:
    if not script_match or not script_store:
        return
    script_store.record_request(
        script_match,
        request.model,
        request.model_dump(mode="json", exclude_none=True),
        response_summary,
    )
