# 工具模块设计文档

## 1. 概述

### 1.1 设计目标
- **统一接口**: 提供统一的工具调用接口，隐藏底层实现差异
- **工具隔离**: 每个用户或 session 拥有独立的工具实例和状态
- **连接管理**: 高效管理 MCP 服务器的连接池，支持有状态和长连接
- **动态扩展**: 支持动态注册和发现内置工具和 MCP 工具
- **权限控制**: 基于用户和 session 的细粒度权限控制
- **状态管理**: 管理有状态工具的状态，支持状态持久化和恢复
- **人工确认**: 支持 HITL（Human-in-the-Loop）机制，确保关键操作的安全性和可控性

### 1.2 核心原则
- **接口隔离**: 工具接口与实现分离
- **依赖倒置**: 高层模块不依赖低层模块，都依赖抽象
- **单一职责**: 每个组件只负责一个功能
- **开闭原则**: 对扩展开放，对修改关闭

### 1.3 架构层次

```
┌─────────────────────────────────────────────────────────┐
│                    应用层                              │
│              (LangChain4j + 业务逻辑)                   │
└─────────────────────────────────────────────────────────┘
                          ↓
┌─────────────────────────────────────────────────────────┐
│                  工具服务层                              │
│              (Tool Service Layer)                       │
│                                                         │
│  ┌──────────────────────────────────────────────┐      │
│  │      ToolService (统一接口)                   │      │
│  └──────────────────────────────────────────────┘      │
│                          ↓                              │
│  ┌──────────────────────────────────────────────┐      │
│  │   ToolRegistry (工具注册表)                 │      │
│  │   ToolExecutor (工具执行器)                 │      │
│  │   ToolPermissionManager (权限管理器)         │      │
│  │   HITLManager (人工确认管理器)               │      │
│  └──────────────────────────────────────────────┘      │
└─────────────────────────────────────────────────────────┘
                          ↓
┌─────────────────────────────────────────────────────────┐
│                  工具适配层                              │
│              (Tool Adapter Layer)                       │
│                                                         │
│  ┌──────────────┬──────────────┬──────────────┐        │
│  │  内置工具适配器 │  MCP 工具适配器 │  自定义适配器  │        │
│  └──────────────┴──────────────┴──────────────┘        │
└─────────────────────────────────────────────────────────┘
                          ↓
┌─────────────────────────────────────────────────────────┐
│                  连接管理层                              │
│              (Connection Management Layer)              │
│                                                         │
│  ┌──────────────────────────────────────────────┐      │
│  │   MCPConnectionPool (MCP 连接池)            │      │
│  │   ConnectionStateManager (状态管理器)        │      │
│  └──────────────────────────────────────────────┘      │
└─────────────────────────────────────────────────────────┘
                          ↓
┌─────────────────────────────────────────────────────────┐
│                  工具实现层                              │
│              (Tool Implementation Layer)                 │
│                                                         │
│  ┌──────────────┬──────────────┬──────────────┐        │
│  │  内置工具      │  MCP 工具      │  自定义工具    │        │
│  └──────────────┴──────────────┴──────────────┘        │
└─────────────────────────────────────────────────────────┘
```

## 2. 核心接口设计

### 2.1 工具服务接口（ToolService）

```java
public interface ToolService {
    
    /**
     * 注册工具
     */
    void registerTool(ToolDefinition tool);
    
    /**
     * 注销工具
     */
    void unregisterTool(String toolId);
    
    /**
     * 获取工具定义
     */
    ToolDefinition getTool(String toolId);
    
    /**
     * 获取所有工具
     */
    List<ToolDefinition> getAllTools();
    
    /**
     * 获取用户可用工具
     */
    List<ToolDefinition> getUserTools(String userId);
    
    /**
     * 获取 session 可用工具
     */
    List<ToolDefinition> getSessionTools(String sessionId);
    
    /**
     * 执行工具
     */
    ToolExecutionResult executeTool(ToolExecutionRequest request);
    
    /**
     * 批量执行工具
     */
    List<ToolExecutionResult> executeTools(List<ToolExecutionRequest> requests);
    
    /**
     * 验证工具调用
     */
    ToolValidationResult validateToolCall(ToolExecutionRequest request);
    
    /**
     * 获取工具状态
     */
    ToolState getToolState(String toolId, String scopeId);
    
    /**
     * 设置工具状态
     */
    void setToolState(String toolId, String scopeId, ToolState state);
    
    /**
     * 清除工具状态
     */
    void clearToolState(String toolId, String scopeId);
}
```

### 2.2 工具注册表接口（ToolRegistry）

```java
public interface ToolRegistry {
    
    /**
     * 注册工具
     */
    void register(ToolDefinition tool);
    
    /**
     * 注销工具
     */
    void unregister(String toolId);
    
    /**
     * 查找工具
     */
    ToolDefinition find(String toolId);
    
    /**
     * 查找所有工具
     */
    List<ToolDefinition> findAll();
    
    /**
     * 按类型查找工具
     */
    List<ToolDefinition> findByType(ToolType type);
    
    /**
     * 按分类查找工具
     */
    List<ToolDefinition> findByCategory(String category);
    
    /**
     * 检查工具是否存在
     */
    boolean exists(String toolId);
}
```

### 2.3 工具执行器接口（ToolExecutor）

```java
public interface ToolExecutor {
    
    /**
     * 执行工具
     */
    ToolExecutionResult execute(ToolExecutionRequest request);
    
    /**
     * 批量执行工具
     */
    List<ToolExecutionResult> executeBatch(List<ToolExecutionRequest> requests);
    
    /**
     * 异步执行工具
     */
    CompletableFuture<ToolExecutionResult> executeAsync(ToolExecutionRequest request);
    
    /**
     * 流式执行工具
     */
    Flux<String> executeStream(ToolExecutionRequest request);
    
    /**
     * 取消工具执行
     */
    void cancel(String executionId);
}
```

### 2.4 工具适配器接口（ToolAdapter）

```java
public interface ToolAdapter {
    
    /**
     * 获取工具类型
     */
    ToolType getType();
    
    /**
     * 执行工具
     */
    ToolExecutionResult execute(ToolExecutionRequest request);
    
    /**
     * 验证工具调用
     */
    ToolValidationResult validate(ToolExecutionRequest request);
    
    /**
     * 获取工具状态
     */
    ToolState getState(String scopeId);
    
    /**
     * 设置工具状态
     */
    void setState(String scopeId, ToolState state);
    
    /**
     * 初始化工具
     */
    void initialize(ToolDefinition tool);
    
    /**
     * 销毁工具
     */
    void destroy(String toolId);
    
    /**
     * 健康检查
     */
    boolean healthCheck();
}
```

### 2.5 MCP 连接池接口（MCPConnectionPool）

```java
public interface MCPConnectionPool {
    
    /**
     * 获取连接
     */
    MCPConnection getConnection(String serverId, String scopeId);
    
    /**
     * 释放连接
     */
    void releaseConnection(MCPConnection connection);
    
    /**
     * 关闭所有连接
     */
    void closeAll();
    
    /**
     * 获取连接统计
     */
    MCPConnectionStats getStats();
    
    /**
     * 清理过期连接
     */
    void cleanupExpiredConnections();
}
```

### 2.6 HITL 管理器接口（HITLManager）

```java
public interface HITLManager {
    
    /**
     * 检查工具调用是否需要人工确认
     */
    boolean requiresApproval(ToolExecutionRequest request);
    
    /**
     * 创建人工确认请求
     */
    HITLRequest createApprovalRequest(ToolExecutionRequest request);
    
    /**
     * 获取待确认的请求
     */
    List<HITLRequest> getPendingRequests(String userId);
    
    /**
     * 批准请求
     */
    void approveRequest(String requestId, String userId, String comment);
    
    /**
     * 拒绝请求
     */
    void rejectRequest(String requestId, String userId, String comment);
    
    /**
     * 获取请求详情
     */
    HITLRequest getRequest(String requestId);
    
    /**
     * 获取请求历史
     */
    List<HITLRequest> getRequestHistory(String userId, String toolId);
    
    /**
     * 取消请求
     */
    void cancelRequest(String requestId);
    
    /**
     * 设置工具的 HITL 策略
     */
    void setToolHITLPolicy(String toolId, HITLPolicy policy);
    
    /**
     * 获取工具的 HITL 策略
     */
    HITLPolicy getToolHITLPolicy(String toolId);
    
    /**
     * 批量设置 HITL 策略
     */
    void setBatchHITLPolicies(Map<String, HITLPolicy> policies);
}
```

## 3. 数据模型设计

### 3.1 工具定义模型

#### ToolDefinition
```java
@Data
public class ToolDefinition {
    private String id;
    private String name;
    private String description;
    private ToolType type;
    private String category;
    private List<ToolParameter> parameters;
    private ToolResultType resultType;
    private ToolConfig config;
    private ToolPermission permission;
    private Map<String, Object> metadata;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
```

#### ToolParameter
```java
@Data
public class ToolParameter {
    private String name;
    private String type;
    private String description;
    private boolean required;
    private Object defaultValue;
    private List<String> enumValues;
    private Map<String, Object> constraints;
    private Map<String, Object> examples;
}
```

#### ToolConfig
```java
@Data
public class ToolConfig {
    private Integer timeout;
    private Integer maxRetries;
    private Boolean async;
    private Boolean stream;
    private String executionMode;
    private Map<String, Object> options;
}
```

#### ToolPermission
```java
@Data
public class ToolPermission {
    private Boolean enabled;
    private List<String> allowedUsers;
    private List<String> allowedRoles;
    private List<String> deniedUsers;
    private List<String> deniedRoles;
    private Integer rateLimit;
    private Map<String, Object> customRules;
}
```

#### HITLPolicy
```java
@Data
public class HITLPolicy {
    private Boolean enabled;
    private HITLTrigger trigger;
    private HITLAction action;
    private List<String> approvers;
    private Integer timeout;
    private String fallbackAction;
    private Map<String, Object> customRules;
}
```

### 3.2 工具执行模型

#### ToolExecutionRequest
```java
@Data
public class ToolExecutionRequest {
    private String executionId;
    private String toolId;
    private String userId;
    private String sessionId;
    private Map<String, Object> parameters;
    private String executionMode;
    private Map<String, Object> context;
    private Map<String, Object> metadata;
}
```

#### ToolExecutionResult
```java
@Data
public class ToolExecutionResult {
    private String executionId;
    private String toolId;
    private ExecutionStatus status;
    private Object result;
    private String error;
    private Long executionTime;
    private Integer tokenCount;
    private Map<String, Object> metadata;
    private LocalDateTime executedAt;
}
```

#### ToolValidationResult
```java
@Data
public class ToolValidationResult {
    private Boolean valid;
    private List<String> errors;
    private List<String> warnings;
    private Map<String, Object> suggestions;
}
```

