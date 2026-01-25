# 语义层编辑完整工作流设计

> **实施阶段**: Phase 2
> **本文档作用**: 系统模块设计参考，指导实施

---


> 从用户请求到 PR 创建的端到端流程设计

## 1. 概述

### 1.1 目标

定义语义层编辑的完整工作流，包括：
- 用户发起编辑请求
- OpenHands 编辑语义层文件
- 实时验证文件正确性
- 自动提交代码并创建 PR
- 错误处理和重试机制

### 1.2 核心流程

```
用户请求 → OpenHands 编辑 → 实时验证 → 提交代码 → 创建 PR
    ↓           ↓              ↓           ↓          ↓
  创建任务    修改文件      验证通过     Git commit  通知用户
```

## 2. 完整流程图

```
┌─────────────────────────────────────────────────────────────┐
│ 1. 用户发起编辑请求                                         │
│    - 选择语义层项目                                         │
│    - 描述编辑需求                                           │
│    - 提供数据库配置                                         │
└─────────────────────────────────────────────────────────────┘
                        ↓
┌─────────────────────────────────────────────────────────────┐
│ 2. Foggy Navigator 创建编辑任务                            │
│    - 生成任务 ID                                            │
│    - 记录任务状态                                           │
│    - 准备 Git 凭证                                          │
└─────────────────────────────────────────────────────────────┘
                        ↓
┌─────────────────────────────────────────────────────────────┐
│ 3. 调用 OpenHands API                                       │
│    - 创建 Session                                           │
│    - 注入 TM/QM 生成技能                                    │
│    - 传递 Git 仓库信息                                      │
└─────────────────────────────────────────────────────────────┘
                        ↓
┌─────────────────────────────────────────────────────────────┐
│ 4. OpenHands 在容器内操作                                   │
│    - Git clone 项目到共享工作空间                           │
│    - 创建新分支 (feature/xxx)                               │
│    - 理解用户需求                                           │
│    - 开始编辑 TM/QM 文件                                    │
└─────────────────────────────────────────────────────────────┘
                        ↓
┌─────────────────────────────────────────────────────────────┐
│ 5. 文件修改后触发实时验证                                   │
│    - OpenHands 保存文件                                     │
│    - 通知 Foggy Navigator 文件已修改                       │
│    - Foggy Navigator 调用 Validation Service               │
└─────────────────────────────────────────────────────────────┘
                        ↓
┌─────────────────────────────────────────────────────────────┐
│ 6. Validation Service 验证                                  │
│    - 读取共享工作空间的文件                                 │
│    - 加载所有 TM/QM 模型                                    │
│    - 验证表和字段的正确性                                   │
│    - 返回验证结果                                           │
└─────────────────────────────────────────────────────────────┘
                        ↓
                  ┌──────────┐
                  │ 验证通过? │
                  └──────────┘
                   ↙        ↘
              是 ↙            ↘ 否
                ↓              ↓
    ┌──────────────────┐  ┌──────────────────┐
    │ 7a. 继续编辑     │  │ 7b. 反馈错误     │
    │ - 检查是否完成   │  │ - 将错误发送给   │
    │ - 继续下一步     │  │   OpenHands      │
    │                  │  │ - OpenHands 修复 │
    │                  │  │ - 重新验证       │
    └──────────────────┘  └──────────────────┘
            ↓                      ↓
            └──────────┬───────────┘
                       ↓
┌─────────────────────────────────────────────────────────────┐
│ 8. 所有验证通过后提交代码                                   │
│    - OpenHands 执行 git add                                 │
│    - OpenHands 执行 git commit                              │
│    - OpenHands 执行 git push                                │
└─────────────────────────────────────────────────────────────┘
                        ↓
┌─────────────────────────────────────────────────────────────┐
│ 9. 创建 Pull Request                                        │
│    - 调用 GitLab/GitHub API                                 │
│    - 生成 PR 描述                                           │
│    - 关联任务 ID                                            │
└─────────────────────────────────────────────────────────────┘
                        ↓
┌─────────────────────────────────────────────────────────────┐
│ 10. 通知用户                                                │
│     - 更新任务状态为"已完成"                                │
│     - 发送通知（邮件/站内信）                               │
│     - 提供 PR 链接                                          │
└─────────────────────────────────────────────────────────────┘
```

## 3. 状态机设计

### 3.1 任务状态

```java
public enum TaskStatus {
    PENDING,           // 待处理
    INITIALIZING,      // 初始化中
    EDITING,           // 编辑中
    VALIDATING,        // 验证中
    VALIDATION_FAILED, // 验证失败
    FIXING,            // 修复中
    COMMITTING,        // 提交中
    CREATING_PR,       // 创建 PR 中
    COMPLETED,         // 已完成
    FAILED,            // 失败
    CANCELLED          // 已取消
}
```

