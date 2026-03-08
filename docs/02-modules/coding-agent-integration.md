# 编程 Agent 集成设计

> 基于 OpenHands 的语义层编辑 Agent 集成方案

## 1. 概述

### 1.1 目标

集成 OpenHands 作为编程 Agent，实现语义层 JavaScript 文件的自动化编辑，包括：
- 根据用户需求修改 TM/QM 文件
- 实时验证语义层文件的正确性
- 自动提交代码并创建 Pull Request

### 1.2 核心原则

- **实时验证**：每次文件修改后立即验证，而非等到 commit
- **环境隔离**：OpenHands 在独立容器中运行，验证服务独立部署
- **技能注入**：通过 System Prompt 注入 TM/QM 生成技能
- **共享工作空间**：OpenHands 和 Validation Service 共享文件目录

## 2. 架构设计

### 2.1 整体架构

```
┌─────────────────────────────────────────────────────────────┐
│                    Foggy Navigator                          │
│  ┌──────────────────────────────────────────────────────┐  │
│  │  CodingAgentService                                  │  │
│  │  - 创建编辑任务                                       │  │
│  │  - 调用 OpenHands API                                │  │
│  │  - 触发实时验证                                       │  │
│  │  - 处理验证结果                                       │  │
│  └──────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
         │                           │
         ↓                           ↓
┌──────────────────┐        ┌──────────────────┐
│  OpenHands       │        │  Validation      │
│  Container       │───────>│  Service         │
│  - Git checkout  │ 共享   │  - 加载模型      │
│  - 编辑 TM/QM    │ 目录   │  - 验证表字段    │
│  - 监听文件变化  │        │  - 返回错误      │
└──────────────────┘        └──────────────────┘
         │                           │
         ↓                           ↓
┌──────────────────┐        ┌──────────────────┐
│  GitLab/GitHub   │        │  User Database   │
│  - 语义层项目    │        │  - MySQL/PG      │
└──────────────────┘        └──────────────────┘
```

### 2.2 共享工作空间设计

```yaml
# docker-compose.yml
services:
  openhands:
    image: ghcr.io/all-hands-ai/openhands:main
    volumes:
      - shared-workspace:/opt/workspace  # 共享工作空间

  validation-service:
    image: foggy-validation:latest
    volumes:
      - shared-workspace:/workspace      # 同一个工作空间

volumes:
  shared-workspace:  # 共享卷
```

## 3. 核心模块设计

### 3.1 模块位置

```
backend/src/main/java/com/foggy/navigator/
├── foundation/
│   └── git/
│       ├── OpenHandsService.java          # OpenHands API 客户端
│       ├── ValidationService.java         # 验证服务客户端
│       └── model/
│           ├── OpenHandsSession.java
│           ├── ValidationRequest.java
│           └── ValidationResult.java
│
└── agent/
    └── CodingAgent.java                   # 编程 Agent 实现
```

### 3.2 OpenHandsService 接口

```java
public interface OpenHandsService {

    /**
     * 创建语义层编辑会话
     * @param request 编辑请求
     * @return 会话信息
     */
    OpenHandsSession createSemanticLayerSession(SemanticLayerEditRequest request);

    /**
     * 执行编辑任务
     * @param sessionId 会话ID
     * @param task 任务描述
     * @return 任务执行结果
     */
    TaskResult executeTask(String sessionId, String task);

    /**
     * 获取工作空间路径
     * @param sessionId 会话ID
     * @return 工作空间绝对路径
     */
    String getWorkspacePath(String sessionId);

    /**
     * 关闭会话
     * @param sessionId 会话ID
     */
    void closeSession(String sessionId);
}
```

### 3.3 ValidationService 接口

```java
public interface ValidationService {

    /**
     * 验证语义层文件
     * @param workspacePath 语义层文件目录路径
     * @return 验证结果
     */
    ValidationResult validate(String workspacePath);

    /**
     * 获取验证服务状态
     * @return 服务是否可用
     */
    boolean isAvailable();
}
```