### 3.3 工具状态模型

#### ToolState
```java
@Data
public class ToolState {
    private String toolId;
    private String scopeId;
    private Map<String, Object> data;
    private LocalDateTime createdAt;
    private LocalDateTime lastAccessedAt;
    private Long ttl;
}
```

### 3.4 HITL 模型

#### HITLRequest
```java
@Data
public class HITLRequest {
    private String requestId;
    private String toolId;
    private String toolName;
    private String userId;
    private String sessionId;
    private Map<String, Object> parameters;
    private String reason;
    private HITLRequestStatus status;
    private String requestedBy;
    private String approvedBy;
    private String rejectedBy;
    private String approvalComment;
    private String rejectionComment;
    private LocalDateTime createdAt;
    private LocalDateTime approvedAt;
    private LocalDateTime rejectedAt;
    private LocalDateTime expiresAt;
    private Map<String, Object> metadata;
}
```

#### HITLRequestStatus
```java
public enum HITLRequestStatus {
    PENDING,
    APPROVED,
    REJECTED,
    CANCELLED,
    EXPIRED
}
```

#### HITLTrigger
```java
public enum HITLTrigger {
    ALWAYS,
    NEVER,
    ON_RISKY_OPERATION,
    ON_DATA_MODIFICATION,
    ON_EXTERNAL_ACCESS,
    ON_HIGH_COST,
    CUSTOM
}
```

#### HITLAction
```java
public enum HITLAction {
    BLOCK_UNTIL_APPROVED,
    ALLOW_WITH_WARNING,
    LOG_ONLY,
    CUSTOM
}
```

### 3.5 MCP 连接模型

#### MCPConnection
```java
@Data
public class MCPConnection {
    private String connectionId;
    private String serverId;
    private String scopeId;
    private MCPClient client;
    private ConnectionState state;
    private LocalDateTime createdAt;
    private LocalDateTime lastUsedAt;
    private Long ttl;
}
```

#### MCPClient
```java
public interface MCPClient {
    
    /**
     * 列出可用工具
     */
    List<ToolDefinition> listTools();
    
    /**
     * 调用工具
     */
    MCPToolResult callTool(String toolName, Map<String, Object> arguments);
    
    /**
     * 获取资源
     */
    MCPResource getResource(String uri);
    
    /**
     * 列出资源
     */
    List<MCPResource> listResources();
    
    /**
     * 获取提示模板
     */
    MCPPrompt getPrompt(String name);
    
    /**
     * 列出提示模板
     */
    List<MCPPrompt> listPrompts();
    
    /**
     * 关闭连接
     */
    void close();
    
    /**
     * 检查连接状态
     */
    boolean isConnected();
}
```

#### MCPToolResult
```java
@Data
public class MCPToolResult {
    private String content;
    private String contentType;
    private Map<String, Object> metadata;
    private Boolean isError;
}
```

#### MCPResource
```java
@Data
public class MCPResource {
    private String uri;
    private String name;
    private String description;
    private String mimeType;
    private Map<String, Object> metadata;
}
```

#### MCPPrompt
```java
@Data
public class MCPPrompt {
    private String name;
    private String description;
    private List<MCPPromptArgument> arguments;
}
```

#### MCPPromptArgument
```java
@Data
public class MCPPromptArgument {
    private String name;
    private String description;
    private boolean required;
}
```

### 3.5 枚举类型

#### ToolType
```java
public enum ToolType {
    BUILTIN,
    MCP,
    CUSTOM
}
```

#### ExecutionStatus
```java
public enum ExecutionStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED,
    TIMEOUT
}
```

#### ConnectionState
```java
public enum ConnectionState {
    CONNECTING,
    CONNECTED,
    IDLE,
    DISCONNECTING,
    DISCONNECTED,
    ERROR
}
```

#### ToolResultType
```java
public enum ToolResultType {
    TEXT,
    JSON,
    BINARY,
    STREAM
}
```

## 4. 内置工具实现

### 4.1 内置工具适配器

```java
@Service
public class BuiltinToolAdapter implements ToolAdapter {
    
    private final Map<String, ToolExecutor> executors;
    
    public BuiltinToolAdapter(List<ToolExecutor> executorList) {
        this.executors = executorList.stream()
            .collect(Collectors.toMap(
                executor -> executor.getClass().getSimpleName(),
                Function.identity()
            ));
    }
    
    @Override
    public ToolType getType() {
        return ToolType.BUILTIN;
    }
    
    @Override
    public ToolExecutionResult execute(ToolExecutionRequest request) {
        ToolExecutor executor = executors.get(request.getToolId());
        if (executor == null) {
            throw new IllegalArgumentException("Tool not found: " + request.getToolId());
        }
        
        return executor.execute(request);
    }
    
    @Override
    public ToolValidationResult validate(ToolExecutionRequest request) {
        ToolExecutor executor = executors.get(request.getToolId());
        if (executor == null) {
            ToolValidationResult result = new ToolValidationResult();
            result.setValid(false);
            result.setErrors(List.of("Tool not found: " + request.getToolId()));
            return result;
        }
        
        return validateParameters(request);
    }
    
    @Override
    public ToolState getState(String scopeId) {
        return null;
    }
    
    @Override
    public void setState(String scopeId, ToolState state) {
    }
    
    @Override
    public void initialize(ToolDefinition tool) {
    }
    
    @Override
    public void destroy(String toolId) {
    }
    
    @Override
    public boolean healthCheck() {
        return true;
    }
    
    private ToolValidationResult validateParameters(ToolExecutionRequest request) {
        ToolDefinition tool = toolRegistry.find(request.getToolId());
        ToolValidationResult result = new ToolValidationResult();
        result.setValid(true);
        result.setErrors(new ArrayList<>());
        result.setWarnings(new ArrayList<>());
        
        for (ToolParameter param : tool.getParameters()) {
            if (param.isRequired() && !request.getParameters().containsKey(param.getName())) {
                result.setValid(false);
                result.getErrors().add("Missing required parameter: " + param.getName());
            }
        }
        
        return result;
    }
}
```

### 4.2 常见内置工具

#### 文件操作工具
```java
@Service
public class FileOperationTool implements ToolExecutor {
    
    @Override
    public ToolExecutionResult execute(ToolExecutionRequest request) {
        String operation = (String) request.getParameters().get("operation");
        String filePath = (String) request.getParameters().get("path");
        
        try {
            Object result;
            switch (operation) {
                case "read":
                    result = readFile(filePath);
                    break;
                case "write":
                    String content = (String) request.getParameters().get("content");
                    result = writeFile(filePath, content);
                    break;
                case "list":
                    result = listFiles(filePath);
                    break;
                case "delete":
                    result = deleteFile(filePath);
                    break;
                default:
                    throw new IllegalArgumentException("Unknown operation: " + operation);
            }
            
            ToolExecutionResult executionResult = new ToolExecutionResult();
            executionResult.setExecutionId(request.getExecutionId());
            executionResult.setToolId(request.getToolId());
            executionResult.setStatus(ExecutionStatus.COMPLETED);
            executionResult.setResult(result);
            executionResult.setExecutedAt(LocalDateTime.now());
            
            return executionResult;
        } catch (Exception e) {
            ToolExecutionResult executionResult = new ToolExecutionResult();
            executionResult.setExecutionId(request.getExecutionId());
            executionResult.setToolId(request.getToolId());
            executionResult.setStatus(ExecutionStatus.FAILED);
            executionResult.setError(e.getMessage());
            executionResult.setExecutedAt(LocalDateTime.now());
            
            return executionResult;
        }
    }
    
    private String readFile(String filePath) {
        return Files.readString(Paths.get(filePath));
    }
    
    private String writeFile(String filePath, String content) {
        Files.writeString(Paths.get(filePath), content);
        return "File written successfully";
    }
    
    private List<String> listFiles(String dirPath) {
        return Files.list(Paths.get(dirPath))
            .map(Path::toString)
            .collect(Collectors.toList());
    }
    
    private String deleteFile(String filePath) {
        Files.delete(Paths.get(filePath));
        return "File deleted successfully";
    }
}
```

#### HTTP 请求工具
```java
@Service
public class HttpRequestTool implements ToolExecutor {
    
    @Autowired
    private RestTemplate restTemplate;
    
    @Override
    public ToolExecutionResult execute(ToolExecutionRequest request) {
        String url = (String) request.getParameters().get("url");
        String method = (String) request.getParameters().getOrDefault("method", "GET");
        Map<String, String> headers = (Map<String, String>) request.getParameters().getOrDefault("headers", Map.of());
        Object body = request.getParameters().get("body");
        
        try {
            Object result;
            switch (method.toUpperCase()) {
                case "GET":
                    result = executeGet(url, headers);
                    break;
                case "POST":
                    result = executePost(url, headers, body);
                    break;
                case "PUT":
                    result = executePut(url, headers, body);
                    break;
                case "DELETE":
                    result = executeDelete(url, headers);
                    break;
                default:
                    throw new IllegalArgumentException("Unknown HTTP method: " + method);
            }
            
            ToolExecutionResult executionResult = new ToolExecutionResult();
            executionResult.setExecutionId(request.getExecutionId());
            executionResult.setToolId(request.getToolId());
            executionResult.setStatus(ExecutionStatus.COMPLETED);
            executionResult.setResult(result);
            executionResult.setExecutedAt(LocalDateTime.now());
            
            return executionResult;
        } catch (Exception e) {
            ToolExecutionResult executionResult = new ToolExecutionResult();
            executionResult.setExecutionId(request.getExecutionId());
            executionResult.setToolId(request.getToolId());
            executionResult.setStatus(ExecutionStatus.FAILED);
            executionResult.setError(e.getMessage());
            executionResult.setExecutedAt(LocalDateTime.now());
            
            return executionResult;
        }
    }
    
    private Object executeGet(String url, Map<String, String> headers) {
        HttpHeaders httpHeaders = new HttpHeaders();
        headers.forEach(httpHeaders::add);
        
        HttpEntity<?> entity = new HttpEntity<>(httpHeaders);
        ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
        
        return Map.of(
            "status", response.getStatusCodeValue(),
            "headers", response.getHeaders().toSingleValueMap(),
            "body", response.getBody()
        );
    }
    
    private Object executePost(String url, Map<String, String> headers, Object body) {
        HttpHeaders httpHeaders = new HttpHeaders();
        headers.forEach(httpHeaders::add);
        httpHeaders.setContentType(MediaType.APPLICATION_JSON);
        
        HttpEntity<?> entity = new HttpEntity<>(body, httpHeaders);
        ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);
        
        return Map.of(
            "status", response.getStatusCodeValue(),
            "headers", response.getHeaders().toSingleValueMap(),
            "body", response.getBody()
        );
    }
    
    private Object executePut(String url, Map<String, String> headers, Object body) {
        HttpHeaders httpHeaders = new HttpHeaders();
        headers.forEach(httpHeaders::add);
        httpHeaders.setContentType(MediaType.APPLICATION_JSON);
        
        HttpEntity<?> entity = new HttpEntity<>(body, httpHeaders);
        ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.PUT, entity, String.class);
        
        return Map.of(
            "status", response.getStatusCodeValue(),
            "headers", response.getHeaders().toSingleValueMap(),
            "body", response.getBody()
        );
    }
    
    private Object executeDelete(String url, Map<String, String> headers) {
        HttpHeaders httpHeaders = new HttpHeaders();
        headers.forEach(httpHeaders::add);
        
        HttpEntity<?> entity = new HttpEntity<>(httpHeaders);
        ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.DELETE, entity, String.class);
        
        return Map.of(
            "status", response.getStatusCodeValue(),
            "headers", response.getHeaders().toSingleValueMap(),
            "body", response.getBody()
        );
    }
}
```

