"""
Anthropic Messages API SSE 流式响应生成器

支持两种模式：
1. Fixture 回放模式：直接读取 JSONL fixture 文件逐行发送
2. 动态生成模式：根据 YAML 配置动态构建 SSE 事件序列
"""

import asyncio
import json
import uuid
from pathlib import Path
from typing import AsyncGenerator, List, Optional, Dict, Any


async def generate_anthropic_sse_from_fixture(
    fixture_path: str,
    delay_ms: int = 30,
) -> AsyncGenerator[str, None]:
    """
    从 JSONL fixture 文件回放 SSE 事件流

    JSONL 格式：每行一个 JSON 对象，含 event 和 data 字段
    {"event":"message_start","data":{...}}

    Args:
        fixture_path: JSONL fixture 文件的绝对或相对路径
        delay_ms: 事件间延迟（毫秒）
    """
    path = Path(fixture_path)
    if not path.exists():
        # 文件不存在时返回错误事件
        yield _format_error_event("not_found_error", f"Fixture file not found: {fixture_path}")
        return

    with open(path, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue

            try:
                record = json.loads(line)
                event_type = record.get("event", "message")
                data = record.get("data", {})
                yield f"event: {event_type}\ndata: {json.dumps(data, ensure_ascii=False)}\n\n"
                await asyncio.sleep(delay_ms / 1000)
            except json.JSONDecodeError:
                continue


async def generate_anthropic_sse_dynamic(
    content: str,
    model: str,
    thinking: Optional[str] = None,
    tool_uses: Optional[List[Dict[str, Any]]] = None,
    chunk_size: int = 20,
    delay_ms: int = 30,
) -> AsyncGenerator[str, None]:
    """
    动态生成 Anthropic Messages API SSE 事件流

    生成完整的事件序列：
    message_start → ping →
      [thinking 块] →
      text 块 →
      [tool_use 块...] →
    message_delta → message_stop

    Args:
        content: 文本响应内容
        model: 模型名称
        thinking: 思维链内容（可选）
        tool_uses: 工具调用列表（可选）
        chunk_size: 每块字符数
        delay_ms: 事件间延迟
    """
    msg_id = f"msg_mock-{uuid.uuid4().hex[:12]}"
    input_tokens = len(content) // 4 + 10
    block_index = 0

    # 1. message_start
    yield _format_event("message_start", {
        "message": {
            "model": model,
            "id": msg_id,
            "role": "assistant",
            "type": "message",
            "content": [],
            "usage": {"input_tokens": input_tokens, "output_tokens": 0},
        },
        "type": "message_start",
    })
    await asyncio.sleep(delay_ms / 1000)

    # 2. ping
    yield _format_event("ping", {"type": "ping"})
    await asyncio.sleep(delay_ms / 1000)

    # 3. thinking 块（如果有）
    if thinking:
        yield _format_event("content_block_start", {
            "type": "content_block_start",
            "content_block": {"type": "thinking", "thinking": ""},
            "index": block_index,
        })

        # 分块发送 thinking
        for i in range(0, len(thinking), chunk_size * 2):
            chunk = thinking[i: i + chunk_size * 2]
            yield _format_event("content_block_delta", {
                "delta": {"type": "thinking_delta", "thinking": chunk},
                "type": "content_block_delta",
                "index": block_index,
            })
            await asyncio.sleep(delay_ms / 1000)

        # signature_delta
        yield _format_event("content_block_delta", {
            "delta": {"type": "signature_delta", "signature": ""},
            "type": "content_block_delta",
            "index": block_index,
        })

        yield _format_event("content_block_stop", {
            "type": "content_block_stop",
            "index": block_index,
        })
        block_index += 1

    # 4. text 块
    yield _format_event("content_block_start", {
        "type": "content_block_start",
        "content_block": {"type": "text", "text": ""},
        "index": block_index,
    })

    # 分块发送 text
    for i in range(0, len(content), chunk_size):
        chunk = content[i: i + chunk_size]
        yield _format_event("content_block_delta", {
            "delta": {"type": "text_delta", "text": chunk},
            "type": "content_block_delta",
            "index": block_index,
        })
        await asyncio.sleep(delay_ms / 1000)

    yield _format_event("content_block_stop", {
        "type": "content_block_stop",
        "index": block_index,
    })
    block_index += 1

    # 5. tool_use 块（如果有）
    if tool_uses:
        for tool in tool_uses:
            tool_id = tool.get("id", f"toolu_mock-{uuid.uuid4().hex[:8]}")
            tool_name = tool.get("name", "unknown_tool")
            tool_input = tool.get("input", {})
            input_json = json.dumps(tool_input, ensure_ascii=False)

            yield _format_event("content_block_start", {
                "type": "content_block_start",
                "content_block": {
                    "name": tool_name,
                    "input": {},
                    "id": tool_id,
                    "type": "tool_use",
                },
                "index": block_index,
            })
            await asyncio.sleep(delay_ms / 1000)

            # 发送空 partial_json 初始化
            yield _format_event("content_block_delta", {
                "delta": {"partial_json": "", "type": "input_json_delta"},
                "type": "content_block_delta",
                "index": block_index,
            })

            # 分块发送 JSON 参数
            for i in range(0, len(input_json), chunk_size):
                chunk = input_json[i: i + chunk_size]
                yield _format_event("content_block_delta", {
                    "delta": {"partial_json": chunk, "type": "input_json_delta"},
                    "type": "content_block_delta",
                    "index": block_index,
                })
                await asyncio.sleep(delay_ms / 1000)

            yield _format_event("content_block_stop", {
                "type": "content_block_stop",
                "index": block_index,
            })
            block_index += 1

    # 6. message_delta — 结束信号
    stop_reason = "tool_use" if tool_uses else "end_turn"
    output_tokens = len(content) // 4 + (len(thinking) // 4 if thinking else 0) + 10
    yield _format_event("message_delta", {
        "delta": {"stop_reason": stop_reason},
        "type": "message_delta",
        "usage": {
            "output_tokens": output_tokens,
            "cache_creation_input_tokens": 0,
            "input_tokens": input_tokens,
            "cache_read_input_tokens": 0,
        },
    })

    # 7. message_stop
    yield _format_event("message_stop", {"type": "message_stop"})


def _format_event(event_type: str, data: dict) -> str:
    """格式化为 SSE 事件"""
    return f"event: {event_type}\ndata: {json.dumps(data, ensure_ascii=False)}\n\n"


def _format_error_event(error_type: str, message: str) -> str:
    """格式化错误事件"""
    return _format_event("error", {
        "type": "error",
        "error": {"type": error_type, "message": message},
    })
