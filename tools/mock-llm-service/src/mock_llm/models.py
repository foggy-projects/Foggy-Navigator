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


class StreamConfig(BaseModel):
    chunk_size: int = 10  # 每块字符数
    delay_ms: int = 50  # 块间延迟（毫秒）


class MockResponseConfig(BaseModel):
    content: str  # 响应内容
    tool_calls: Optional[List[Dict]] = None


class ResponseRule(BaseModel):
    name: str  # 规则名称（唯一标识）
    match: MatchRule  # 匹配规则
    response: MockResponseConfig  # 响应配置
    stream: Optional[StreamConfig] = None  # 流式配置