#### 数据库查询工具
```java
@Service
public class DatabaseQueryTool implements ToolExecutor {
    
    @Autowired
    private JdbcTemplate jdbcTemplate;
    
    @Override
    public ToolExecutionResult execute(ToolExecutionRequest request) {
        String query = (String) request.getParameters().get("query");
        
        try {
            List<Map<String, Object>> result = jdbcTemplate.queryForList(query);
            
            ToolExecutionResult executionResult = new ToolExecutionResult();
            executionResult.setExecutionId(request.getExecutionId());
            executionResult.setToolId(request.getToolId());
            executionResult.setStatus(ExecutionStatus.COMPLETED);
            executionResult.setResult(result);
            executionResult.setExecutedAt(LocalDateTime.now());
            
            return executionResult;
        } catch (Exception e) {
            ToolExecutionResult executionResult = new ToolExecutionResult();
            executionResult.setExecutionId(request.getExecutionId());
            executionResult.setToolId(request.getToolId());
            executionResult.setStatus(ExecutionStatus.FAILED);
            executionResult.setError(e.getMessage());
            executionResult.setExecutedAt(LocalDateTime.now());
            
            return executionResult;
        }
    }
}
```

## 5. MCP 工具实现

### 5.1 MCP 工具适配器

```java
@Service
public class MCPToolAdapter implements ToolAdapter {
    
    @Autowired
    private MCPConnectionPool connectionPool;
    
    @Autowired
    private MCPClientFactory mcpClientFactory;
    
    @Override
    public ToolType getType() {
        return ToolType.MCP;
    }
    
    @Override
    public ToolExecutionResult execute(ToolExecutionRequest request) {
        String scopeId = getScopeId(request);
        MCPConnection connection = connectionPool.getConnection(request.getToolId(), scopeId);
        
        try {
            MCPClient client = connection.getClient();
            MCPToolResult mcpResult = client.callTool(
                request.getToolId(),
                request.getParameters()
            );
            
            ToolExecutionResult result = new ToolExecutionResult();
            result.setExecutionId(request.getExecutionId());
            result.setToolId(request.getToolId());
            result.setStatus(mcpResult.getIsError() ? ExecutionStatus.FAILED : ExecutionStatus.COMPLETED);
            result.setResult(mcpResult.getContent());
            result.setMetadata(mcpResult.getMetadata());
            result.setExecutedAt(LocalDateTime.now());
            
            return result;
        } catch (Exception e) {
            ToolExecutionResult result = new ToolExecutionResult();
            result.setExecutionId(request.getExecutionId());
            result.setToolId(request.getToolId());
            result.setStatus(ExecutionStatus.FAILED);
            result.setError(e.getMessage());
            result.setExecutedAt(LocalDateTime.now());
            
            return result;
        } finally {
            connectionPool.releaseConnection(connection);
        }
    }
    
    @Override
    public ToolValidationResult validate(ToolExecutionRequest request) {
        ToolValidationResult result = new ToolValidationResult();
        result.setValid(true);
        result.setErrors(new ArrayList<>());
        result.setWarnings(new ArrayList<>());
        
        try {
            String scopeId = getScopeId(request);
            MCPConnection connection = connectionPool.getConnection(request.getToolId(), scopeId);
            
            if (!connection.getClient().isConnected()) {
                result.setValid(false);
                result.getErrors().add("MCP server is not connected");
            }
            
            connectionPool.releaseConnection(connection);
        } catch (Exception e) {
            result.setValid(false);
            result.getErrors().add("Failed to connect to MCP server: " + e.getMessage());
        }
        
        return result;
    }
    
    @Override
    public ToolState getState(String scopeId) {
        return null;
    }
    
    @Override
    public void setState(String scopeId, ToolState state) {
    }
    
    @Override
    public void initialize(ToolDefinition tool) {
        MCPClient client = mcpClientFactory.createClient(tool.getConfig());
        MCPConnection connection = new MCPConnection();
        connection.setConnectionId(UUID.randomUUID().toString());
        connection.setServerId(tool.getId());
        connection.setClient(client);
        connection.setState(ConnectionState.CONNECTED);
        connection.setCreatedAt(LocalDateTime.now());
        
        connectionPool.addConnection(connection);
    }
    
    @Override
    public void destroy(String toolId) {
        connectionPool.closeAll(toolId);
    }
    
    @Override
    public boolean healthCheck() {
        return true;
    }
    
    private String getScopeId(ToolExecutionRequest request) {
        return request.getSessionId() != null ? request.getSessionId() : request.getUserId();
    }
}
```

### 5.2 MCP 连接池实现

```java
@Service
public class MCPConnectionPoolImpl implements MCPConnectionPool {
    
    private final Map<String, Queue<MCPConnection>> pool = new ConcurrentHashMap<>();
    private final Map<String, MCPConnection> activeConnections = new ConcurrentHashMap<>();
    private final Map<String, MCPConnectionConfig> configs = new ConcurrentHashMap<>();
    
    private final int maxPoolSize = 10;
    private final long connectionTtl = 3600000; // 1 hour
    
    @Autowired
    private MCPClientFactory mcpClientFactory;
    
    @Override
    public MCPConnection getConnection(String serverId, String scopeId) {
        String poolKey = serverId + ":" + scopeId;
        
        Queue<MCPConnection> queue = pool.computeIfAbsent(poolKey, k -> new ConcurrentLinkedQueue<>());
        
        MCPConnection connection = queue.poll();
        
        if (connection == null || !connection.getClient().isConnected()) {
            connection = createNewConnection(serverId, scopeId);
        }
        
        connection.setLastUsedAt(LocalDateTime.now());
        activeConnections.put(connection.getConnectionId(), connection);
        
        return connection;
    }
    
    @Override
    public void releaseConnection(MCPConnection connection) {
        String connectionId = connection.getConnectionId();
        activeConnections.remove(connectionId);
        
        String poolKey = connection.getServerId() + ":" + getScopeIdFromConnectionId(connectionId);
        Queue<MCPConnection> queue = pool.get(poolKey);
        
        if (queue != null && queue.size() < maxPoolSize) {
            queue.offer(connection);
        } else {
            closeConnection(connection);
        }
    }
    
    @Override
    public void closeAll() {
        pool.values().forEach(queue -> queue.forEach(this::closeConnection));
        pool.clear();
        activeConnections.values().forEach(this::closeConnection);
        activeConnections.clear();
    }
    
    public void closeAll(String serverId) {
        pool.entrySet().stream()
            .filter(entry -> entry.getKey().startsWith(serverId + ":"))
            .forEach(entry -> entry.getValue().forEach(this::closeConnection));
        
        activeConnections.entrySet().stream()
            .filter(entry -> entry.getValue().getServerId().equals(serverId))
            .forEach(entry -> closeConnection(entry.getValue()));
    }
    
    @Override
    public MCPConnectionStats getStats() {
        MCPConnectionStats stats = new MCPConnectionStats();
        stats.setTotalConnections(pool.values().stream().mapToInt(Queue::size).sum());
        stats.setActiveConnections(activeConnections.size());
        stats.setPoolSize(maxPoolSize);
        stats.setConnectionTtl(connectionTtl);
        return stats;
    }
    
    @Override
    public void cleanupExpiredConnections() {
        LocalDateTime now = LocalDateTime.now();
        
        pool.forEach((key, queue) -> {
            queue.removeIf(connection -> {
                long age = Duration.between(connection.getLastUsedAt(), now).toMillis();
                return age > connectionTtl;
            });
        });
        
        activeConnections.forEach((id, connection) -> {
            long age = Duration.between(connection.getLastUsedAt(), now).toMillis();
            if (age > connectionTtl) {
                closeConnection(connection);
                activeConnections.remove(id);
            }
        });
    }
    
    private MCPConnection createNewConnection(String serverId, String scopeId) {
        MCPConnectionConfig config = configs.get(serverId);
        if (config == null) {
            throw new IllegalArgumentException("MCP server config not found: " + serverId);
        }
        
        MCPClient client = mcpClientFactory.createClient(config);
        
        MCPConnection connection = new MCPConnection();
        connection.setConnectionId(UUID.randomUUID().toString());
        connection.setServerId(serverId);
        connection.setScopeId(scopeId);
        connection.setClient(client);
        connection.setState(ConnectionState.CONNECTED);
        connection.setCreatedAt(LocalDateTime.now());
        connection.setLastUsedAt(LocalDateTime.now());
        connection.setTtl(connectionTtl);
        
        return connection;
    }
    
    private void closeConnection(MCPConnection connection) {
        try {
            connection.getClient().close();
        } catch (Exception e) {
            log.error("Failed to close MCP connection: {}", connection.getConnectionId(), e);
        }
    }
    
    private String getScopeIdFromConnectionId(String connectionId) {
        return connectionId.split(":")[1];
    }
    
    public void addConnectionConfig(String serverId, MCPConnectionConfig config) {
        configs.put(serverId, config);
    }
}
```

### 5.3 MCP 客户端工厂

```java
@Service
public class MCPClientFactory {
    
    @Autowired
    private ObjectMapper objectMapper;
    
    public MCPClient createClient(MCPConnectionConfig config) {
        switch (config.getTransportType()) {
            case STDIO:
                return createStdioClient(config);
            case SSE:
                return createSSEClient(config);
            case WEBSOCKET:
                return createWebSocketClient(config);
            default:
                throw new IllegalArgumentException("Unknown transport type: " + config.getTransportType());
        }
    }
    
    private MCPClient createStdioClient(MCPConnectionConfig config) {
        return new StdioMCPClient(config);
    }
    
    private MCPClient createSSEClient(MCPConnectionConfig config) {
        return new SSEMCPClient(config);
    }
    
    private MCPClient createWebSocketClient(MCPConnectionConfig config) {
        return new WebSocketMCPClient(config);
    }
}
```