## 4. 数据模型

### 4.1 SemanticLayerEditRequest

```java
public class SemanticLayerEditRequest {
    private String userId;              // 用户ID
    private String gitRepoUrl;          // Git 仓库地址
    private String branch;              // 分支名称
    private String task;                // 编辑任务描述
    private GitCredentials credentials; // Git 凭证
}
```

### 4.2 OpenHandsSession

```java
public class OpenHandsSession {
    private String sessionId;           // 会话ID
    private String workspacePath;       // 工作空间路径
    private String status;              // 会话状态
    private LocalDateTime createdAt;    // 创建时间
}
```

### 4.3 ValidationResult

```java
public class ValidationResult {
    private boolean success;            // 验证是否通过
    private List<ValidationError> errors; // 错误列表
    private String message;             // 验证消息
    private LocalDateTime validatedAt;  // 验证时间
}

public class ValidationError {
    private String file;                // 文件路径
    private String type;                // 错误类型
    private String message;             // 错误信息
    private Integer line;               // 行号（可选）
}
```

## 5. 工作流程

### 5.1 完整流程

```
1. 用户发起编辑请求
   ↓
2. CodingAgent 创建 OpenHands Session
   - 调用 OpenHands API
   - 传递 Git 仓库信息
   - 注入 TM/QM 生成技能（System Prompt）
   ↓
3. OpenHands 在容器内操作
   - Git clone 语义层项目到共享工作空间
   - 根据任务编辑 TM/QM 文件
   ↓
4. 文件修改后触发实时验证
   - CodingAgent 调用 ValidationService
   - 传递工作空间路径
   ↓
5. ValidationService 验证
   - 加载工作空间下的所有模型文件
   - 验证表和字段的正确性
   - 返回验证结果
   ↓
6. 处理验证结果
   - ✅ 验证通过 → 继续下一步编辑或提交
   - ❌ 验证失败 → 将错误反馈给 OpenHands，继续修复
   ↓
7. 所有验证通过后
   - OpenHands 提交代码到 Git
   - 创建 Pull Request
   - 关闭会话
```

### 5.2 实时验证触发机制

```java
// 方案 1: 轮询检测文件变化
public class FileWatcher {
    public void watchWorkspace(String workspacePath, Consumer<String> onFileChanged) {
        // 使用 WatchService 监听文件变化
        // 文件变化时触发验证
    }
}

// 方案 2: OpenHands 主动通知（推荐）
// OpenHands 每次修改文件后，调用回调 API
POST /api/coding-agent/file-changed
{
    "sessionId": "session-123",
    "changedFiles": ["FactOrderModel.tm", "FactOrderQueryModel.qm"]
}
```

## 6. System Prompt 设计

### 6.1 技能注入

```java
public class SystemPromptBuilder {

    public String buildSemanticLayerPrompt() {
        return """
            你是 Foggy Dataset Model 的语义层编辑专家。

            # 工作流程
            1. 理解用户的编辑需求
            2. 修改或创建 TM/QM 文件
            3. 每次修改文件后，等待验证结果
            4. 如果验证失败，根据错误信息修复
            5. 所有验证通过后，提交代码

            # TM 生成技能
            %s

            # QM 生成技能
            %s

            # 验证反馈处理
            - 当收到验证错误时，仔细阅读错误信息
            - 常见错误：表名不存在、字段名错误、类型不匹配
            - 修复后重新保存文件，等待下一次验证

            # 注意事项
            - 严格按照 TM/QM 语法规范编写
            - 文件名必须与模型名称一致：${model.name}.tm
            - 修改文件后立即保存，触发验证
            - 不要在验证失败时提交代码
            """.formatted(loadTmSkill(), loadQmSkill());
    }
}
```

### 6.2 技能文件存储

