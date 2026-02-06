from pydantic import BaseModel
from typing import Optional, List, Dict, Any

# ========== OpenAI API 请求/响应模型 ==========


class ChatMessage(BaseModel):
    role: str  # system / user / assistant / tool
    content: str
    name: Optional[str] = None
    tool_call_id: Optional[str] = None


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


class DeltaContent(BaseModel):
    role: Optional[str] = None
    content: Optional[str] = None


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