### 5.4 Stdio MCP 客户端

```java
public class StdioMCPClient implements MCPClient {
    
    private final MCPConnectionConfig config;
    private Process process;
    private BufferedReader reader;
    private BufferedWriter writer;
    
    public StdioMCPClient(MCPConnectionConfig config) {
        this.config = config;
        initialize();
    }
    
    private void initialize() {
        try {
            ProcessBuilder pb = new ProcessBuilder(config.getCommand());
            pb.redirectErrorStream(true);
            process = pb.start();
            
            reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()));
            
            sendInitialize();
        } catch (IOException e) {
            throw new RuntimeException("Failed to initialize MCP client", e);
        }
    }
    
    private void sendInitialize() {
        Map<String, Object> request = Map.of(
            "jsonrpc", "2.0",
            "id", 1,
            "method", "initialize",
            "params", Map.of(
                "protocolVersion", "2024-11-05",
                "capabilities", Map.of(),
                "clientInfo", Map.of(
                    "name", "Foggy Navigator",
                    "version", "1.0.0"
                )
            )
        );
        
        sendRequest(request);
    }
    
    @Override
    public List<ToolDefinition> listTools() {
        Map<String, Object> request = Map.of(
            "jsonrpc", "2.0",
            "id", 2,
            "method", "tools/list"
        );
        
        Map<String, Object> response = sendRequest(request);
        List<Map<String, Object>> tools = (List<Map<String, Object>>) response.get("result");
        
        return tools.stream()
            .map(this::toToolDefinition)
            .collect(Collectors.toList());
    }
    
    @Override
    public MCPToolResult callTool(String toolName, Map<String, Object> arguments) {
        Map<String, Object> request = Map.of(
            "jsonrpc", "2.0",
            "id", UUID.randomUUID().toString(),
            "method", "tools/call",
            "params", Map.of(
                "name", toolName,
                "arguments", arguments
            )
        );
        
        Map<String, Object> response = sendRequest(request);
        Map<String, Object> result = (Map<String, Object>) response.get("result");
        
        MCPToolResult mcpResult = new MCPToolResult();
        mcpResult.setContent((String) result.get("content"));
        mcpResult.setContentType((String) result.get("type"));
        mcpResult.setMetadata((Map<String, Object>) result.get("metadata"));
        mcpResult.setIsError((Boolean) result.get("isError"));
        
        return mcpResult;
    }
    
    @Override
    public MCPResource getResource(String uri) {
        Map<String, Object> request = Map.of(
            "jsonrpc", "2.0",
            "id", UUID.randomUUID().toString(),
            "method", "resources/read",
            "params", Map.of("uri", uri)
        );
        
        Map<String, Object> response = sendRequest(request);
        Map<String, Object> result = (Map<String, Object>) response.get("result");
        
        return toMCPResource(result);
    }
    
    @Override
    public List<MCPResource> listResources() {
        Map<String, Object> request = Map.of(
            "jsonrpc", "2.0",
            "id", UUID.randomUUID().toString(),
            "method", "resources/list"
        );
        
        Map<String, Object> response = sendRequest(request);
        List<Map<String, Object>> resources = (List<Map<String, Object>>) response.get("result");
        
        return resources.stream()
            .map(this::toMCPResource)
            .collect(Collectors.toList());
    }
    
    @Override
    public MCPPrompt getPrompt(String name) {
        Map<String, Object> request = Map.of(
            "jsonrpc", "2.0",
            "id", UUID.randomUUID().toString(),
            "method", "prompts/get",
            "params", Map.of("name", name)
        );
        
        Map<String, Object> response = sendRequest(request);
        Map<String, Object> result = (Map<String, Object>) response.get("result");
        
        return toMCPPrompt(result);
    }
    
    @Override
    public List<MCPPrompt> listPrompts() {
        Map<String, Object> request = Map.of(
            "jsonrpc", "2.0",
            "id", UUID.randomUUID().toString(),
            "method", "prompts/list"
        );
        
        Map<String, Object> response = sendRequest(request);
        List<Map<String, Object>> prompts = (List<Map<String, Object>>) response.get("result");
        
        return prompts.stream()
            .map(this::toMCPPrompt)
            .collect(Collectors.toList());
    }
    
    @Override
    public void close() {
        try {
            if (writer != null) {
                writer.close();
            }
            if (reader != null) {
                reader.close();
            }
            if (process != null) {
                process.destroy();
            }
        } catch (IOException e) {
            log.error("Failed to close MCP client", e);
        }
    }
    
    @Override
    public boolean isConnected() {
        return process != null && process.isAlive();
    }
    
    private Map<String, Object> sendRequest(Map<String, Object> request) {
        try {
            String json = objectMapper.writeValueAsString(request);
            writer.write(json + "\n");
            writer.flush();
            
            String responseJson = reader.readLine();
            return objectMapper.readValue(responseJson, Map.class);
        } catch (IOException e) {
            throw new RuntimeException("Failed to send MCP request", e);
        }
    }
    
    private ToolDefinition toToolDefinition(Map<String, Object> data) {
        ToolDefinition tool = new ToolDefinition();
        tool.setId((String) data.get("name"));
        tool.setName((String) data.get("name"));
        tool.setDescription((String) data.get("description"));
        tool.setType(ToolType.MCP);
        
        List<Map<String, Object>> inputSchema = (List<Map<String, Object>>) data.get("inputSchema");
        if (inputSchema != null) {
            List<ToolParameter> parameters = inputSchema.stream()
                .map(this::toToolParameter)
                .collect(Collectors.toList());
            tool.setParameters(parameters);
        }
        
        return tool;
    }
    
    private ToolParameter toToolParameter(Map<String, Object> data) {
        ToolParameter param = new ToolParameter();
        param.setName((String) data.get("name"));
        param.setType((String) data.get("type"));
        param.setDescription((String) data.get("description"));
        param.setRequired((Boolean) data.get("required"));
        return param;
    }
    
    private MCPResource toMCPResource(Map<String, Object> data) {
        MCPResource resource = new MCPResource();
        resource.setUri((String) data.get("uri"));
        resource.setName((String) data.get("name"));
        resource.setDescription((String) data.get("description"));
        resource.setMimeType((String) data.get("mimeType"));
        resource.setMetadata((Map<String, Object>) data.get("metadata"));
        return resource;
    }
    
    private MCPPrompt toMCPPrompt(Map<String, Object> data) {
        MCPPrompt prompt = new MCPPrompt();
        prompt.setName((String) data.get("name"));
        prompt.setDescription((String) data.get("description"));
        
        List<Map<String, Object>> arguments = (List<Map<String, Object>>) data.get("arguments");
        if (arguments != null) {
            List<MCPPromptArgument> promptArgs = arguments.stream()
                .map(arg -> {
                    MCPPromptArgument promptArg = new MCPPromptArgument();
                    promptArg.setName((String) arg.get("name"));
                    promptArg.setDescription((String) arg.get("description"));
                    promptArg.setRequired((Boolean) arg.get("required"));
                    return promptArg;
                })
                .collect(Collectors.toList());
            prompt.setArguments(promptArgs);
        }
        
        return prompt;
    }
}
```

### 5.5 SSE MCP 客户端

```java
public class SSEMCPClient implements MCPClient {
    
    private final MCPConnectionConfig config;
    private WebClient webClient;
    private String sessionId;
    
    public SSEMCPClient(MCPConnectionConfig config) {
        this.config = config;
        initialize();
    }
    
    private void initialize() {
        webClient = WebClient.builder()
            .baseUrl(config.getUrl())
            .build();
        
        sendInitialize();
    }
    
    private void sendInitialize() {
        Map<String, Object> request = Map.of(
            "jsonrpc", "2.0",
            "id", 1,
            "method", "initialize",
            "params", Map.of(
                "protocolVersion", "2024-11-05",
                "capabilities", Map.of(),
                "clientInfo", Map.of(
                    "name", "Foggy Navigator",
                    "version", "1.0.0"
                )
            )
        );
        
        sendRequest(request);
    }
    
    @Override
    public List<ToolDefinition> listTools() {
        Map<String, Object> request = Map.of(
            "jsonrpc", "2.0",
            "id", 2,
            "method", "tools/list"
        );
        
        Map<String, Object> response = sendRequest(request);
        List<Map<String, Object>> tools = (List<Map<String, Object>>) response.get("result");
        
        return tools.stream()
            .map(this::toToolDefinition)
            .collect(Collectors.toList());
    }
    
    @Override
    public MCPToolResult callTool(String toolName, Map<String, Object> arguments) {
        Map<String, Object> request = Map.of(
            "jsonrpc", "2.0",
            "id", UUID.randomUUID().toString(),
            "method", "tools/call",
            "params", Map.of(
                "name", toolName,
                "arguments", arguments
            )
        );
        
        Map<String, Object> response = sendRequest(request);
        Map<String, Object> result = (Map<String, Object>) response.get("result");
        
        MCPToolResult mcpResult = new MCPToolResult();
        mcpResult.setContent((String) result.get("content"));
        mcpResult.setContentType((String) result.get("type"));
        mcpResult.setMetadata((Map<String, Object>) result.get("metadata"));
        mcpResult.setIsError((Boolean) result.get("isError"));
        
        return mcpResult;
    }
    
    @Override
    public MCPResource getResource(String uri) {
        Map<String, Object> request = Map.of(
            "jsonrpc", "2.0",
            "id", UUID.randomUUID().toString(),
            "method", "resources/read",
            "params", Map.of("uri", uri)
        );
        
        Map<String, Object> response = sendRequest(request);
        Map<String, Object> result = (Map<String, Object>) response.get("result");
        
        return toMCPResource(result);
    }
    
    @Override
    public List<MCPResource> listResources() {
        Map<String, Object> request = Map.of(
            "jsonrpc", "2.0",
            "id", UUID.randomUUID().toString(),
            "method", "resources/list"
        );
        
        Map<String, Object> response = sendRequest(request);
        List<Map<String, Object>> resources = (List<Map<String, Object>>) response.get("result");
        
        return resources.stream()
            .map(this::toMCPResource)
            .collect(Collectors.toList());
    }
    
    @Override
    public MCPPrompt getPrompt(String name) {
        Map<String, Object> request = Map.of(
            "jsonrpc", "2.0",
            "id", UUID.randomUUID().toString(),
            "method", "prompts/get",
            "params", Map.of("name", name)
        );
        
        Map<String, Object> response = sendRequest(request);
        Map<String, Object> result = (Map<String, Object>) response.get("result");
        
        return toMCPPrompt(result);
    }
    
    @Override
    public List<MCPPrompt> listPrompts() {
        Map<String, Object> request = Map.of(
            "jsonrpc", "2.0",
            "id", UUID.randomUUID().toString(),
            "method", "prompts/list"
        );
        
        Map<String, Object> response = sendRequest(request);
        List<Map<String, Object>> prompts = (List<Map<String, Object>>) response.get("result");
        
        return prompts.stream()
            .map(this::toMCPPrompt)
            .collect(Collectors.toList());
    }
    
    @Override
    public void close() {
        if (sessionId != null) {
            webClient.post()
                .uri("/close")
                .bodyValue(Map.of("sessionId", sessionId))
                .retrieve()
                .bodyToMono(Void.class)
                .block();
        }
    }
    
    @Override
    public boolean isConnected() {
        return sessionId != null;
    }
    
    private Map<String, Object> sendRequest(Map<String, Object> request) {
        return webClient.post()
            .uri("/rpc")
            .bodyValue(request)
            .retrieve()
            .bodyToMono(Map.class)
            .block();
    }
    
    private ToolDefinition toToolDefinition(Map<String, Object> data) {
        ToolDefinition tool = new ToolDefinition();
        tool.setId((String) data.get("name"));
        tool.setName((String) data.get("name"));
        tool.setDescription((String) data.get("description"));
        tool.setType(ToolType.MCP);
        
        List<Map<String, Object>> inputSchema = (List<Map<String, Object>>) data.get("inputSchema");
        if (inputSchema != null) {
            List<ToolParameter> parameters = inputSchema.stream()
                .map(this::toToolParameter)
                .collect(Collectors.toList());
            tool.setParameters(parameters);
        }
        
        return tool;
    }
    
    private ToolParameter toToolParameter(Map<String, Object> data) {
        ToolParameter param = new ToolParameter();
        param.setName((String) data.get("name"));
        param.setType((String) data.get("type"));
        param.setDescription((String) data.get("description"));
        param.setRequired((Boolean) data.get("required"));
        return param;
    }
    
    private MCPResource toMCPResource(Map<String, Object> data) {
        MCPResource resource = new MCPResource();
        resource.setUri((String) data.get("uri"));
        resource.setName((String) data.get("name"));
        resource.setDescription((String) data.get("description"));
        resource.setMimeType((String) data.get("mimeType"));
        resource.setMetadata((Map<String, Object>) data.get("metadata"));
        return resource;
    }
    
    private MCPPrompt toMCPPrompt(Map<String, Object> data) {
        MCPPrompt prompt = new MCPPrompt();
        prompt.setName((String) data.get("name"));
        prompt.setDescription((String) data.get("description"));
        
        List<Map<String, Object>> arguments = (List<Map<String, Object>>) data.get("arguments");
        if (arguments != null) {
            List<MCPPromptArgument> promptArgs = arguments.stream()
                .map(arg -> {
                    MCPPromptArgument promptArg = new MCPPromptArgument();
                    promptArg.setName((String) arg.get("name"));
                    promptArg.setDescription((String) arg.get("description"));
                    promptArg.setRequired((Boolean) arg.get("required"));
                    return promptArg;
                })
                .collect(Collectors.toList());
            prompt.setArguments(promptArgs);
        }
        
        return prompt;
    }
}
```