```
backend/src/main/resources/
└── openhands/
    └── skills/
        ├── tm-generate.md      # TM 生成技能
        ├── qm-generate.md      # QM 生成技能
        └── validation-guide.md # 验证错误处理指南
```

## 7. API 设计

### 7.1 OpenHands API 调用

```java
@Service
public class OpenHandsServiceImpl implements OpenHandsService {

    private final RestTemplate restTemplate;
    private final String openHandsUrl = "http://localhost:13000";

    @Override
    public OpenHandsSession createSemanticLayerSession(SemanticLayerEditRequest request) {
        // 构建请求
        Map<String, Object> payload = Map.of(
            "workspace", "/opt/workspace/" + UUID.randomUUID(),
            "git_repo", request.getGitRepoUrl(),
            "git_branch", request.getBranch(),
            "system_prompt", systemPromptBuilder.buildSemanticLayerPrompt(),
            "git_credentials", request.getCredentials()
        );

        // 调用 OpenHands API
        ResponseEntity<Map> response = restTemplate.postForEntity(
            openHandsUrl + "/api/sessions",
            payload,
            Map.class
        );

        // 解析响应
        Map<String, Object> data = response.getBody();
        return OpenHandsSession.builder()
            .sessionId((String) data.get("session_id"))
            .workspacePath((String) data.get("workspace_path"))
            .status("active")
            .createdAt(LocalDateTime.now())
            .build();
    }

    @Override
    public TaskResult executeTask(String sessionId, String task) {
        Map<String, Object> payload = Map.of(
            "task", task
        );

        ResponseEntity<Map> response = restTemplate.postForEntity(
            openHandsUrl + "/api/sessions/" + sessionId + "/execute",
            payload,
            Map.class
        );

        return parseTaskResult(response.getBody());
    }
}
```

### 7.2 Validation Service API 调用

```java
@Service
public class ValidationServiceImpl implements ValidationService {

    private final RestTemplate restTemplate;
    private final String validationUrl = "http://validation-service:8081";

    @Override
    public ValidationResult validate(String workspacePath) {
        Map<String, Object> payload = Map.of(
            "workspace_path", workspacePath
        );

        ResponseEntity<ValidationResult> response = restTemplate.postForEntity(
            validationUrl + "/api/validation/validate",
            payload,
            ValidationResult.class
        );

        return response.getBody();
    }
}
```

## 8. 配置管理

### 8.1 application.yml

```yaml
foggy:
  coding-agent:
    openhands:
      url: http://localhost:13000
      timeout: 300000  # 5分钟超时

    validation:
      url: http://validation-service:8081
      enabled: true
      realtime: true   # 实时验证

    git:
      default-branch: main
      auto-commit: true
      auto-pr: true
```

### 8.2 Docker Compose 配置

```yaml
version: '3.8'

services:
  foggy-navigator:
    image: foggy-navigator:latest
    ports:
      - "8080:8080"
    environment:
      - OPENHANDS_URL=http://openhands:3000
      - VALIDATION_URL=http://validation-service:8081
    depends_on:
      - openhands
      - validation-service

  openhands:
    image: ghcr.io/all-hands-ai/openhands:main
    privileged: true
    ports:
      - "13000:3000"
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock
      - shared-workspace:/opt/workspace

  validation-service:
    image: foggy-validation:latest
    ports:
      - "8081:8081"
    environment:
      - SPRING_DATASOURCE_URL=${DB_URL}
      - SPRING_DATASOURCE_USERNAME=${DB_USER}
      - SPRING_DATASOURCE_PASSWORD=${DB_PASSWORD}
    volumes:
      - shared-workspace:/workspace

volumes:
  shared-workspace:
```

## 9. 错误处理

### 9.1 验证失败处理