### 3.2 状态转换

```
PENDING → INITIALIZING → EDITING → VALIDATING
                            ↑          ↓
                            │      验证失败
                            │          ↓
                            └─── FIXING ←┘
                                   ↓
                              验证通过
                                   ↓
                            COMMITTING → CREATING_PR → COMPLETED
                                   ↓
                                FAILED
```

### 3.3 状态机实现

```java
@Component
public class TaskStateMachine {

    public void transition(Task task, TaskStatus newStatus) {
        TaskStatus currentStatus = task.getStatus();

        // 验证状态转换是否合法
        if (!isValidTransition(currentStatus, newStatus)) {
            throw new IllegalStateException(
                String.format("非法状态转换: %s -> %s", currentStatus, newStatus)
            );
        }

        // 更新状态
        task.setStatus(newStatus);
        task.setUpdatedAt(LocalDateTime.now());

        // 记录状态变更历史
        taskHistoryRepository.save(TaskHistory.builder()
            .taskId(task.getId())
            .fromStatus(currentStatus)
            .toStatus(newStatus)
            .timestamp(LocalDateTime.now())
            .build());

        // 触发状态变更事件
        eventPublisher.publishEvent(new TaskStatusChangedEvent(task));
    }

    private boolean isValidTransition(TaskStatus from, TaskStatus to) {
        return switch (from) {
            case PENDING -> to == TaskStatus.INITIALIZING;
            case INITIALIZING -> to == TaskStatus.EDITING || to == TaskStatus.FAILED;
            case EDITING -> to == TaskStatus.VALIDATING || to == TaskStatus.FAILED;
            case VALIDATING -> to == TaskStatus.FIXING || to == TaskStatus.COMMITTING || to == TaskStatus.FAILED;
            case FIXING -> to == TaskStatus.VALIDATING || to == TaskStatus.FAILED;
            case COMMITTING -> to == TaskStatus.CREATING_PR || to == TaskStatus.FAILED;
            case CREATING_PR -> to == TaskStatus.COMPLETED || to == TaskStatus.FAILED;
            default -> false;
        };
    }
}
```

## 4. 核心服务实现

### 4.1 CodingAgentService

```java
@Service
@Slf4j
public class CodingAgentService {

    @Autowired
    private OpenHandsService openHandsService;

    @Autowired
    private ValidationService validationService;

    @Autowired
    private GitService gitService;

    @Autowired
    private TaskStateMachine stateMachine;

    @Autowired
    private NotificationService notificationService;

    /**
     * 执行语义层编辑任务
     */
    @Async
    public void executeEditTask(Task task) {
        try {
            // 1. 初始化
            stateMachine.transition(task, TaskStatus.INITIALIZING);
            OpenHandsSession session = initializeSession(task);

            // 2. 开始编辑
            stateMachine.transition(task, TaskStatus.EDITING);
            editSemanticLayer(task, session);

            // 3. 验证和修复循环
            boolean validated = validateAndFix(task, session);

            if (!validated) {
                stateMachine.transition(task, TaskStatus.FAILED);
                notificationService.notifyTaskFailed(task);
                return;
            }

            // 4. 提交代码
            stateMachine.transition(task, TaskStatus.COMMITTING);
            commitChanges(task, session);

            // 5. 创建 PR
            stateMachine.transition(task, TaskStatus.CREATING_PR);
            String prUrl = createPullRequest(task, session);

            // 6. 完成
            stateMachine.transition(task, TaskStatus.COMPLETED);
            task.setPrUrl(prUrl);
            notificationService.notifyTaskCompleted(task);

        } catch (Exception e) {
            log.error("任务执行失败: taskId={}", task.getId(), e);
            stateMachine.transition(task, TaskStatus.FAILED);
            notificationService.notifyTaskFailed(task);
        } finally {
            // 清理资源
            cleanup(task);
        }
    }

    private OpenHandsSession initializeSession(Task task) {
        SemanticLayerEditRequest request = SemanticLayerEditRequest.builder()
            .userId(task.getUserId())
            .gitRepoUrl(task.getGitRepoUrl())
            .branch(task.getBranch())
            .task(task.getDescription())
            .credentials(gitService.getCredentials(task.getUserId()))
            .build();

        return openHandsService.createSemanticLayerSession(request);
    }

    private void editSemanticLayer(Task task, OpenHandsSession session) {
        TaskResult result = openHandsService.executeTask(
            session.getSessionId(),
            task.getDescription()
        );

        if (!result.isSuccess()) {
            throw new RuntimeException("编辑失败: " + result.getMessage());
        }
    }

    private boolean validateAndFix(Task task, OpenHandsSession session) {
        int maxRetries = 3;
        int retryCount = 0;

        while (retryCount < maxRetries) {
            // 验证
            stateMachine.transition(task, TaskStatus.VALIDATING);
            ValidationResult validation = validationService.validate(
                session.getWorkspacePath()
            );

            if (validation.isSuccess()) {
                return true; // 验证通过
            }

            // 验证失败，尝试修复
            stateMachine.transition(task, TaskStatus.FIXING);
            String errorFeedback = buildErrorFeedback(validation);

            TaskResult fixResult = openHandsService.executeTask(
                session.getSessionId(),
                "验证失败，请修复以下错误：\n" + errorFeedback
            );

            if (!fixResult.isSuccess()) {
                log.error("修复失败: {}", fixResult.getMessage());
                return false;
            }

            retryCount++;
        }

        log.error("超过最大重试次数，验证仍未通过");
        return false;
    }

    private String buildErrorFeedback(ValidationResult validation) {
        StringBuilder sb = new StringBuilder();
        for (FileValidationResult fileResult : validation.getResults()) {
            if (!fileResult.isSuccess()) {
                sb.append(String.format("\n文件: %s\n", fileResult.getFile()));
                for (ValidationError error : fileResult.getErrors()) {
                    sb.append(String.format("  - %s: %s\n",
                        error.getType(), error.getMessage()));
                }
            }
        }
        return sb.toString();
    }

    private void commitChanges(Task task, OpenHandsSession session) {
        openHandsService.executeTask(
            session.getSessionId(),
            String.format("提交代码，commit message: %s", task.getCommitMessage())
        );
    }

    private String createPullRequest(Task task, OpenHandsSession session) {
        return gitService.createPullRequest(
            task.getGitRepoUrl(),
            task.getBranch(),
            "main",
            task.getPrTitle(),
            task.getPrDescription()
        );
    }

    private void cleanup(Task task) {
        // 关闭 OpenHands 会话
        // 清理工作空间
        // 释放资源
    }
}
```