## 6. 工具服务实现

### 6.1 工具服务实现

```java
@Service
public class ToolServiceImpl implements ToolService {
    
    @Autowired
    private ToolRegistry toolRegistry;
    
    @Autowired
    private ToolExecutor toolExecutor;
    
    @Autowired
    private ToolPermissionManager permissionManager;
    
    @Autowired
    private ToolStateManager stateManager;
    
    @Autowired
    private Map<ToolType, ToolAdapter> adapters;
    
    @Autowired
    private HITLManager hitlManager;
    
    @Override
    public void registerTool(ToolDefinition tool) {
        toolRegistry.register(tool);
        
        ToolAdapter adapter = adapters.get(tool.getType());
        if (adapter != null) {
            adapter.initialize(tool);
        }
    }
    
    @Override
    public void unregisterTool(String toolId) {
        ToolDefinition tool = toolRegistry.find(toolId);
        if (tool != null) {
            ToolAdapter adapter = adapters.get(tool.getType());
            if (adapter != null) {
                adapter.destroy(toolId);
            }
        }
        
        toolRegistry.unregister(toolId);
    }
    
    @Override
    public ToolDefinition getTool(String toolId) {
        return toolRegistry.find(toolId);
    }
    
    @Override
    public List<ToolDefinition> getAllTools() {
        return toolRegistry.findAll();
    }
    
    @Override
    public List<ToolDefinition> getUserTools(String userId) {
        List<ToolDefinition> allTools = toolRegistry.findAll();
        
        return allTools.stream()
            .filter(tool -> permissionManager.hasPermission(userId, tool))
            .collect(Collectors.toList());
    }
    
    @Override
    public List<ToolDefinition> getSessionTools(String sessionId) {
        String userId = getUserIdFromSessionId(sessionId);
        return getUserTools(userId);
    }
    
    @Override
    public ToolExecutionResult executeTool(ToolExecutionRequest request) {
        ToolDefinition tool = toolRegistry.find(request.getToolId());
        if (tool == null) {
            throw new IllegalArgumentException("Tool not found: " + request.getToolId());
        }
        
        // 检查权限
        if (!permissionManager.hasPermission(request.getUserId(), tool)) {
            throw new SecurityException("No permission to use tool: " + request.getToolId());
        }
        
        // 检查是否需要人工确认
        if (hitlManager.requiresApproval(request)) {
            HITLRequest hitlRequest = hitlManager.createApprovalRequest(request);
            
            ToolExecutionResult result = new ToolExecutionResult();
            result.setExecutionId(request.getExecutionId());
            result.setToolId(request.getToolId());
            result.setStatus(ExecutionStatus.PENDING);
            result.setResult(Map.of(
                "hitl_request_id", hitlRequest.getRequestId(),
                "message", "Tool execution requires approval"
            ));
            result.setExecutedAt(LocalDateTime.now());
            
            return result;
        }
        
        // 验证工具调用
        ToolValidationResult validation = validateToolCall(request);
        if (!validation.getValid()) {
            throw new IllegalArgumentException("Invalid tool call: " + String.join(", ", validation.getErrors()));
        }
        
        // 执行工具
        ToolAdapter adapter = adapters.get(tool.getType());
        return adapter.execute(request);
    }
    
    @Override
    public List<ToolExecutionResult> executeTools(List<ToolExecutionRequest> requests) {
        return requests.stream()
            .map(this::executeTool)
            .collect(Collectors.toList());
    }
    
    @Override
    public ToolValidationResult validateToolCall(ToolExecutionRequest request) {
        ToolDefinition tool = toolRegistry.find(request.getToolId());
        if (tool == null) {
            ToolValidationResult result = new ToolValidationResult();
            result.setValid(false);
            result.setErrors(List.of("Tool not found: " + request.getToolId()));
            return result;
        }
        
        ToolAdapter adapter = adapters.get(tool.getType());
        return adapter.validate(request);
    }
    
    @Override
    public ToolState getToolState(String toolId, String scopeId) {
        return stateManager.getState(toolId, scopeId);
    }
    
    @Override
    public void setToolState(String toolId, String scopeId, ToolState state) {
        stateManager.setState(toolId, scopeId, state);
    }
    
    @Override
    public void clearToolState(String toolId, String scopeId) {
        stateManager.clearState(toolId, scopeId);
    }
    
    private String getUserIdFromSessionId(String sessionId) {
        return sessionId.split("-")[1];
    }
}
```

### 6.2 工具注册表实现

```java
@Service
public class ToolRegistryImpl implements ToolRegistry {
    
    private final Map<String, ToolDefinition> tools = new ConcurrentHashMap<>();
    
    @Autowired
    private ToolRepository toolRepository;
    
    @PostConstruct
    public void initialize() {
        loadToolsFromDatabase();
    }
    
    private void loadToolsFromDatabase() {
        List<ToolDefinition> tools = toolRepository.findAll();
        tools.forEach(tool -> this.tools.put(tool.getId(), tool));
    }
    
    @Override
    public void register(ToolDefinition tool) {
        tools.put(tool.getId(), tool);
        toolRepository.save(tool);
    }
    
    @Override
    public void unregister(String toolId) {
        tools.remove(toolId);
        toolRepository.deleteById(toolId);
    }
    
    @Override
    public ToolDefinition find(String toolId) {
        return tools.get(toolId);
    }
    
    @Override
    public List<ToolDefinition> findAll() {
        return new ArrayList<>(tools.values());
    }
    
    @Override
    public List<ToolDefinition> findByType(ToolType type) {
        return tools.values().stream()
            .filter(tool -> tool.getType() == type)
            .collect(Collectors.toList());
    }
    
    @Override
    public List<ToolDefinition> findByCategory(String category) {
        return tools.values().stream()
            .filter(tool -> category.equals(tool.getCategory()))
            .collect(Collectors.toList());
    }
    
    @Override
    public boolean exists(String toolId) {
        return tools.containsKey(toolId);
    }
}
```

### 6.3 工具权限管理器

```java
@Service
public class ToolPermissionManager {
    
    @Autowired
    private UserRepository userRepository;
    
    @Autowired
    private RoleRepository roleRepository;
    
    public boolean hasPermission(String userId, ToolDefinition tool) {
        ToolPermission permission = tool.getPermission();
        if (permission == null || permission.getEnabled() == null || permission.getEnabled()) {
            return true;
        }
        
        User user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            return false;
        }
        
        // 检查拒绝列表
        if (permission.getDeniedUsers() != null && permission.getDeniedUsers().contains(userId)) {
            return false;
        }
        
        if (permission.getDeniedRoles() != null) {
            for (String roleId : user.getRoleIds()) {
                if (permission.getDeniedRoles().contains(roleId)) {
                    return false;
                }
            }
        }
        
        // 检查允许列表
        if (permission.getAllowedUsers() != null && !permission.getAllowedUsers().isEmpty()) {
            return permission.getAllowedUsers().contains(userId);
        }
        
        if (permission.getAllowedRoles() != null && !permission.getAllowedRoles().isEmpty()) {
            for (String roleId : user.getRoleIds()) {
                if (permission.getAllowedRoles().contains(roleId)) {
                    return true;
                }
            }
            return false;
        }
        
        return true;
    }
    
    public void checkRateLimit(String userId, ToolDefinition tool) {
        ToolPermission permission = tool.getPermission();
        if (permission == null || permission.getRateLimit() == null) {
            return;
        }
        
        String key = "tool_rate_limit:" + userId + ":" + tool.getId();
        long currentCount = redisTemplate.opsForValue().increment(key);
        
        if (currentCount == 1) {
            redisTemplate.expire(key, 1, TimeUnit.MINUTES);
        }
        
        if (currentCount > permission.getRateLimit()) {
            throw new RateLimitExceededException("Rate limit exceeded for tool: " + tool.getId());
        }
    }
}
```