```java
public class ValidationErrorHandler {

    public void handleValidationError(
        String sessionId,
        ValidationResult result
    ) {
        if (!result.isSuccess()) {
            // 构建错误反馈
            String feedback = buildErrorFeedback(result.getErrors());

            // 发送给 OpenHands 继续修复
            openHandsService.executeTask(sessionId,
                "验证失败，请修复以下错误：\n" + feedback
            );
        }
    }

    private String buildErrorFeedback(List<ValidationError> errors) {
        StringBuilder sb = new StringBuilder();
        for (ValidationError error : errors) {
            sb.append(String.format(
                "- 文件 %s: %s\n",
                error.getFile(),
                error.getMessage()
            ));
        }
        return sb.toString();
    }
}
```

### 9.2 超时处理

```java
public class TimeoutHandler {

    @Scheduled(fixedRate = 60000) // 每分钟检查
    public void checkTimeouts() {
        List<OpenHandsSession> sessions = sessionRepository.findActiveSessions();

        for (OpenHandsSession session : sessions) {
            if (isTimeout(session)) {
                // 关闭超时会话
                openHandsService.closeSession(session.getSessionId());

                // 通知用户
                notificationService.notifyTimeout(session.getUserId());
            }
        }
    }
}
```

## 10. 监控和日志

### 10.1 关键指标

- OpenHands 会话创建成功率
- 平均编辑时长
- 验证通过率
- 验证失败原因分布
- PR 创建成功率

### 10.2 日志记录

```java
@Slf4j
public class CodingAgentService {

    public void editSemanticLayer(SemanticLayerEditRequest request) {
        log.info("开始编辑语义层: userId={}, repo={}",
            request.getUserId(), request.getGitRepoUrl());

        try {
            // 创建会话
            OpenHandsSession session = openHandsService.createSession(request);
            log.info("OpenHands 会话创建成功: sessionId={}", session.getSessionId());

            // 执行任务
            TaskResult result = openHandsService.executeTask(session.getSessionId(), request.getTask());
            log.info("任务执行完成: sessionId={}, status={}",
                session.getSessionId(), result.getStatus());

            // 验证
            ValidationResult validation = validationService.validate(session.getWorkspacePath());
            log.info("验证完成: success={}, errors={}",
                validation.isSuccess(), validation.getErrors().size());

        } catch (Exception e) {
            log.error("编辑语义层失败: userId={}", request.getUserId(), e);
            throw new CodingAgentException("编辑失败", e);
        }
    }
}
```

## 11. 安全考虑

### 11.1 Git 凭证管理

```java
public class GitCredentialsManager {

    @Autowired
    private EncryptionService encryptionService;

    public GitCredentials getCredentials(String userId) {
        // 从加密存储中获取凭证
        String encryptedToken = credentialsRepository.findByUserId(userId);
        String token = encryptionService.decrypt(encryptedToken);

        return GitCredentials.builder()
            .type("token")
            .token(token)
            .build();
    }
}
```

### 11.2 工作空间隔离

```java
public class WorkspaceManager {

    public String createIsolatedWorkspace(String userId) {
        // 为每个用户创建独立的工作空间
        String workspaceId = UUID.randomUUID().toString();
        String workspacePath = "/opt/workspace/" + userId + "/" + workspaceId;

        // 设置权限
        Files.createDirectories(Paths.get(workspacePath));

        return workspacePath;
    }

    public void cleanupWorkspace(String workspacePath) {
        // 清理工作空间
        FileUtils.deleteDirectory(new File(workspacePath));
    }
}
```

## 12. 后续扩展

### 12.1 多语义层项目支持

- 支持用户管理多个语义层项目
- 项目模板管理
- 项目权限控制

### 12.2 协作编辑

- 多用户同时编辑不同文件
- 冲突检测和解决
- 实时协作通知

### 12.3 版本管理

- 语义层版本历史
- 版本对比
- 版本回滚

---

**文档版本**: 1.0.0
**创建日期**: 2026-01-21
**作者**: Foggy Navigator Team