### 4.2 实时验证触发器

```java
@RestController
@RequestMapping("/api/coding-agent")
@Slf4j
public class CodingAgentController {

    @Autowired
    private ValidationService validationService;

    @Autowired
    private TaskRepository taskRepository;

    /**
     * OpenHands 文件修改回调
     */
    @PostMapping("/file-changed")
    public ResponseEntity<ValidationResult> onFileChanged(
        @RequestBody FileChangedEvent event
    ) {
        log.info("文件已修改: sessionId={}, files={}",
            event.getSessionId(), event.getChangedFiles());

        // 查找对应的任务
        Task task = taskRepository.findBySessionId(event.getSessionId())
            .orElseThrow(() -> new RuntimeException("任务不存在"));

        // 触发验证
        String workspacePath = getWorkspacePath(event.getSessionId());
        ValidationResult result = validationService.validate(workspacePath);

        // 记录验证结果
        task.setLastValidationResult(result);
        taskRepository.save(task);

        return ResponseEntity.ok(result);
    }
}
```

## 5. 错误处理和重试

### 5.1 错误分类

```java
public enum ErrorType {
    VALIDATION_ERROR,      // 验证错误（可重试）
    GIT_ERROR,             // Git 操作错误（可重试）
    OPENHANDS_ERROR,       // OpenHands 错误（可重试）
    NETWORK_ERROR,         // 网络错误（可重试）
    SYSTEM_ERROR,          // 系统错误（不可重试）
    USER_CANCELLED         // 用户取消（不可重试）
}
```

### 5.2 重试策略

```java
@Component
public class RetryStrategy {

    public <T> T executeWithRetry(
        Supplier<T> operation,
        ErrorType errorType,
        int maxRetries
    ) {
        int retryCount = 0;
        Exception lastException = null;

        while (retryCount < maxRetries) {
            try {
                return operation.get();
            } catch (Exception e) {
                lastException = e;
                retryCount++;

                if (!isRetryable(errorType)) {
                    throw new RuntimeException("不可重试的错误", e);
                }

                // 指数退避
                long waitTime = (long) Math.pow(2, retryCount) * 1000;
                sleep(waitTime);

                log.warn("操作失败，第 {} 次重试", retryCount, e);
            }
        }

        throw new RuntimeException("超过最大重试次数", lastException);
    }

    private boolean isRetryable(ErrorType errorType) {
        return errorType != ErrorType.SYSTEM_ERROR &&
               errorType != ErrorType.USER_CANCELLED;
    }
}
```

## 6. 监控和通知

### 6.1 任务监控