### 6.4 工具状态管理器

```java
@Service
public class ToolStateManager {
    
    @Autowired
    private ToolStateRepository toolStateRepository;
    
    public ToolState getState(String toolId, String scopeId) {
        String stateId = toolId + ":" + scopeId;
        return toolStateRepository.findById(stateId).orElse(null);
    }
    
    public void setState(String toolId, String scopeId, ToolState state) {
        String stateId = toolId + ":" + scopeId;
        state.setId(stateId);
        state.setToolId(toolId);
        state.setScopeId(scopeId);
        state.setLastAccessedAt(LocalDateTime.now());
        
        toolStateRepository.save(state);
    }
    
    public void clearState(String toolId, String scopeId) {
        String stateId = toolId + ":" + scopeId;
        toolStateRepository.deleteById(stateId);
    }
    
    public void clearAllStates(String toolId) {
        List<ToolState> states = toolStateRepository.findByToolId(toolId);
        states.forEach(state -> toolStateRepository.delete(state));
    }
    
    public void clearExpiredStates() {
        LocalDateTime now = LocalDateTime.now();
        List<ToolState> expiredStates = toolStateRepository.findExpiredStates(now);
        expiredStates.forEach(state -> toolStateRepository.delete(state));
    }
}
```

## 7. 工具隔离设计

### 7.1 作用域隔离

```java
@Service
public class ToolScopeManager {
    
    private final Map<String, ToolScope> scopes = new ConcurrentHashMap<>();
    
    /**
     * 创建用户作用域
     */
    public ToolScope createUserScope(String userId) {
        String scopeId = "user:" + userId;
        ToolScope scope = new ToolScope();
        scope.setId(scopeId);
        scope.setType(ScopeType.USER);
        scope.setOwnerId(userId);
        scope.setCreatedAt(LocalDateTime.now());
        
        scopes.put(scopeId, scope);
        return scope;
    }
    
    /**
     * 创建 session 作用域
     */
    public ToolScope createSessionScope(String sessionId, String userId) {
        String scopeId = "session:" + sessionId;
        ToolScope scope = new ToolScope();
        scope.setId(scopeId);
        scope.setType(ScopeType.SESSION);
        scope.setOwnerId(userId);
        scope.setCreatedAt(LocalDateTime.now());
        
        scopes.put(scopeId, scope);
        return scope;
    }
    
    /**
     * 获取作用域
     */
    public ToolScope getScope(String scopeId) {
        return scopes.get(scopeId);
    }
    
    /**
     * 删除作用域
     */
    public void deleteScope(String scopeId) {
        ToolScope scope = scopes.remove(scopeId);
        if (scope != null) {
            clearScopeState(scope);
        }
    }
    
    private void clearScopeState(ToolScope scope) {
        toolStateRepository.deleteByScopeId(scope.getId());
    }
}
```

### 7.2 连接隔离

```java
@Service
public class MCPConnectionIsolationManager {
    
    private final MCPConnectionPool connectionPool;
    
    /**
     * 为用户创建独立的连接
     */
    public MCPConnection getUserConnection(String serverId, String userId) {
        String scopeId = "user:" + userId;
        return connectionPool.getConnection(serverId, scopeId);
    }
    
    /**
     * 为 session 创建独立的连接
     */
    public MCPConnection getSessionConnection(String serverId, String sessionId) {
        String scopeId = "session:" + sessionId;
        return connectionPool.getConnection(serverId, scopeId);
    }
    
    /**
     * 释放用户连接
     */
    public void releaseUserConnection(MCPConnection connection, String userId) {
        String scopeId = "user:" + userId;
        connectionPool.releaseConnection(connection);
    }
    
    /**
     * 释放 session 连接
     */
    public void releaseSessionConnection(MCPConnection connection, String sessionId) {
        String scopeId = "session:" + sessionId;
        connectionPool.releaseConnection(connection);
    }
    
    /**
     * 清理用户所有连接
     */
    public void cleanupUserConnections(String userId) {
        connectionPool.closeAllByScope("user:" + userId);
    }
    
    /**
     * 清理 session 所有连接
     */
    public void cleanupSessionConnections(String sessionId) {
        connectionPool.closeAllByScope("session:" + sessionId);
    }
}
```

## 8. HITL 管理器实现

### 8.1 HITL 管理器

```java
@Service
public class HITLManagerImpl implements HITLManager {
    
    @Autowired
    private HITLRequestRepository hitlRequestRepository;
    
    @Autowired
    private ToolRegistry toolRegistry;
    
    @Autowired
    private UserRepository userRepository;
    
    @Autowired
    private NotificationService notificationService;
    
    private final Map<String, HITLPolicy> toolPolicies = new ConcurrentHashMap<>();
    
    @Override
    public boolean requiresApproval(ToolExecutionRequest request) {
        ToolDefinition tool = toolRegistry.find(request.getToolId());
        if (tool == null) {
            return false;
        }
        
        HITLPolicy policy = getToolHITLPolicy(request.getToolId());
        if (policy == null || !policy.getEnabled()) {
            return false;
        }
        
        return evaluateTrigger(policy, request);
    }
    
    @Override
    public HITLRequest createApprovalRequest(ToolExecutionRequest request) {
        ToolDefinition tool = toolRegistry.find(request.getToolId());
        HITLPolicy policy = getToolHITLPolicy(request.getToolId());
        
        HITLRequest hitlRequest = new HITLRequest();
        hitlRequest.setRequestId(UUID.randomUUID().toString());
        hitlRequest.setToolId(request.getToolId());
        hitlRequest.setToolName(tool.getName());
        hitlRequest.setUserId(request.getUserId());
        hitlRequest.setSessionId(request.getSessionId());
        hitlRequest.setParameters(request.getParameters());
        hitlRequest.setReason(generateReason(request, policy));
        hitlRequest.setStatus(HITLRequestStatus.PENDING);
        hitlRequest.setRequestedBy(request.getUserId());
        hitlRequest.setCreatedAt(LocalDateTime.now());
        
        if (policy.getTimeout() != null) {
            hitlRequest.setExpiresAt(LocalDateTime.now().plusSeconds(policy.getTimeout()));
        }
        
        hitlRequestRepository.save(hitlRequest);
        
        // 通知审批人
        notifyApprovers(hitlRequest, policy);
        
        return hitlRequest;
    }
    
    @Override
    public List<HITLRequest> getPendingRequests(String userId) {
        return hitlRequestRepository.findByApproverAndStatus(userId, HITLRequestStatus.PENDING);
    }
    
    @Override
    public void approveRequest(String requestId, String userId, String comment) {
        HITLRequest request = hitlRequestRepository.findById(requestId).orElse(null);
        if (request == null) {
            throw new IllegalArgumentException("Request not found: " + requestId);
        }
        
        if (request.getStatus() != HITLRequestStatus.PENDING) {
            throw new IllegalStateException("Request is not pending: " + requestId);
        }
        
        request.setStatus(HITLRequestStatus.APPROVED);
        request.setApprovedBy(userId);
        request.setApprovalComment(comment);
        request.setApprovedAt(LocalDateTime.now());
        
        hitlRequestRepository.save(request);
        
        // 执行工具
        executeApprovedTool(request);
    }
    
    @Override
    public void rejectRequest(String requestId, String userId, String comment) {
        HITLRequest request = hitlRequestRepository.findById(requestId).orElse(null);
        if (request == null) {
            throw new IllegalArgumentException("Request not found: " + requestId);
        }
        
        if (request.getStatus() != HITLRequestStatus.PENDING) {
            throw new IllegalStateException("Request is not pending: " + requestId);
        }
        
        request.setStatus(HITLRequestStatus.REJECTED);
        request.setRejectedBy(userId);
        request.setRejectionComment(comment);
        request.setRejectedAt(LocalDateTime.now());
        
        hitlRequestRepository.save(request);
        
        // 通知请求人
        notifyRejection(request);
    }
    
    @Override
    public HITLRequest getRequest(String requestId) {
        return hitlRequestRepository.findById(requestId).orElse(null);
    }
    
    @Override
    public List<HITLRequest> getRequestHistory(String userId, String toolId) {
        if (toolId != null) {
            return hitlRequestRepository.findByUserIdAndToolIdOrderByCreatedAtDesc(userId, toolId);
        }
        return hitlRequestRepository.findByUserIdOrderByCreatedAtDesc(userId);
    }
    
    @Override
    public void cancelRequest(String requestId) {
        HITLRequest request = hitlRequestRepository.findById(requestId).orElse(null);
        if (request == null) {
            throw new IllegalArgumentException("Request not found: " + requestId);
        }
        
        if (request.getStatus() != HITLRequestStatus.PENDING) {
            throw new IllegalStateException("Request is not pending: " + requestId);
        }
        
        request.setStatus(HITLRequestStatus.CANCELLED);
        hitlRequestRepository.save(request);
    }
    
    @Override
    public void setToolHITLPolicy(String toolId, HITLPolicy policy) {
        toolPolicies.put(toolId, policy);
    }
    
    @Override
    public HITLPolicy getToolHITLPolicy(String toolId) {
        return toolPolicies.get(toolId);
    }
    
    @Override
    public void setBatchHITLPolicies(Map<String, HITLPolicy> policies) {
        toolPolicies.putAll(policies);
    }
    
    private boolean evaluateTrigger(HITLPolicy policy, ToolExecutionRequest request) {
        switch (policy.getTrigger()) {
            case ALWAYS:
                return true;
            case NEVER:
                return false;
            case ON_RISKY_OPERATION:
                return isRiskyOperation(request);
            case ON_DATA_MODIFICATION:
                return isDataModification(request);
            case ON_EXTERNAL_ACCESS:
                return isExternalAccess(request);
            case ON_HIGH_COST:
                return isHighCost(request);
            case CUSTOM:
                return evaluateCustomTrigger(policy, request);
            default:
                return false;
        }
    }
    
    private boolean isRiskyOperation(ToolExecutionRequest request) {
        String toolId = request.getToolId();
        return toolId.contains("delete") || 
               toolId.contains("remove") || 
               toolId.contains("drop");
    }
    
    private boolean isDataModification(ToolExecutionRequest request) {
        String toolId = request.getToolId();
        return toolId.contains("write") || 
               toolId.contains("update") || 
               toolId.contains("modify");
    }
    
    private boolean isExternalAccess(ToolExecutionRequest request) {
        String toolId = request.getToolId();
        return toolId.contains("http") || 
               toolId.contains("api") || 
               toolId.contains("external");
    }
    
    private boolean isHighCost(ToolExecutionRequest request) {
        Map<String, Object> parameters = request.getParameters();
        Object cost = parameters.get("cost");
        if (cost instanceof Number) {
            return ((Number) cost).doubleValue() > 100;
        }
        return false;
    }
    
    private boolean evaluateCustomTrigger(HITLPolicy policy, ToolExecutionRequest request) {
        Map<String, Object> customRules = policy.getCustomRules();
        if (customRules == null) {
            return false;
        }
        
        for (Map.Entry<String, Object> entry : customRules.entrySet()) {
            String ruleKey = entry.getKey();
            Object ruleValue = entry.getValue();
            
            Object paramValue = request.getParameters().get(ruleKey);
            if (!ruleValue.equals(paramValue)) {
                return false;
            }
        }
        
        return true;
    }
    
    private String generateReason(ToolExecutionRequest request, HITLPolicy policy) {
        StringBuilder reason = new StringBuilder();
        reason.append("Tool execution requires approval based on policy: ");
        reason.append(policy.getTrigger());
        
        if (policy.getTrigger() == HITLTrigger.ON_RISKY_OPERATION) {
            reason.append(" (risky operation detected)");
        } else if (policy.getTrigger() == HITLTrigger.ON_DATA_MODIFICATION) {
            reason.append(" (data modification detected)");
        } else if (policy.getTrigger() == HITLTrigger.ON_EXTERNAL_ACCESS) {
            reason.append(" (external access detected)");
        } else if (policy.getTrigger() == HITLTrigger.ON_HIGH_COST) {
            reason.append(" (high cost detected)");
        }
        
        return reason.toString();
    }
    
    private void notifyApprovers(HITLRequest request, HITLPolicy policy) {
        if (policy.getApprovers() == null || policy.getApprovers().isEmpty()) {
            return;
        }
        
        for (String approverId : policy.getApprovers()) {
            notificationService.sendNotification(
                approverId,
                "Tool Approval Required",
                String.format(
                    "Tool '%s' execution requires approval. Request ID: %s",
                    request.getToolName(),
                    request.getRequestId()
                ),
                Map.of(
                    "requestId", request.getRequestId(),
                    "toolId", request.getToolId(),
                    "toolName", request.getToolName(),
                    "userId", request.getUserId()
                )
            );
        }
    }
    
    private void notifyRejection(HITLRequest request) {
        notificationService.sendNotification(
            request.getUserId(),
            "Tool Execution Rejected",
            String.format(
                "Tool '%s' execution has been rejected. Reason: %s",
                request.getToolName(),
                request.getRejectionComment()
            ),
            Map.of(
                "requestId", request.getRequestId(),
                "toolId", request.getToolId(),
                "toolName", request.getToolName()
            )
        );
    }
    
    private void executeApprovedTool(HITLRequest request) {
        ToolExecutionRequest executionRequest = new ToolExecutionRequest();
        executionRequest.setExecutionId(UUID.randomUUID().toString());
        executionRequest.setToolId(request.getToolId());
        executionRequest.setUserId(request.getUserId());
        executionRequest.setSessionId(request.getSessionId());
        executionRequest.setParameters(request.getParameters());
        
        // 异步执行工具
        CompletableFuture.runAsync(() -> {
            try {
                ToolExecutionResult result = toolService.executeTool(executionRequest);
                log.info("Tool executed successfully after approval: {}", request.getRequestId());
            } catch (Exception e) {
                log.error("Failed to execute tool after approval: {}", request.getRequestId(), e);
            }
        });
    }
    
    @Scheduled(fixedRate = 60000) // 每分钟检查一次
    public void cleanupExpiredRequests() {
        LocalDateTime now = LocalDateTime.now();
        List<HITLRequest> expiredRequests = hitlRequestRepository.findExpiredRequests(now);
        
        expiredRequests.forEach(request -> {
            request.setStatus(HITLRequestStatus.EXPIRED);
            hitlRequestRepository.save(request);
            
            notifyRejection(request);
        });
    }
}
```

