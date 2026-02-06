import asyncio
import time
import uuid
from typing import AsyncGenerator
from ..models import ChatCompletionChunk, StreamChoice, DeltaContent, StreamConfig


async def generate_sse_stream(
    content: str, model: str, config: StreamConfig = None
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

    # 发送结束标记
    yield _format_chunk(
        ChatCompletionChunk(
            id=chunk_id,
            created=created,
            model=model,
            choices=[
                StreamChoice(index=0, delta=DeltaContent(), finish_reason="stop")
            ],
        )
    )

    # 发送 [DONE]
    yield "data: [DONE]\n\n"


def _format_chunk(chunk: ChatCompletionChunk) -> str:
    """格式化为 SSE 数据行"""
    return f"data: {chunk.model_dump_json()}\n\n"