```java
@Component
@Slf4j
public class TaskMonitor {

    @Scheduled(fixedRate = 60000) // 每分钟检查
    public void monitorTasks() {
        List<Task> activeTasks = taskRepository.findByStatusIn(
            List.of(TaskStatus.EDITING, TaskStatus.VALIDATING, TaskStatus.FIXING)
        );

        for (Task task : activeTasks) {
            // 检查超时
            if (isTimeout(task)) {
                log.warn("任务超时: taskId={}", task.getId());
                handleTimeout(task);
            }

            // 检查卡住
            if (isStuck(task)) {
                log.warn("任务卡住: taskId={}", task.getId());
                handleStuck(task);
            }
        }
    }

    private boolean isTimeout(Task task) {
        Duration duration = Duration.between(task.getCreatedAt(), LocalDateTime.now());
        return duration.toMinutes() > 30; // 30分钟超时
    }

    private boolean isStuck(Task task) {
        Duration duration = Duration.between(task.getUpdatedAt(), LocalDateTime.now());
        return duration.toMinutes() > 10; // 10分钟无更新
    }
}
```

### 6.2 用户通知

```java
@Service
public class NotificationService {

    @Autowired
    private EmailService emailService;

    @Autowired
    private WebSocketService webSocketService;

    public void notifyTaskCompleted(Task task) {
        // 实时通知（WebSocket）
        webSocketService.sendToUser(task.getUserId(), Notification.builder()
            .type("TASK_COMPLETED")
            .title("语义层编辑完成")
            .message(String.format("任务 %s 已完成，PR: %s", task.getId(), task.getPrUrl()))
            .build());

        // 邮件通知
        emailService.send(task.getUserEmail(), "语义层编辑完成",
            buildCompletedEmailContent(task));
    }

    public void notifyTaskFailed(Task task) {
        webSocketService.sendToUser(task.getUserId(), Notification.builder()
            .type("TASK_FAILED")
            .title("语义层编辑失败")
            .message(String.format("任务 %s 失败: %s", task.getId(), task.getErrorMessage()))
            .build());

        emailService.send(task.getUserEmail(), "语义层编辑失败",
            buildFailedEmailContent(task));
    }
}
```

## 7. 用户交互界面

### 7.1 任务创建界面

```typescript
// 前端代码示例
interface CreateTaskRequest {
  projectId: string;
  description: string;
  datasource: DatasourceConfig;
}

async function createEditTask(request: CreateTaskRequest) {
  const response = await api.post('/api/coding-agent/tasks', request);
  return response.data;
}
```

### 7.2 任务监控界面

```typescript
interface TaskStatus {
  taskId: string;
  status: string;
  progress: number;
  currentStep: string;
  lastValidation?: ValidationResult;
}

// 实时监控任务状态
const ws = new WebSocket('ws://localhost:8080/ws/tasks');
ws.onmessage = (event) => {
  const status: TaskStatus = JSON.parse(event.data);
  updateTaskUI(status);
};
```

## 8. 配置示例

### 8.1 完整 docker-compose.yml

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
      - SPRING_DATASOURCE_URL=jdbc:mysql://mysql:3306/foggy_navigator
    depends_on:
      - mysql
      - openhands
      - validation-service
    volumes:
      - shared-workspace:/workspace

  openhands:
    image: ghcr.io/all-hands-ai/openhands:main
    privileged: true
    ports:
      - "13000:3000"
    environment:
      - OPENAI_API_KEY=${OPENAI_API_KEY}
      - LLM_MODEL=gpt-4
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock
      - shared-workspace:/opt/workspace

  validation-service:
    image: foggy-validation:latest
    ports:
      - "8081:8081"
    volumes:
      - shared-workspace:/workspace:ro

  mysql:
    image: mysql:8.0
    environment:
      - MYSQL_ROOT_PASSWORD=password
      - MYSQL_DATABASE=foggy_navigator
    ports:
      - "3306:3306"

volumes:
  shared-workspace:
```

## 9. 性能优化

### 9.1 并发控制

```java
@Configuration
public class TaskExecutorConfig {

    @Bean
    public ThreadPoolTaskExecutor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(5);
        executor.setMaxPoolSize(10);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("coding-agent-");
        executor.initialize();
        return executor;
    }
}
```

### 9.2 缓存策略

```java
@Service
public class ValidationCacheService {

    @Cacheable(value = "validation", key = "#workspacePath + #fileHash")
    public ValidationResult getCachedValidation(String workspacePath, String fileHash) {
        // 如果文件内容未变化，返回缓存的验证结果
        return null;
    }
}
```

## 10. 总结

### 10.1 关键特性

- ✅ 实时验证，快速反馈
- ✅ 自动重试，提高成功率
- ✅ 状态机管理，流程清晰
- ✅ 错误处理，容错性强
- ✅ 用户通知，体验良好

### 10.2 后续优化

- 支持多任务并行
- 优化验证性能
- 增强错误诊断
- 添加任务优先级
- 支持任务暂停/恢复

---

**文档版本**: 1.0.0
**创建日期**: 2026-01-21
**作者**: Foggy Navigator Team