### 8.2 HITL 请求仓库

```java
@Repository
public interface HITLRequestRepository extends JpaRepository<HITLRequest, String> {
    
    List<HITLRequest> findByApproverAndStatus(String approverId, HITLRequestStatus status);
    
    List<HITLRequest> findByUserIdOrderByCreatedAtDesc(String userId);
    
    List<HITLRequest> findByUserIdAndToolIdOrderByCreatedAtDesc(String userId, String toolId);
    
    List<HITLRequest> findExpiredRequests(LocalDateTime now);
}
```

### 8.3 HITL 策略示例

#### 示例 1: 危险操作需要确认
```java
HITLPolicy riskyOperationPolicy = new HITLPolicy();
riskyOperationPolicy.setEnabled(true);
riskyOperationPolicy.setTrigger(HITLTrigger.ON_RISKY_OPERATION);
riskyOperationPolicy.setAction(HITLAction.BLOCK_UNTIL_APPROVED);
riskyOperationPolicy.setApprovers(List.of("admin", "supervisor"));
riskyOperationPolicy.setTimeout(3600); // 1 hour

hitlManager.setToolHITLPolicy("file_delete", riskyOperationPolicy);
```

#### 示例 2: 数据修改需要确认
```java
HITLPolicy dataModificationPolicy = new HITLPolicy();
dataModificationPolicy.setEnabled(true);
dataModificationPolicy.setTrigger(HITLTrigger.ON_DATA_MODIFICATION);
dataModificationPolicy.setAction(HITLAction.BLOCK_UNTIL_APPROVED);
dataModificationPolicy.setApprovers(List.of("data_admin"));
dataModificationPolicy.setTimeout(1800); // 30 minutes

hitlManager.setToolHITLPolicy("database_update", dataModificationPolicy);
```

#### 示例 3: 外部访问需要警告
```java
HITLPolicy externalAccessPolicy = new HITLPolicy();
externalAccessPolicy.setEnabled(true);
externalAccessPolicy.setTrigger(HITLTrigger.ON_EXTERNAL_ACCESS);
externalAccessPolicy.setAction(HITLAction.ALLOW_WITH_WARNING);
externalAccessPolicy.setTimeout(0); // 不需要确认，只需警告

hitlManager.setToolHITLPolicy("http_request", externalAccessPolicy);
```

#### 示例 4: 高成本操作需要确认
```java
HITLPolicy highCostPolicy = new HITLPolicy();
highCostPolicy.setEnabled(true);
highCostPolicy.setTrigger(HITLTrigger.ON_HIGH_COST);
highCostPolicy.setAction(HITLAction.BLOCK_UNTIL_APPROVED);
highCostPolicy.setApprovers(List.of("finance_admin"));
highCostPolicy.setTimeout(7200); // 2 hours

hitlManager.setToolHITLPolicy("api_call", highCostPolicy);
```

## 9. 配置管理

### 8.1 配置文件

```yaml
# application.yml
tools:
  # 内置工具配置
  builtin:
    enabled: true
    tools:
      - id: file_operation
        name: File Operation
        description: Perform file operations
        category: system
        enabled: true
      - id: http_request
        name: HTTP Request
        description: Make HTTP requests
        category: network
        enabled: true
      - id: database_query
        name: Database Query
        description: Execute database queries
        category: database
        enabled: true
  
  # MCP 工具配置
  mcp:
    enabled: true
    servers:
      - id: filesystem
        name: Filesystem MCP Server
        type: stdio
        command: ["npx", "-y", "@modelcontextprotocol/server-filesystem", "/path/to/files"]
        enabled: true
      - id: github
        name: GitHub MCP Server
        type: sse
        url: http://localhost:3000/mcp
        enabled: true
      - id: brave-search
        name: Brave Search MCP Server
        type: websocket
        url: ws://localhost:3001/mcp
        enabled: true
  
  # 连接池配置
  connection-pool:
    max-size: 10
    min-idle: 2
    max-idle: 5
    ttl: 3600000  # 1 hour
    cleanup-interval: 300000  # 5 minutes
  
  # 权限配置
  permission:
    default-enabled: true
    rate-limit:
      enabled: true
      default-limit: 100
      window: 60000  # 1 minute
  
  # 状态管理配置
  state:
    enabled: true
    persistence: true
    ttl: 86400000  # 24 hours
    cleanup-interval: 3600000  # 1 hour
  
  # HITL 配置
  hitl:
    enabled: true
    default-policy:
      enabled: false
      trigger: NEVER
      action: BLOCK_UNTIL_APPROVED
      timeout: 3600  # 1 hour
    tool-policies:
      - tool-id: file_delete
        enabled: true
        trigger: ON_RISKY_OPERATION
        action: BLOCK_UNTIL_APPROVED
        approvers: ["admin", "supervisor"]
        timeout: 3600
      - tool-id: database_update
        enabled: true
        trigger: ON_DATA_MODIFICATION
        action: BLOCK_UNTIL_APPROVED
        approvers: ["data_admin"]
        timeout: 1800
      - tool-id: http_request
        enabled: true
        trigger: ON_EXTERNAL_ACCESS
        action: ALLOW_WITH_WARNING
        timeout: 0
```

### 8.2 配置类

```java
@Configuration
@ConfigurationProperties(prefix = "tools")
@Data
public class ToolProperties {
    
    private BuiltinProperties builtin;
    private MCPProperties mcp;
    private ConnectionPoolProperties connectionPool;
    private PermissionProperties permission;
    private StateProperties state;
    private HITLProperties hitl;
    
    @Data
    public static class BuiltinProperties {
        private Boolean enabled = true;
        private List<ToolConfig> tools;
    }
    
    @Data
    public static class MCPProperties {
        private Boolean enabled = true;
        private List<MCPServerConfig> servers;
    }
    
    @Data
    public static class MCPServerConfig {
        private String id;
        private String name;
        private MCPTransportType type;
        private List<String> command;
        private String url;
        private Map<String, Object> config;
        private Boolean enabled = true;
    }
    
    @Data
    public static class ConnectionPoolProperties {
        private Integer maxSize = 10;
        private Integer minIdle = 2;
        private Integer maxIdle = 5;
        private Long ttl = 3600000L;
        private Long cleanupInterval = 300000L;
    }
    
    @Data
    public static class PermissionProperties {
        private Boolean defaultEnabled = true;
        private RateLimitProperties rateLimit;
    }
    
    @Data
    public static class RateLimitProperties {
        private Boolean enabled = true;
        private Integer defaultLimit = 100;
        private Long window = 60000L;
    }
    
    @Data
    public static class StateProperties {
        private Boolean enabled = true;
        private Boolean persistence = true;
        private Long ttl = 86400000L;
        private Long cleanupInterval = 3600000L;
    }
    
    @Data
    public static class HITLProperties {
        private Boolean enabled = true;
        private HITLPolicyProperties defaultPolicy;
        private List<ToolHITLPolicyProperties> toolPolicies;
    }
    
    @Data
    public static class HITLPolicyProperties {
        private Boolean enabled = false;
        private HITLTrigger trigger = HITLTrigger.NEVER;
        private HITLAction action = HITLAction.BLOCK_UNTIL_APPROVED;
        private Integer timeout = 3600;
    }
    
    @Data
    public static class ToolHITLPolicyProperties {
        private String toolId;
        private Boolean enabled = true;
        private HITLTrigger trigger;
        private HITLAction action;
        private List<String> approvers;
        private Integer timeout;
    }
}
```

