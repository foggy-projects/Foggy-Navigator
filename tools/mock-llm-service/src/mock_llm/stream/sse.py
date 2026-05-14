import asyncio
import time
import uuid
from typing import AsyncGenerator, List, Optional
from ..models import (
    ChatCompletionChunk,
    StreamChoice,
    DeltaContent,
    DeltaToolCall,
    DeltaToolCallFunction,
    StreamConfig,
)
from ..tool_calls import normalize_tool_call


async def generate_sse_stream(
    content: str,
    model: str,
    config: StreamConfig = None,
    tool_calls: Optional[List[dict]] = None,
) -> AsyncGenerator[str, None]:
    """
    生成 SSE 流式响应

    格式与 OpenAI API 完全兼容：
    data: {"id":"...","object":"chat.completion.chunk","choices":[{"delta":{"content":"..."}}]}
    """
    if config is None:
        config = StreamConfig()

    chunk_id = f"chatcmpl-{uuid.uuid4().hex[:8]}"
    created = int(time.time())

    # 发送 role
    yield _format_chunk(
        ChatCompletionChunk(
            id=chunk_id,
            created=created,
            model=model,
            choices=[
                StreamChoice(
                    index=0, delta=DeltaContent(role="assistant"), finish_reason=None
                )
            ],
        )
    )

    # 分块发送内容
    for i in range(0, len(content), config.chunk_size):
        chunk_content = content[i : i + config.chunk_size]
        yield _format_chunk(
            ChatCompletionChunk(
                id=chunk_id,
                created=created,
                model=model,
                choices=[
                    StreamChoice(
                        index=0,
                        delta=DeltaContent(content=chunk_content),
                        finish_reason=None,
                    )
                ],
            )
        )
        await asyncio.sleep(config.delay_ms / 1000)

    # 如果有 tool_calls，流式发送
    if tool_calls:
        for i, tc in enumerate(tool_calls):
            normalized = normalize_tool_call(tc, i)
            # 发送 tool_call 开始（包含 id、type、name）
            tc_id = normalized["id"]
            func_name = normalized["function"]["name"]
            func_args = normalized["function"]["arguments"]

            yield _format_chunk(
                ChatCompletionChunk(
                    id=chunk_id,
                    created=created,
                    model=model,
                    choices=[
                        StreamChoice(
                            index=0,
                            delta=DeltaContent(
                                tool_calls=[
                                    DeltaToolCall(
                                        index=i,
                                        id=tc_id,
                                        type="function",
                                        function=DeltaToolCallFunction(
                                            name=func_name, arguments=""
                                        ),
                                    )
                                ]
                            ),
                            finish_reason=None,
                        )
                    ],
                )
            )
            await asyncio.sleep(config.delay_ms / 1000)

            # 分块发送 arguments
            for j in range(0, len(func_args), config.chunk_size):
                arg_chunk = func_args[j : j + config.chunk_size]
                yield _format_chunk(
                    ChatCompletionChunk(
                        id=chunk_id,
                        created=created,
                        model=model,
                        choices=[
                            StreamChoice(
                                index=0,
                                delta=DeltaContent(
                                    tool_calls=[
                                        DeltaToolCall(
                                            index=i,
                                            function=DeltaToolCallFunction(
                                                arguments=arg_chunk
                                            ),
                                        )
                                    ]
                                ),
                                finish_reason=None,
                            )
                        ],
                    )
                )
                await asyncio.sleep(config.delay_ms / 1000)

    # 发送结束标记
    finish_reason = "tool_calls" if tool_calls else "stop"
    yield _format_chunk(
        ChatCompletionChunk(
            id=chunk_id,
            created=created,
            model=model,
            choices=[
                StreamChoice(index=0, delta=DeltaContent(), finish_reason=finish_reason)
            ],
        )
    )

    # 发送 [DONE]
    yield "data: [DONE]\n\n"


def _format_chunk(chunk: ChatCompletionChunk) -> str:
    """格式化为 SSE 数据行"""
    return f"data: {chunk.model_dump_json()}\n\n"
