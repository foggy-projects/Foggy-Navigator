import json
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
from ..strategies.keyword import KeywordMatchStrategy
from ..stream.sse import generate_sse_stream

router = APIRouter()

# 全局存储和策略（由 main.py 注入）
response_store: YamlResponseStore = None
match_strategy: KeywordMatchStrategy = None


def init_router(store: YamlResponseStore, strategy: KeywordMatchStrategy):
    global response_store, match_strategy
    response_store = store
    match_strategy = strategy


@router.post("/v1/chat/completions")
async def chat_completions(request: ChatCompletionRequest):
    """
    OpenAI Chat Completions API 兼容端点

    支持：
    - 同步响应
    - 流式响应（stream=true）
    - 关键词匹配
    """
    # 匹配响应规则
    rule = match_strategy.match(request.messages, response_store.get_rules())

    if rule is None:
        content = "Mock LLM: No matching response rule found."
    else:
        content = rule.response.content

    # 流式响应
    if request.stream:
        stream_config = rule.stream if rule and rule.stream else StreamConfig()
        tool_calls = rule.response.tool_calls if rule and rule.response.tool_calls else None
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

    # 如果规则配置了 tool_calls，添加到响应中
    if rule and rule.response.tool_calls:
        tool_calls = []
        for i, tc in enumerate(rule.response.tool_calls):
            tool_calls.append(
                ToolCall(
                    id=tc.get("id", f"call_{uuid.uuid4().hex[:8]}"),
                    type=tc.get("type", "function"),
                    function=FunctionCall(
                        name=tc["function"]["name"],
                        arguments=json.dumps(tc["function"]["arguments"])
                        if isinstance(tc["function"]["arguments"], dict)
                        else tc["function"]["arguments"],
                    ),
                )
            )
        response_message = ChatMessage(
            role="assistant", content=content, tool_calls=tool_calls
        )
        finish_reason = "tool_calls"

    return ChatCompletionResponse(
        id=f"chatcmpl-{uuid.uuid4().hex[:8]}",
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


def _estimate_tokens(messages: list) -> int:
    """估算 token 数量"""
    return sum(len(m.content) // 4 for m in messages)
