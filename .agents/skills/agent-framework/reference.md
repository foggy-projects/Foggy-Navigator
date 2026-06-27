# Agent Framework 接口参考

## 核心接口清单

### core 包

| 接口 | 说明 | 实现 |
|------|------|------|
| `AgentRegistry` | Agent注册与发现 | `InMemoryAgentRegistry` |
| `AgentConfigLoader` | 配置加载 | `DefaultAgentConfigLoader` |

**AgentRegistry 方法：**
```java
void register(AgentConfig config);
void unregister(String agentId);
AgentInfo findById(String agentId);
List<AgentInfo> findByCapability(String capability);
List<AgentInfo> findAll();
boolean exists(String agentId);
void updateStatus(String agentId, AgentStatus status);
```

### tool 包

| 接口 | 说明 | 实现 |
|------|------|------|
| `ToolRegistry` | 工具注册与执行 | `InMemoryToolRegistry` |
| `CredentialStore` | 用户凭证存储 | `InMemoryCredentialStore` |

**ToolRegistry 方法：**
```java
void registerHttpTool(String agentId, String name, String description, HttpToolConfig config);
void registerMcpTool(String agentId, McpToolConfig config);
void bindUserCredential(String userId, String toolName, UserToolCredential credential);
void unbindUserCredential(String userId, String toolName);
UserToolCredential getUserCredential(String userId, String toolName);
List<ToolDefinition> getToolsByAgent(String agentId);
List<ToolDefinition> getAvailableTools(String agentId, String userId);
ToolExecutionResult executeTool(ToolExecutionRequest request);
```

**CredentialStore 方法：**
```java
void save(UserToolCredential credential);
UserToolCredential find(String userId, String toolName);
List<UserToolCredential> findByUser(String userId);
void delete(String userId, String toolName);
boolean isValid(String userId, String toolName);
UserToolCredential refresh(String userId, String toolName);
```

### session 包

| 接口 | 说明 | 实现 |
|------|------|------|
| `SessionManager` | 会话生命周期管理 | `InMemorySessionManager` |

**SessionManager 方法：**
```java
String createSession(SessionCreateRequest request);
Session getSession(String sessionId);
void updateStatus(String sessionId, SessionStatus status);
String addMessage(String sessionId, Message message);
List<Message> getRecentMessages(String sessionId, int limit);
List<Message> getAllMessages(String sessionId);
void closeSession(String sessionId);
List<Session> findPendingByUser(String userId);
List<Session> findByUser(String userId);
```

### skill 包

| 接口 | 说明 | 实现 |
|------|------|------|
| `SkillManager` | 技能加载与管理 | `DefaultSkillManager` |
| `SkillParser` | Markdown解析 | `DefaultSkillParser` |
| `SkillMatcher` | 技能匹配 | `KeywordSkillMatcher` |

**SkillParser 方法：**
```java
Skill parse(String markdownContent);
Skill parseFile(String filePath);
```

**SkillMatcher 方法：**
```java
Skill match(String userMessage, List<Skill> availableSkills);
double calculateScore(String userMessage, Skill skill);
```

### router 包

| 接口 | 说明 | 实现 |
|------|------|------|
| `SessionRouter` | Agent间路由分派 | `DefaultSessionRouter` |

**SessionRouter 方法：**
```java
DelegationResult delegateToAgent(DelegationRequest request);
DelegationResult returnToParent(String currentSessionId);
PreconditionCheckResult checkPreconditions(Map<String, Object> preconditions);
```

### llm 包

| 接口 | 说明 | 实现 |
|------|------|------|
| `LlmAdapter` | LLM调用适配 | `LangChain4jAdapter` |

**LlmAdapter 方法：**
```java
LlmResponse chat(LlmRequest request);
void chatStream(LlmRequest request, LlmStreamHandler handler);
String getName();
boolean supportsModel(String model);
```

## 协议类型 (MessageType)

```java
// 文本流
TEXT_CHUNK, TEXT_COMPLETE

// 工具调用
TOOL_CALL_START, TOOL_CALL_RESULT, TOOL_CALL_ERROR

// UI渲染
SURFACE_UPDATE

// 路由跳转
ROUTE_REQUEST, ROUTE_CONFIRM

// 用户操作
USER_ACTION_REQUEST, USER_ACTION_RESPONSE

// 状态同步
STATE_SYNC

// 系统
ERROR, HEARTBEAT
```

## 枚举类型

**AgentStatus：** `REGISTERED`, `ACTIVE`, `PAUSED`, `STOPPED`, `ERROR`

**SessionStatus：** `ACTIVE`, `PAUSED`, `DELEGATED`, `COMPLETED`, `FAILED`

**MessageRole：** `SYSTEM`, `USER`, `ASSISTANT`, `TOOL`

**RouteAction：** `DELEGATE`, `RETURN`, `REDIRECT`

**RouteMode：** `REPLACE`, `PUSH`, `MODAL`

**ActionType：** `CONFIRM`, `FORM_SUBMIT`, `SINGLE_SELECT`, `MULTI_SELECT`, `FILE_UPLOAD`, `CUSTOM`

**ComponentType：** `TEXT`, `BUTTON`, `INPUT`, `SELECT`, `FORM`, `LIST`, `CARD`, `PROGRESS`, `CUSTOM`

## 配置模型

**AgentConfig 字段：**
- id, name, type, description
- capabilities: List<String>
- skills: SkillsConfig
- tools: List<ToolConfig>
- model: ModelConfig
- delegation: DelegationConfig
- sessionResume: SessionResumeConfig

**McpToolConfig 字段：**
- name, description
- mcpServerUrl, protocol
- capabilities: List<String>
- inputSchema, outputSchema: Map
- requiresAuth: boolean

**UserToolCredential 字段：**
- id, userId, tenantId, toolName
- accessToken, refreshToken
- customHeaders: Map
- expiresAt, createdAt, updatedAt
