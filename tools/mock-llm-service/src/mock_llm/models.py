from pydantic import BaseModel
from typing import Optional, List, Dict, Any

# ========== OpenAI API 请求/响应模型 ==========


class FunctionCall(BaseModel):
    name: str
    arguments: str  # JSON string


class ToolCall(BaseModel):
    id: str
    type: str = "function"
    function: FunctionCall


class ChatMessage(BaseModel):
    role: str  # system / user / assistant / tool
    content: Optional[str] = None
    name: Optional[str] = None
    tool_call_id: Optional[str] = None
    tool_calls: Optional[List[ToolCall]] = None


class ChatCompletionRequest(BaseModel):
    model: str
    messages: List[ChatMessage]
    temperature: Optional[float] = 0.7
    max_tokens: Optional[int] = None
    stream: Optional[bool] = False
    tools: Optional[List[Dict[str, Any]]] = None


class ChatChoice(BaseModel):
    index: int
    message: ChatMessage
    finish_reason: str


class Usage(BaseModel):
    prompt_tokens: int
    completion_tokens: int
    total_tokens: int


class ChatCompletionResponse(BaseModel):
    id: str
    object: str = "chat.completion"
    created: int
    model: str
    choices: List[ChatChoice]
    usage: Usage


# ========== 流式响应模型 ==========


class DeltaToolCallFunction(BaseModel):
    name: Optional[str] = None
    arguments: Optional[str] = None


class DeltaToolCall(BaseModel):
    index: int
    id: Optional[str] = None
    type: Optional[str] = None
    function: Optional[DeltaToolCallFunction] = None


class DeltaContent(BaseModel):
    role: Optional[str] = None
    content: Optional[str] = None
    tool_calls: Optional[List[DeltaToolCall]] = None


class StreamChoice(BaseModel):
    index: int
    delta: DeltaContent
    finish_reason: Optional[str] = None


class ChatCompletionChunk(BaseModel):
    id: str
    object: str = "chat.completion.chunk"
    created: int
    model: str
    choices: List[StreamChoice]


# ========== 响应配置模型 ==========


class MatchRule(BaseModel):
    keywords: Optional[List[str]] = None  # 关键词列表（OR 匹配）
    pattern: Optional[str] = None  # 正则表达式
    default: Optional[bool] = False  # 是否为默认响应
    message_role: Optional[str] = None  # 指定从哪个角色的最后一条消息匹配，默认 user


class StreamConfig(BaseModel):
    chunk_size: int = 10  # 每块字符数
    delay_ms: int = 50  # 块间延迟（毫秒）


class MockResponseConfig(BaseModel):
    content: str  # 响应内容
    tool_calls: Optional[List[Dict]] = None


class AnthropicResponseConfig(BaseModel):
    """Anthropic Messages API 响应配置"""

    thinking: Optional[str] = None  # 思维链内容（可选）
    content: str  # 文本响应
    tool_uses: Optional[List[Dict[str, Any]]] = None  # tool_use 块列表


class AnthropicStreamConfig(BaseModel):
    """Anthropic 流式配置"""

    fixture_file: Optional[str] = None  # JSONL fixture 文件路径（优先使用）
    chunk_size: int = 20  # 每块字符数（无 fixture 时使用）
    delay_ms: int = 30  # 块间延迟


class ResponseRule(BaseModel):
    name: str  # 规则名称（唯一标识）
    match: MatchRule  # 匹配规则
    response: MockResponseConfig  # OpenAI 格式响应配置
    stream: Optional[StreamConfig] = None  # OpenAI 流式配置
    anthropic: Optional[AnthropicResponseConfig] = None  # Anthropic 格式响应
    anthropic_stream: Optional[AnthropicStreamConfig] = None  # Anthropic 流式配置


# ========== Anthropic Messages API 请求/响应模型 ==========


class AnthropicContentBlock(BaseModel):
    """Anthropic 消息中的内容块"""

    type: str  # "text" | "image" | "tool_use" | "tool_result"
    text: Optional[str] = None
    # tool_result 字段
    tool_use_id: Optional[str] = None
    content: Optional[Any] = None  # tool_result 的内容
    # tool_use 字段
    id: Optional[str] = None
    name: Optional[str] = None
    input: Optional[Dict[str, Any]] = None


class AnthropicMessage(BaseModel):
    """Anthropic 消息格式"""

    role: str  # "user" | "assistant"
    content: Any  # str 或 List[AnthropicContentBlock]


class AnthropicMessagesRequest(BaseModel):
    """Anthropic POST /v1/messages 请求"""

    model: str
    max_tokens: int = 4096
    messages: List[AnthropicMessage]
    # Claude Code CLI may send either a plain system string or a list of
    # content blocks with cache-control metadata.
    system: Optional[str | List[Dict[str, Any]]] = None
    stream: Optional[bool] = False
    tools: Optional[List[Dict[str, Any]]] = None
    temperature: Optional[float] = None
    top_p: Optional[float] = None
    metadata: Optional[Dict[str, Any]] = None