## 9. 数据库设计

### 9.1 工具定义表

```sql
CREATE TABLE tools (
    id VARCHAR(100) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    type ENUM('BUILTIN', 'MCP', 'CUSTOM') NOT NULL,
    category VARCHAR(100),
    parameters JSON,
    result_type VARCHAR(50),
    config JSON,
    permission JSON,
    metadata JSON,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    
    INDEX idx_type (type),
    INDEX idx_category (category),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

### 9.2 工具执行记录表

```sql
CREATE TABLE tool_executions (
    id VARCHAR(100) PRIMARY KEY,
    tool_id VARCHAR(100) NOT NULL,
    user_id VARCHAR(100) NOT NULL,
    session_id VARCHAR(100),
    parameters JSON,
    status ENUM('PENDING', 'RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED', 'TIMEOUT') NOT NULL,
    result JSON,
    error TEXT,
    execution_time BIGINT,
    token_count INT,
    metadata JSON,
    executed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    
    INDEX idx_tool_id (tool_id),
    INDEX idx_user_id (user_id),
    INDEX idx_session_id (session_id),
    INDEX idx_status (status),
    INDEX idx_executed_at (executed_at),
    FOREIGN KEY (tool_id) REFERENCES tools(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

### 9.3 工具状态表

```sql
CREATE TABLE tool_states (
    id VARCHAR(100) PRIMARY KEY,
    tool_id VARCHAR(100) NOT NULL,
    scope_id VARCHAR(100) NOT NULL,
    data JSON,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_accessed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    ttl BIGINT,
    
    INDEX idx_tool_id (tool_id),
    INDEX idx_scope_id (scope_id),
    INDEX idx_last_accessed_at (last_accessed_at),
    FOREIGN KEY (tool_id) REFERENCES tools(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

## 10. API 设计

### 10.1 工具管理 API

```java
@RestController
@RequestMapping("/api/tools")
public class ToolController {
    
    @Autowired
    private ToolService toolService;
    
    @GetMapping
    public ResponseEntity<List<ToolDefinition>> getAllTools() {
        List<ToolDefinition> tools = toolService.getAllTools();
        return ResponseEntity.ok(tools);
    }
    
    @GetMapping("/{toolId}")
    public ResponseEntity<ToolDefinition> getTool(@PathVariable String toolId) {
        ToolDefinition tool = toolService.getTool(toolId);
        return ResponseEntity.ok(tool);
    }
    
    @GetMapping("/user/{userId}")
    public ResponseEntity<List<ToolDefinition>> getUserTools(@PathVariable String userId) {
        List<ToolDefinition> tools = toolService.getUserTools(userId);
        return ResponseEntity.ok(tools);
    }
    
    @GetMapping("/session/{sessionId}")
    public ResponseEntity<List<ToolDefinition>> getSessionTools(@PathVariable String sessionId) {
        List<ToolDefinition> tools = toolService.getSessionTools(sessionId);
        return ResponseEntity.ok(tools);
    }
    
    @PostMapping
    public ResponseEntity<Void> registerTool(@RequestBody ToolDefinition tool) {
        toolService.registerTool(tool);
        return ResponseEntity.ok().build();
    }
    
    @DeleteMapping("/{toolId}")
    public ResponseEntity<Void> unregisterTool(@PathVariable String toolId) {
        toolService.unregisterTool(toolId);
        return ResponseEntity.ok().build();
    }
}
```

### 10.2 工具执行 API

```java
@RestController
@RequestMapping("/api/tools/execute")
public class ToolExecutionController {
    
    @Autowired
    private ToolService toolService;
    
    @PostMapping
    public ResponseEntity<ToolExecutionResult> executeTool(@RequestBody ToolExecutionRequest request) {
        ToolExecutionResult result = toolService.executeTool(request);
        return ResponseEntity.ok(result);
    }
    
    @PostMapping("/batch")
    public ResponseEntity<List<ToolExecutionResult>> executeTools(@RequestBody List<ToolExecutionRequest> requests) {
        List<ToolExecutionResult> results = toolService.executeTools(requests);
        return ResponseEntity.ok(results);
    }
    
    @PostMapping("/validate")
    public ResponseEntity<ToolValidationResult> validateToolCall(@RequestBody ToolExecutionRequest request) {
        ToolValidationResult result = toolService.validateToolCall(request);
        return ResponseEntity.ok(result);
    }
}
```

### 10.3 工具状态 API

```java
@RestController
@RequestMapping("/api/tools/state")
public class ToolStateController {
    
    @Autowired
    private ToolService toolService;
    
    @GetMapping("/{toolId}/{scopeId}")
    public ResponseEntity<ToolState> getToolState(
        @PathVariable String toolId,
        @PathVariable String scopeId
    ) {
        ToolState state = toolService.getToolState(toolId, scopeId);
        return ResponseEntity.ok(state);
    }
    
    @PutMapping("/{toolId}/{scopeId}")
    public ResponseEntity<Void> setToolState(
        @PathVariable String toolId,
        @PathVariable String scopeId,
        @RequestBody ToolState state
    ) {
        toolService.setToolState(toolId, scopeId, state);
        return ResponseEntity.ok().build();
    }
    
    @DeleteMapping("/{toolId}/{scopeId}")
    public ResponseEntity<Void> clearToolState(
        @PathVariable String toolId,
        @PathVariable String scopeId
    ) {
        toolService.clearToolState(toolId, scopeId);
        return ResponseEntity.ok().build();
    }
}
```

### 10.4 HITL API

```java
@RestController
@RequestMapping("/api/tools/hitl")
public class HITLController {
    
    @Autowired
    private HITLManager hitlManager;
    
    @GetMapping("/pending")
    public ResponseEntity<List<HITLRequest>> getPendingRequests(
        @RequestParam String userId
    ) {
        List<HITLRequest> requests = hitlManager.getPendingRequests(userId);
        return ResponseEntity.ok(requests);
    }
    
    @GetMapping("/request/{requestId}")
    public ResponseEntity<HITLRequest> getRequest(@PathVariable String requestId) {
        HITLRequest request = hitlManager.getRequest(requestId);
        return ResponseEntity.ok(request);
    }
    
    @GetMapping("/history")
    public ResponseEntity<List<HITLRequest>> getRequestHistory(
        @RequestParam String userId,
        @RequestParam(required = false) String toolId
    ) {
        List<HITLRequest> history = hitlManager.getRequestHistory(userId, toolId);
        return ResponseEntity.ok(history);
    }
    
    @PostMapping("/approve")
    public ResponseEntity<Void> approveRequest(
        @RequestParam String requestId,
        @RequestParam String userId,
        @RequestParam(required = false) String comment
    ) {
        hitlManager.approveRequest(requestId, userId, comment);
        return ResponseEntity.ok().build();
    }
    
    @PostMapping("/reject")
    public ResponseEntity<Void> rejectRequest(
        @RequestParam String requestId,
        @RequestParam String userId,
        @RequestParam(required = false) String comment
    ) {
        hitlManager.rejectRequest(requestId, userId, comment);
        return ResponseEntity.ok().build();
    }
    
    @PostMapping("/cancel")
    public ResponseEntity<Void> cancelRequest(@RequestParam String requestId) {
        hitlManager.cancelRequest(requestId);
        return ResponseEntity.ok().build();
    }
    
    @GetMapping("/policy/{toolId}")
    public ResponseEntity<HITLPolicy> getToolHITLPolicy(@PathVariable String toolId) {
        HITLPolicy policy = hitlManager.getToolHITLPolicy(toolId);
        return ResponseEntity.ok(policy);
    }
    
    @PutMapping("/policy/{toolId}")
    public ResponseEntity<Void> setToolHITLPolicy(
        @PathVariable String toolId,
        @RequestBody HITLPolicy policy
    ) {
        hitlManager.setToolHITLPolicy(toolId, policy);
        return ResponseEntity.ok().build();
    }
    
    @PutMapping("/policies/batch")
    public ResponseEntity<Void> setBatchHITLPolicies(
        @RequestBody Map<String, HITLPolicy> policies
    ) {
        hitlManager.setBatchHITLPolicies(policies);
        return ResponseEntity.ok().build();
    }
}
```

## 11. 最佳实践

### 11.1 工具隔离
- 每个用户和 session 拥有独立的工具实例
- 使用作用域隔离工具状态
- 使用连接池隔离 MCP 连接

### 11.2 连接管理
- 使用连接池管理 MCP 连接
- 设置合理的连接 TTL
- 定期清理过期连接

### 11.3 权限控制
- 基于用户和角色的权限控制
- 实现速率限制
- 记录工具执行日志

### 11.4 状态管理
- 持久化工具状态
- 设置合理的 TTL
- 定期清理过期状态

## 12. 实施计划

### Phase 1: 基础设施（1周）
- 设计数据模型
- 创建数据库表
- 实现工具注册表
- 实现工具执行器

### Phase 2: 内置工具（1周）
- 实现内置工具适配器
- 实现常见内置工具
- 实现权限管理器
- 实现状态管理器

### Phase 3: MCP 工具（2周）
- 实现 MCP 客户端
- 实现 MCP 连接池
- 实现 MCP 工具适配器
- 实现连接隔离

### Phase 4: 集成与测试（1周）
- 与 LangChain4j 集成
- 编写单元测试
- 编写集成测试
- 性能测试

### Phase 5: 文档与部署（1周）
- 编写使用文档
- 编写开发文档
- 部署到生产环境
- 监控和优化

## 13. 扩展方向

### 13.1 工具编排
- 支持工具链式调用
- 支持工具并行调用
- 支持工具依赖管理

### 13.2 工具监控
- 实时监控工具执行
- 工具性能分析
- 工具异常告警

### 13.3 工具市场
- 工具发布和订阅
- 工具评分和评价
- 工具推荐

### 13.4 工具沙箱
- 工具执行隔离
- 资源限制
- 安全审计

### 13.5 HITL 增强
- 支持多级审批流程
- 支持审批委派
- 支持批量审批
- 支持审批模板
- 支持审批统计分析
- 支持审批审计日志
- 集成第三方审批系统
- 支持移动端审批

### 13.6 智能审批
- 基于历史数据的智能推荐
- 风险评估模型
- 自动化审批规则
- 异常检测和告警
