# 可观察性系统设计文档

## 1. 概述

### 1.1 设计目标

可观察性系统（Observability System）是 Foggy Navigator 系统的重要组成部分，负责实时监控、追踪和管理系统的运行状态。其核心设计目标包括：

- **实时监控**：实时监控系统的运行状态，包括会话、任务、工具调用等
- **状态追踪**：追踪每个会话和任务的详细状态和执行流程
- **任务控制**：支持任务的暂停、继续、中止、重试等操作
- **指标收集**：收集和展示系统的关键指标
- **日志追踪**：提供完整的日志追踪和审计功能
- **告警机制**：及时发现和通知系统异常
- **数据可视化**：提供直观的数据可视化界面
- **性能分析**：分析系统性能，发现瓶颈

### 1.2 核心职责

```
┌─────────────────────────────────────────────────────────────┐
│              Observability System                            │
├─────────────────────────────────────────────────────────────┤
│  1. 会话监控                                                 │
│     - 实时监控所有会话状态                                   │
│     - 追踪会话生命周期                                       │
│     - 监控会话资源使用                                       │
├─────────────────────────────────────────────────────────────┤
│  2. 任务控制                                                 │
│     - 任务暂停/继续                                          │
│     - 任务中止/重试                                          │
│     - 任务优先级调整                                         │
├─────────────────────────────────────────────────────────────┤
│  3. 状态追踪                                                 │
│     - 追踪执行流程                                           │
│     - 记录状态变化                                           │
│     - 提供状态快照                                           │
├─────────────────────────────────────────────────────────────┤
│  4. 指标收集                                                 │
│     - 收集系统指标                                           │
│     - 收集业务指标                                           │
│     - 实时计算聚合指标                                       │
├─────────────────────────────────────────────────────────────┤
│  5. 日志追踪                                                 │
│     - 结构化日志记录                                         │
│     - 分布式追踪                                             │
│     - 日志查询和分析                                         │
├─────────────────────────────────────────────────────────────┤
│  6. 告警机制                                                 │
│     - 实时告警监测                                           │
│     - 告警规则配置                                           │
│     - 告警通知发送                                           │
├─────────────────────────────────────────────────────────────┤
│  7. 数据可视化                                               │
│     - 实时数据展示                                           │
│     - 历史趋势分析                                           │
│     - 自定义仪表板                                           │
└─────────────────────────────────────────────────────────────┘
```

### 1.3 可观察性维度

```
可观察性系统
    ├── 会话维度
    │   ├── 会话列表
    │   ├── 会话详情
    │   ├── 会话状态
    │   └── 会话历史
    │
    ├── 任务维度
    │   ├── 任务列表
    │   ├── 任务详情
    │   ├── 任务状态
    │   └── 任务控制
    │
    ├── 工具维度
    │   ├── 工具调用记录
    │   ├── 工具执行状态
    │   ├── HITL 状态
    │   └── 工具性能
    │
    ├── 模型维度
    │   ├── 模型调用记录
    │   ├── 模型性能
    │   ├── Token 使用
    │   └── 成本分析
    │
    ├── 系统维度
    │   ├── 系统资源
    │   ├── 系统性能
    │   ├── 系统健康
    │   └── 系统容量
    │
    └── 用户维度
        ├── 用户活动
        ├── 用户会话
        ├── 用户配额
        └── 用户行为
```

## 2. 系统架构

### 2.1 整体架构

```
┌─────────────────────────────────────────────────────────────┐
│                     可观察性系统                              │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  ┌──────────────────────────────────────────────────────┐  │
│  │              数据采集层 (Data Collection)              │  │
│  │  ┌──────────────┐  ┌──────────────┐  ┌────────────┐ │  │
│  │  │ 会话监控器    │  │ 任务监控器    │  │ 指标收集器  │ │  │
│  │  │ SessionMonitor│  │ TaskMonitor  │  │ MetricsCollector│ │  │
│  │  └──────────────┘  └──────────────┘  └────────────┘ │  │
│  │  ┌──────────────┐  ┌──────────────┐  ┌────────────┐ │  │
│  │  │ 工具监控器    │  │ 模型监控器    │  │ 日志收集器  │ │  │
│  │  │ ToolMonitor  │  │ ModelMonitor │  │ LogCollector│ │  │
│  │  └──────────────┘  └──────────────┘  └────────────┘ │  │
│  └──────────────────────────────────────────────────────┘  │
│                          ↓                                   │
│  ┌──────────────────────────────────────────────────────┐  │
│  │              数据处理层 (Data Processing)             │  │
│  │  ┌──────────────┐  ┌──────────────┐  ┌────────────┐ │  │
│  │  │ 状态处理器    │  │ 指标处理器    │  │ 日志处理器  │ │  │
│  │  │ StateProcessor│  │ MetricsProcessor│ │ LogProcessor│ │  │
│  │  └──────────────┘  └──────────────┘  └────────────┘ │  │
│  │  ┌──────────────┐  ┌──────────────┐                   │  │
│  │  │ 告警处理器    │  │ 追踪处理器    │                   │  │
│  │  │ AlertProcessor│  │ TraceProcessor│                   │  │
│  │  └──────────────┘  └──────────────┘                   │  │
│  └──────────────────────────────────────────────────────┘  │
│                          ↓                                   │
│  ┌──────────────────────────────────────────────────────┐  │
│  │              数据存储层 (Data Storage)                │  │
│  │  ┌──────────────┐  ┌──────────────┐  ┌────────────┐ │  │
│  │  │ 时序数据库    │  │ 日志数据库    │  │ 追踪数据库  │ │  │
│  │  │ TimeSeriesDB │  │ LogDB        │  │ TraceDB    │ │  │
│  │  └──────────────┘  └──────────────┘  └────────────┘ │  │
│  │  ┌──────────────┐  ┌──────────────┐                   │  │
│  │  │ 状态数据库    │  │ 告警数据库    │                   │  │
│  │  │ StateDB      │  │ AlertDB      │                   │  │
│  │  └──────────────┘  └──────────────┘                   │  │
│  └──────────────────────────────────────────────────────┘  │
│                          ↓                                   │
│  ┌──────────────────────────────────────────────────────┐  │
│  │              数据服务层 (Data Service)                │  │
│  │  ┌──────────────┐  ┌──────────────┐  ┌────────────┐ │  │
│  │  │ 状态服务      │  │ 指标服务      │  │ 日志服务    │ │  │
│  │  │ StateService │  │ MetricsService│ │ LogService │ │  │
│  │  └──────────────┘  └──────────────┘  └────────────┘ │  │
│  │  ┌──────────────┐  ┌──────────────┐                   │  │
│  │  │ 告警服务      │  │ 追踪服务      │                   │  │
│  │  │ AlertService │  │ TraceService │                   │  │
│  │  └──────────────┘  └──────────────┘                   │  │
│  └──────────────────────────────────────────────────────┘  │
│                          ↓                                   │
│  ┌──────────────────────────────────────────────────────┐  │
│  │              控制层 (Control Layer)                     │  │
│  │  ┌──────────────┐  ┌──────────────┐  ┌────────────┐ │  │
│  │  │ 任务控制器    │  │ 会话控制器    │  │ 系统控制器  │ │  │
│  │  │ TaskController│  │ SessionController│ │ SystemController│ │  │
│  │  └──────────────┘  └──────────────┘  └────────────┘ │  │
│  └──────────────────────────────────────────────────────┘  │
│                          ↓                                   │
│  ┌──────────────────────────────────────────────────────┐  │
│  │              展示层 (Presentation Layer)              │  │
│  │  ┌──────────────┐  ┌──────────────┐  ┌────────────┐ │  │
│  │  │ 实时监控      │  │ 数据可视化    │  │ 告警中心    │ │  │
│  │  │ RealtimeMonitor│ │ Dashboard    │  │ AlertCenter│ │  │
│  │  └──────────────┘  └──────────────┘  └────────────┘ │  │
│  └──────────────────────────────────────────────────────┘  │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

### 2.2 核心组件

#### 2.2.1 SessionMonitor（会话监控器）

实时监控所有会话的状态和执行情况。

**核心职责：**
- 监控会话生命周期
- 追踪会话状态变化
- 监控会话资源使用
- 提供会话实时视图

**接口定义：**

```java
public interface SessionMonitor {
    
    SessionSnapshot getSessionSnapshot(String sessionId);
    
    List<SessionSnapshot> getAllSessionSnapshots();
    
    List<SessionSnapshot> getSessionsByStatus(SessionStatus status);
    
    List<SessionSnapshot> getSessionsByUser(String userId);
    
    void subscribeToSessionChanges(
        String sessionId,
        Consumer<SessionChangeEvent> handler
    );
    
    void unsubscribeFromSessionChanges(String sessionId);
    
    SessionStatistics getSessionStatistics();
}
```

#### 2.2.2 TaskMonitor（任务监控器）

实时监控所有任务的执行状态和进度。

**核心职责：**
- 监控任务执行状态
- 追踪任务进度
- 监控任务资源使用
- 提供任务实时视图

**接口定义：**

```java
public interface TaskMonitor {
    
    TaskSnapshot getTaskSnapshot(String taskId);
    
    List<TaskSnapshot> getAllTaskSnapshots();
    
    List<TaskSnapshot> getTasksByStatus(TaskStatus status);
    
    List<TaskSnapshot> getTasksBySession(String sessionId);
    
    void subscribeToTaskChanges(
        String taskId,
        Consumer<TaskChangeEvent> handler
    );
    
    void unsubscribeFromTaskChanges(String taskId);
    
    TaskStatistics getTaskStatistics();
}
```

#### 2.2.3 TaskController（任务控制器）

控制任务的执行，支持暂停、继续、中止、重试等操作。

**核心职责：**
- 暂停任务执行
- 继续任务执行
- 中止任务执行
- 重试失败任务
- 调整任务优先级

**接口定义：**

```java
public interface TaskController {
    
    boolean pauseTask(String taskId);
    
    boolean resumeTask(String taskId);
    
    boolean abortTask(String taskId);
    
    boolean retryTask(String taskId);
    
    boolean adjustTaskPriority(String taskId, int priority);
    
    TaskControlResult getTaskControlStatus(String taskId);
    
    List<TaskControlResult> getBatchTaskControlStatus(List<String> taskIds);
}
```

#### 2.2.4 SessionController（会话控制器）

控制会话的生命周期和状态。

**核心职责：**
- 关闭会话
- 暂停会话
- 恢复会话
- 清理会话

**接口定义：**

```java
public interface SessionController {
    
    boolean closeSession(String sessionId);
    
    boolean pauseSession(String sessionId);
    
    boolean resumeSession(String sessionId);
    
    boolean cleanupSession(String sessionId);
    
    SessionControlResult getSessionControlStatus(String sessionId);
}
```

#### 2.2.5 MetricsCollector（指标收集器）

收集系统的各种指标数据。

**核心职责：**
- 收集系统指标
- 收集业务指标
- 收集性能指标
- 收集自定义指标

**接口定义：**

```java
public interface MetricsCollector {
    
    void recordMetric(String name, double value, Map<String, String> tags);
    
    void incrementCounter(String name, Map<String, String> tags);
    
    void recordGauge(String name, double value, Map<String, String> tags);
    
    void recordHistogram(String name, double value, Map<String, String> tags);
    
    void recordTimer(String name, long duration, Map<String, String> tags);
    
    MetricsSnapshot getMetricsSnapshot();
    
    void startCollection();
    
    void stopCollection();
}
```

#### 2.2.6 AlertProcessor（告警处理器）

处理告警规则和告警通知。

**核心职责：**
- 配置告警规则
- 监测告警条件
- 发送告警通知
- 管理告警历史

**接口定义：**

```java
public interface AlertProcessor {
    
    String createAlertRule(AlertRule rule);
    
    void updateAlertRule(String ruleId, AlertRule rule);
    
    void deleteAlertRule(String ruleId);
    
    List<AlertRule> getAlertRules();
    
    List<Alert> getActiveAlerts();
    
    List<Alert> getAlertHistory(String userId, LocalDateTime startTime, LocalDateTime endTime);
    
    void acknowledgeAlert(String alertId);
    
    void resolveAlert(String alertId);
}
```

#### 2.2.7 TraceProcessor（追踪处理器）

处理分布式追踪数据。

**核心职责：**
- 记录追踪数据
- 关联追踪链路
- 分析追踪数据
- 提供追踪查询

**接口定义：**

```java
public interface TraceProcessor {
    
    String startTrace(String traceId, String operationName);
    
    void endTrace(String traceId);
    
    void addSpan(String traceId, Span span);
    
    Trace getTrace(String traceId);
    
    List<Trace> searchTraces(TraceQuery query);
    
    TraceStatistics getTraceStatistics();
}
```

## 3. 会话状态监控

### 3.1 会话状态定义

```java
public enum SessionStatus {
    INITIALIZING,        // 初始化中
    IDLE,               // 空闲
    PROCESSING,         // 处理中
    WAITING_FOR_TOOL,   // 等待工具调用
    WAITING_FOR_MODEL,  // 等待模型输出
    WAITING_FOR_HITL,   // 等待人工确认
    PAUSED,             // 已暂停
    CLOSING,            // 关闭中
    CLOSED,             // 已关闭
    ERROR               // 错误
}
```

### 3.2 会话快照

```java
@Data
public class SessionSnapshot {
    private String sessionId;
    private String userId;
    private String agentId;
    private SessionStatus status;
    private String currentStep;
    private Integer progress;
    private Long elapsedTime;
    private Long estimatedRemainingTime;
    private SessionResourceUsage resourceUsage;
    private List<ActiveTask> activeTasks;
    private List<ActiveToolCall> activeToolCalls;
    private List<ActiveModelCall> activeModelCalls;
    private Map<String, Object> metadata;
    private LocalDateTime lastUpdated;
}
```

### 3.3 会话资源使用

```java
@Data
public class SessionResourceUsage {
    private Integer memoryUsageMB;
    private Integer cpuUsagePercent;
    private Integer threadCount;
    private Integer activeConnections;
    private Integer tokenUsage;
    private Integer messageCount;
    private Integer toolCallCount;
}
```

### 3.4 会话监控 API

```java
@RestController
@RequestMapping("/api/monitoring/sessions")
public class SessionMonitoringController {
    
    @Autowired
    private SessionMonitor sessionMonitor;
    
    @Autowired
    private SessionController sessionController;
    
    @GetMapping
    public ResponseEntity<List<SessionSnapshot>> getAllSessions() {
        List<SessionSnapshot> sessions = sessionMonitor.getAllSessionSnapshots();
        return ResponseEntity.ok(sessions);
    }
    
    @GetMapping("/{sessionId}")
    public ResponseEntity<SessionSnapshot> getSession(
        @PathVariable String sessionId
    ) {
        SessionSnapshot snapshot = sessionMonitor.getSessionSnapshot(sessionId);
        return ResponseEntity.ok(snapshot);
    }
    
    @GetMapping("/status/{status}")
    public ResponseEntity<List<SessionSnapshot>> getSessionsByStatus(
        @PathVariable SessionStatus status
    ) {
        List<SessionSnapshot> sessions = sessionMonitor.getSessionsByStatus(status);
        return ResponseEntity.ok(sessions);
    }
    
    @GetMapping("/user/{userId}")
    public ResponseEntity<List<SessionSnapshot>> getSessionsByUser(
        @PathVariable String userId
    ) {
        List<SessionSnapshot> sessions = sessionMonitor.getSessionsByUser(userId);
        return ResponseEntity.ok(sessions);
    }
    
    @GetMapping("/statistics")
    public ResponseEntity<SessionStatistics> getStatistics() {
        SessionStatistics statistics = sessionMonitor.getSessionStatistics();
        return ResponseEntity.ok(statistics);
    }
    
    @PostMapping("/{sessionId}/close")
    public ResponseEntity<Void> closeSession(@PathVariable String sessionId) {
        sessionController.closeSession(sessionId);
        return ResponseEntity.ok().build();
    }
    
    @PostMapping("/{sessionId}/pause")
    public ResponseEntity<Void> pauseSession(@PathVariable String sessionId) {
        sessionController.pauseSession(sessionId);
        return ResponseEntity.ok().build();
    }
    
    @PostMapping("/{sessionId}/resume")
    public ResponseEntity<Void> resumeSession(@PathVariable String sessionId) {
        sessionController.resumeSession(sessionId);
        return ResponseEntity.ok().build();
    }
    
    @PostMapping("/{sessionId}/cleanup")
    public ResponseEntity<Void> cleanupSession(@PathVariable String sessionId) {
        sessionController.cleanupSession(sessionId);
        return ResponseEntity.ok().build();
    }
}
```

## 4. 任务控制机制

### 4.1 任务状态定义

```java
public enum TaskStatus {
    PENDING,            // 待执行
    RUNNING,            // 运行中
    PAUSED,             // 已暂停
    WAITING_FOR_TOOL,   // 等待工具调用
    WAITING_FOR_HITL,   // 等待人工确认
    COMPLETED,          // 已完成
    FAILED,             // 失败
    CANCELLED,          // 已取消
    TIMEOUT             // 超时
}
```

### 4.2 任务快照

```java
@Data
public class TaskSnapshot {
    private String taskId;
    private String sessionId;
    private String taskName;
    private TaskStatus status;
    private Integer progress;
    private String currentStep;
    private List<TaskStep> steps;
    private Integer currentStepIndex;
    private Long elapsedTime;
    private Long estimatedRemainingTime;
    private TaskResourceUsage resourceUsage;
    private Map<String, Object> context;
    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
}
```

### 4.3 任务控制操作

```java
@Data
public class TaskControlResult {
    private String taskId;
    private TaskControlOperation operation;
    private boolean success;
    private String message;
    private TaskStatus newStatus;
    private LocalDateTime timestamp;
}

public enum TaskControlOperation {
    PAUSE,
    RESUME,
    ABORT,
    RETRY,
    ADJUST_PRIORITY
}
```

### 4.4 任务控制 API

```java
@RestController
@RequestMapping("/api/monitoring/tasks")
public class TaskMonitoringController {
    
    @Autowired
    private TaskMonitor taskMonitor;
    
    @Autowired
    private TaskController taskController;
    
    @GetMapping
    public ResponseEntity<List<TaskSnapshot>> getAllTasks() {
        List<TaskSnapshot> tasks = taskMonitor.getAllTaskSnapshots();
        return ResponseEntity.ok(tasks);
    }
    
    @GetMapping("/{taskId}")
    public ResponseEntity<TaskSnapshot> getTask(
        @PathVariable String taskId
    ) {
        TaskSnapshot snapshot = taskMonitor.getTaskSnapshot(taskId);
        return ResponseEntity.ok(snapshot);
    }
    
    @GetMapping("/status/{status}")
    public ResponseEntity<List<TaskSnapshot>> getTasksByStatus(
        @PathVariable TaskStatus status
    ) {
        List<TaskSnapshot> tasks = taskMonitor.getTasksByStatus(status);
        return ResponseEntity.ok(tasks);
    }
    
    @GetMapping("/session/{sessionId}")
    public ResponseEntity<List<TaskSnapshot>> getTasksBySession(
        @PathVariable String sessionId
    ) {
        List<TaskSnapshot> tasks = taskMonitor.getTasksBySession(sessionId);
        return ResponseEntity.ok(tasks);
    }
    
    @GetMapping("/statistics")
    public ResponseEntity<TaskStatistics> getStatistics() {
        TaskStatistics statistics = taskMonitor.getTaskStatistics();
        return ResponseEntity.ok(statistics);
    }
    
    @PostMapping("/{taskId}/pause")
    public ResponseEntity<TaskControlResult> pauseTask(
        @PathVariable String taskId
    ) {
        boolean success = taskController.pauseTask(taskId);
        TaskControlResult result = new TaskControlResult();
        result.setTaskId(taskId);
        result.setOperation(TaskControlOperation.PAUSE);
        result.setSuccess(success);
        result.setTimestamp(LocalDateTime.now());
        return ResponseEntity.ok(result);
    }
    
    @PostMapping("/{taskId}/resume")
    public ResponseEntity<TaskControlResult> resumeTask(
        @PathVariable String taskId
    ) {
        boolean success = taskController.resumeTask(taskId);
        TaskControlResult result = new TaskControlResult();
        result.setTaskId(taskId);
        result.setOperation(TaskControlOperation.RESUME);
        result.setSuccess(success);
        result.setTimestamp(LocalDateTime.now());
        return ResponseEntity.ok(result);
    }
    
    @PostMapping("/{taskId}/abort")
    public ResponseEntity<TaskControlResult> abortTask(
        @PathVariable String taskId
    ) {
        boolean success = taskController.abortTask(taskId);
        TaskControlResult result = new TaskControlResult();
        result.setTaskId(taskId);
        result.setOperation(TaskControlOperation.ABORT);
        result.setSuccess(success);
        result.setTimestamp(LocalDateTime.now());
        return ResponseEntity.ok(result);
    }
    
    @PostMapping("/{taskId}/retry")
    public ResponseEntity<TaskControlResult> retryTask(
        @PathVariable String taskId
    ) {
        boolean success = taskController.retryTask(taskId);
        TaskControlResult result = new TaskControlResult();
        result.setTaskId(taskId);
        result.setOperation(TaskControlOperation.RETRY);
        result.setSuccess(success);
        result.setTimestamp(LocalDateTime.now());
        return ResponseEntity.ok(result);
    }
    
    @PostMapping("/{taskId}/priority")
    public ResponseEntity<TaskControlResult> adjustPriority(
        @PathVariable String taskId,
        @RequestParam int priority
    ) {
        boolean success = taskController.adjustTaskPriority(taskId, priority);
        TaskControlResult result = new TaskControlResult();
        result.setTaskId(taskId);
        result.setOperation(TaskControlOperation.ADJUST_PRIORITY);
        result.setSuccess(success);
        result.setTimestamp(LocalDateTime.now());
        return ResponseEntity.ok(result);
    }
}
```

## 5. 实时监控

### 5.1 实时数据推送

```java
@RestController
@RequestMapping("/api/monitoring/realtime")
public class RealtimeMonitoringController {
    
    @Autowired
    private SessionMonitor sessionMonitor;
    
    @Autowired
    private TaskMonitor taskMonitor;
    
    @GetMapping("/sessions")
    public SseEmitter streamSessions() {
        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);
        
        sessionMonitor.subscribeToSessionChanges("all", event -> {
            try {
                emitter.send(SseEmitter.event()
                    .name("session-change")
                    .data(event));
            } catch (IOException e) {
                emitter.completeWithError(e);
            }
        });
        
        return emitter;
    }
    
    @GetMapping("/sessions/{sessionId}")
    public SseEmitter streamSession(
        @PathVariable String sessionId
    ) {
        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);
        
        sessionMonitor.subscribeToSessionChanges(sessionId, event -> {
            try {
                emitter.send(SseEmitter.event()
                    .name("session-change")
                    .data(event));
            } catch (IOException e) {
                emitter.completeWithError(e);
            }
        });
        
        return emitter;
    }
    
    @GetMapping("/tasks")
    public SseEmitter streamTasks() {
        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);
        
        taskMonitor.subscribeToTaskChanges("all", event -> {
            try {
                emitter.send(SseEmitter.event()
                    .name("task-change")
                    .data(event));
            } catch (IOException e) {
                emitter.completeWithError(e);
            }
        });
        
        return emitter;
    }
    
    @GetMapping("/tasks/{taskId}")
    public SseEmitter streamTask(
        @PathVariable String taskId
    ) {
        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);
        
        taskMonitor.subscribeToTaskChanges(taskId, event -> {
            try {
                emitter.send(SseEmitter.event()
                    .name("task-change")
                    .data(event));
            } catch (IOException e) {
                emitter.completeWithError(e);
            }
        });
        
        return emitter;
    }
}
```

### 5.2 实时仪表板

```java
@Data
public class RealtimeDashboard {
    private SystemOverview systemOverview;
    private SessionOverview sessionOverview;
    private TaskOverview taskOverview;
    private ToolOverview toolOverview;
    private ModelOverview modelOverview;
    private List<ActiveAlert> activeAlerts;
    private LocalDateTime timestamp;
}

@Data
public class SystemOverview {
    private Integer totalSessions;
    private Integer activeSessions;
    private Integer totalTasks;
    private Integer runningTasks;
    private Integer pausedTasks;
    private Double cpuUsage;
    private Long memoryUsage;
    private Integer activeConnections;
}

@Data
public class SessionOverview {
    private Map<SessionStatus, Integer> statusDistribution;
    private List<SessionSnapshot> recentSessions;
    private List<SessionSnapshot> longestRunningSessions;
}

@Data
public class TaskOverview {
    private Map<TaskStatus, Integer> statusDistribution;
    private List<TaskSnapshot> recentTasks;
    private List<TaskSnapshot> longestRunningTasks;
}
```

## 6. 指标收集和展示

### 6.1 系统指标

```java
@Data
public class SystemMetrics {
    private CpuMetrics cpu;
    private MemoryMetrics memory;
    private NetworkMetrics network;
    private DiskMetrics disk;
    private LocalDateTime timestamp;
}

@Data
public class CpuMetrics {
    private Double usagePercent;
    private Integer coreCount;
    private Double loadAverage1m;
    private Double loadAverage5m;
    private Double loadAverage15m;
}

@Data
public class MemoryMetrics {
    private Long totalBytes;
    private Long usedBytes;
    private Long freeBytes;
    private Long cachedBytes;
    private Double usagePercent;
}

@Data
public class NetworkMetrics {
    private Long bytesReceived;
    private Long bytesSent;
    private Long packetsReceived;
    private Long packetsSent;
    private Double receiveRate;
    private Double sendRate;
}

@Data
public class DiskMetrics {
    private Long totalBytes;
    private Long usedBytes;
    private Long freeBytes;
    private Double usagePercent;
    private Double readRate;
    private Double writeRate;
}
```

### 6.2 业务指标

```java
@Data
public class BusinessMetrics {
    private RequestMetrics request;
    private SessionMetrics session;
    private TaskMetrics task;
    private ToolMetrics tool;
    private ModelMetrics model;
    private LocalDateTime timestamp;
}

@Data
public class RequestMetrics {
    private Long totalRequests;
    private Long successfulRequests;
    private Long failedRequests;
    private Double successRate;
    private Double averageResponseTime;
    private Double p50ResponseTime;
    private Double p95ResponseTime;
    private Double p99ResponseTime;
}

@Data
public class SessionMetrics {
    private Long totalSessions;
    private Long activeSessions;
    private Long closedSessions;
    private Double averageSessionDuration;
    private Long totalMessages;
    private Long totalTokens;
}

@Data
public class TaskMetrics {
    private Long totalTasks;
    private Long completedTasks;
    private Long failedTasks;
    private Long cancelledTasks;
    private Double completionRate;
    private Double averageTaskDuration;
}
```

### 6.3 指标查询 API

```java
@RestController
@RequestMapping("/api/monitoring/metrics")
public class MetricsController {
    
    @Autowired
    private MetricsCollector metricsCollector;
    
    @GetMapping("/system")
    public ResponseEntity<SystemMetrics> getSystemMetrics() {
        SystemMetrics metrics = metricsCollector.getSystemMetrics();
        return ResponseEntity.ok(metrics);
    }
    
    @GetMapping("/business")
    public ResponseEntity<BusinessMetrics> getBusinessMetrics() {
        BusinessMetrics metrics = metricsCollector.getBusinessMetrics();
        return ResponseEntity.ok(metrics);
    }
    
    @GetMapping("/custom")
    public ResponseEntity<Map<String, Object>> getCustomMetrics(
        @RequestParam List<String> metricNames,
        @RequestParam(required = false) String startTime,
        @RequestParam(required = false) String endTime
    ) {
        Map<String, Object> metrics = metricsCollector.getCustomMetrics(
            metricNames, startTime, endTime
        );
        return ResponseEntity.ok(metrics);
    }
    
    @GetMapping("/history")
    public ResponseEntity<List<MetricDataPoint>> getMetricHistory(
        @RequestParam String metricName,
        @RequestParam String startTime,
        @RequestParam String endTime,
        @RequestParam(defaultValue = "60") int intervalSeconds
    ) {
        List<MetricDataPoint> history = metricsCollector.getMetricHistory(
            metricName, startTime, endTime, intervalSeconds
        );
        return ResponseEntity.ok(history);
    }
}
```

## 7. 日志和追踪

### 7.1 日志记录

```java
@Data
public class LogEntry {
    private String logId;
    private String requestId;
    private String sessionId;
    private String taskId;
    private LogLevel level;
    private String message;
    private String logger;
    private String thread;
    private Map<String, Object> context;
    private LocalDateTime timestamp;
}

public enum LogLevel {
    TRACE,
    DEBUG,
    INFO,
    WARN,
    ERROR,
    FATAL
}
```

### 7.2 分布式追踪

```java
@Data
public class Trace {
    private String traceId;
    private String operationName;
    private Long startTime;
    private Long duration;
    private List<Span> spans;
    private Map<String, String> tags;
}

@Data
public class Span {
    private String spanId;
    private String parentSpanId;
    private String operationName;
    private Long startTime;
    private Long duration;
    private Map<String, String> tags;
    private List<LogEvent> logs;
}
```

### 7.3 日志查询 API

```java
@RestController
@RequestMapping("/api/monitoring/logs")
public class LogController {
    
    @Autowired
    private LogService logService;
    
    @GetMapping("/search")
    public ResponseEntity<List<LogEntry>> searchLogs(
        @RequestParam(required = false) String requestId,
        @RequestParam(required = false) String sessionId,
        @RequestParam(required = false) String taskId,
        @RequestParam(required = false) LogLevel level,
        @RequestParam(required = false) String startTime,
        @RequestParam(required = false) String endTime,
        @RequestParam(required = false) String keyword,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "100") int size
    ) {
        List<LogEntry> logs = logService.searchLogs(
            requestId, sessionId, taskId, level,
            startTime, endTime, keyword, page, size
        );
        return ResponseEntity.ok(logs);
    }
    
    @GetMapping("/{logId}")
    public ResponseEntity<LogEntry> getLog(@PathVariable String logId) {
        LogEntry log = logService.getLog(logId);
        return ResponseEntity.ok(log);
    }
    
    @GetMapping("/trace/{traceId}")
    public ResponseEntity<Trace> getTrace(@PathVariable String traceId) {
        Trace trace = logService.getTrace(traceId);
        return ResponseEntity.ok(trace);
    }
}
```

## 8. 告警机制

### 8.1 告警规则

```java
@Data
public class AlertRule {
    private String ruleId;
    private String ruleName;
    private String description;
    private AlertCondition condition;
    private AlertSeverity severity;
    private List<AlertNotification> notifications;
    private Boolean enabled;
    private Integer cooldownMinutes;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

@Data
public class AlertCondition {
    private String metricName;
    private AlertOperator operator;
    private Double threshold;
    private Integer durationMinutes;
    private Map<String, String> tags;
}

public enum AlertOperator {
    GREATER_THAN,
    LESS_THAN,
    EQUAL_TO,
    NOT_EQUAL_TO,
    GREATER_THAN_OR_EQUAL,
    LESS_THAN_OR_EQUAL
}

public enum AlertSeverity {
    INFO,
    WARNING,
    ERROR,
    CRITICAL
}
```

### 8.2 告警记录

```java
@Data
public class Alert {
    private String alertId;
    private String ruleId;
    private String ruleName;
    private AlertSeverity severity;
    private String message;
    private Map<String, Object> details;
    private AlertStatus status;
    private LocalDateTime triggeredAt;
    private LocalDateTime acknowledgedAt;
    private LocalDateTime resolvedAt;
    private String acknowledgedBy;
    private String resolvedBy;
    private String notes;
}

public enum AlertStatus {
    ACTIVE,
    ACKNOWLEDGED,
    RESOLVED
}
```

### 8.3 告警 API

```java
@RestController
@RequestMapping("/api/monitoring/alerts")
public class AlertController {
    
    @Autowired
    private AlertProcessor alertProcessor;
    
    @GetMapping
    public ResponseEntity<List<Alert>> getAlerts(
        @RequestParam(required = false) AlertStatus status,
        @RequestParam(required = false) AlertSeverity severity
    ) {
        List<Alert> alerts = alertProcessor.getAlerts(status, severity);
        return ResponseEntity.ok(alerts);
    }
    
    @GetMapping("/active")
    public ResponseEntity<List<Alert>> getActiveAlerts() {
        List<Alert> alerts = alertProcessor.getActiveAlerts();
        return ResponseEntity.ok(alerts);
    }
    
    @GetMapping("/history")
    public ResponseEntity<List<Alert>> getAlertHistory(
        @RequestParam String startTime,
        @RequestParam String endTime
    ) {
        List<Alert> alerts = alertProcessor.getAlertHistory(startTime, endTime);
        return ResponseEntity.ok(alerts);
    }
    
    @PostMapping("/{alertId}/acknowledge")
    public ResponseEntity<Void> acknowledgeAlert(
        @PathVariable String alertId,
        @RequestParam String userId,
        @RequestParam(required = false) String notes
    ) {
        alertProcessor.acknowledgeAlert(alertId, userId, notes);
        return ResponseEntity.ok().build();
    }
    
    @PostMapping("/{alertId}/resolve")
    public ResponseEntity<Void> resolveAlert(
        @PathVariable String alertId,
        @RequestParam String userId,
        @RequestParam(required = false) String notes
    ) {
        alertProcessor.resolveAlert(alertId, userId, notes);
        return ResponseEntity.ok().build();
    }
    
    @PostMapping("/rules")
    public ResponseEntity<String> createAlertRule(
        @RequestBody AlertRule rule
    ) {
        String ruleId = alertProcessor.createAlertRule(rule);
        return ResponseEntity.ok(ruleId);
    }
    
    @PutMapping("/rules/{ruleId}")
    public ResponseEntity<Void> updateAlertRule(
        @PathVariable String ruleId,
        @RequestBody AlertRule rule
    ) {
        alertProcessor.updateAlertRule(ruleId, rule);
        return ResponseEntity.ok().build();
    }
    
    @DeleteMapping("/rules/{ruleId}")
    public ResponseEntity<Void> deleteAlertRule(@PathVariable String ruleId) {
        alertProcessor.deleteAlertRule(ruleId);
        return ResponseEntity.ok().build();
    }
}
```

## 9. 数据可视化

### 9.1 仪表板配置

```java
@Data
public class Dashboard {
    private String dashboardId;
    private String dashboardName;
    private String description;
    private List<Panel> panels;
    private DashboardConfig config;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

@Data
public class Panel {
    private String panelId;
    private String panelName;
    private PanelType type;
    private PanelConfig config;
    private Integer row;
    private Integer col;
    private Integer width;
    private Integer height;
}

public enum PanelType {
    METRIC_CHART,
    TIME_SERIES,
    GAUGE,
    TABLE,
    STAT,
    LOG_VIEW,
    TRACE_VIEW,
    CUSTOM
}
```

### 9.2 仪表板 API

```java
@RestController
@RequestMapping("/api/monitoring/dashboards")
public class DashboardController {
    
    @Autowired
    private DashboardService dashboardService;
    
    @GetMapping
    public ResponseEntity<List<Dashboard>> getDashboards() {
        List<Dashboard> dashboards = dashboardService.getDashboards();
        return ResponseEntity.ok(dashboards);
    }
    
    @GetMapping("/{dashboardId}")
    public ResponseEntity<Dashboard> getDashboard(
        @PathVariable String dashboardId
    ) {
        Dashboard dashboard = dashboardService.getDashboard(dashboardId);
        return ResponseEntity.ok(dashboard);
    }
    
    @PostMapping
    public ResponseEntity<String> createDashboard(
        @RequestBody Dashboard dashboard
    ) {
        String dashboardId = dashboardService.createDashboard(dashboard);
        return ResponseEntity.ok(dashboardId);
    }
    
    @PutMapping("/{dashboardId}")
    public ResponseEntity<Void> updateDashboard(
        @PathVariable String dashboardId,
        @RequestBody Dashboard dashboard
    ) {
        dashboardService.updateDashboard(dashboardId, dashboard);
        return ResponseEntity.ok().build();
    }
    
    @DeleteMapping("/{dashboardId}")
    public ResponseEntity<Void> deleteDashboard(
        @PathVariable String dashboardId
    ) {
        dashboardService.deleteDashboard(dashboardId);
        return ResponseEntity.ok().build();
    }
}
```

## 10. 数据库设计

### 10.1 会话状态表

```sql
CREATE TABLE session_states (
    id VARCHAR(100) PRIMARY KEY,
    session_id VARCHAR(100) NOT NULL,
    status ENUM('INITIALIZING', 'IDLE', 'PROCESSING', 'WAITING_FOR_TOOL', 
                'WAITING_FOR_MODEL', 'WAITING_FOR_HITL', 'PAUSED', 
                'CLOSING', 'CLOSED', 'ERROR') NOT NULL,
    current_step VARCHAR(255),
    progress INT DEFAULT 0,
    elapsed_time BIGINT,
    estimated_remaining_time BIGINT,
    resource_usage JSON,
    metadata JSON,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    
    INDEX idx_session_id (session_id),
    INDEX idx_status (status),
    INDEX idx_updated_at (updated_at),
    FOREIGN KEY (session_id) REFERENCES sessions(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

### 10.2 任务状态表

```sql
CREATE TABLE task_states (
    id VARCHAR(100) PRIMARY KEY,
    task_id VARCHAR(100) NOT NULL,
    session_id VARCHAR(100) NOT NULL,
    status ENUM('PENDING', 'RUNNING', 'PAUSED', 'WAITING_FOR_TOOL', 
                'WAITING_FOR_HITL', 'COMPLETED', 'FAILED', 'CANCELLED', 
                'TIMEOUT') NOT NULL,
    current_step VARCHAR(255),
    progress INT DEFAULT 0,
    elapsed_time BIGINT,
    estimated_remaining_time BIGINT,
    resource_usage JSON,
    context JSON,
    error_message TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at TIMESTAMP NULL,
    completed_at TIMESTAMP NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    
    INDEX idx_task_id (task_id),
    INDEX idx_session_id (session_id),
    INDEX idx_status (status),
    INDEX idx_updated_at (updated_at),
    FOREIGN KEY (task_id) REFERENCES tasks(id) ON DELETE CASCADE,
    FOREIGN KEY (session_id) REFERENCES sessions(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

### 10.3 指标数据表

```sql
CREATE TABLE metrics (
    id VARCHAR(100) PRIMARY KEY,
    metric_name VARCHAR(255) NOT NULL,
    metric_value DOUBLE NOT NULL,
    tags JSON,
    timestamp TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    
    INDEX idx_metric_name (metric_name),
    INDEX idx_timestamp (timestamp),
    INDEX idx_tags ((CAST(tags AS CHAR(255) ARRAY)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

### 10.4 告警规则表

```sql
CREATE TABLE alert_rules (
    id VARCHAR(100) PRIMARY KEY,
    rule_name VARCHAR(255) NOT NULL,
    description TEXT,
    condition JSON NOT NULL,
    severity ENUM('INFO', 'WARNING', 'ERROR', 'CRITICAL') NOT NULL,
    notifications JSON,
    enabled BOOLEAN DEFAULT TRUE,
    cooldown_minutes INT DEFAULT 5,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    
    INDEX idx_rule_name (rule_name),
    INDEX idx_enabled (enabled),
    INDEX idx_severity (severity)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

### 10.5 告警记录表

```sql
CREATE TABLE alerts (
    id VARCHAR(100) PRIMARY KEY,
    rule_id VARCHAR(100) NOT NULL,
    rule_name VARCHAR(255) NOT NULL,
    severity ENUM('INFO', 'WARNING', 'ERROR', 'CRITICAL') NOT NULL,
    message TEXT NOT NULL,
    details JSON,
    status ENUM('ACTIVE', 'ACKNOWLEDGED', 'RESOLVED') NOT NULL,
    triggered_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    acknowledged_at TIMESTAMP NULL,
    resolved_at TIMESTAMP NULL,
    acknowledged_by VARCHAR(100),
    resolved_by VARCHAR(100),
    notes TEXT,
    
    INDEX idx_rule_id (rule_id),
    INDEX idx_status (status),
    INDEX idx_severity (severity),
    INDEX idx_triggered_at (triggered_at),
    FOREIGN KEY (rule_id) REFERENCES alert_rules(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

## 11. 配置管理

### 11.1 可观察性配置

```java
@Data
@ConfigurationProperties(prefix = "observability")
public class ObservabilityConfig {
    
    private SessionMonitorProperties sessionMonitor;
    private TaskMonitorProperties taskMonitor;
    private MetricsCollectorProperties metricsCollector;
    private AlertProcessorProperties alertProcessor;
    private TraceProcessorProperties traceProcessor;
    
    @Data
    public static class SessionMonitorProperties {
        private Boolean enabled = true;
        private Integer snapshotInterval = 5;
        private Integer historyRetentionDays = 30;
    }
    
    @Data
    public static class TaskMonitorProperties {
        private Boolean enabled = true;
        private Integer snapshotInterval = 1;
        private Integer historyRetentionDays = 30;
    }
    
    @Data
    public static class MetricsCollectorProperties {
        private Boolean enabled = true;
        private Integer collectionInterval = 10;
        private Integer retentionDays = 90;
    }
    
    @Data
    public static class AlertProcessorProperties {
        private Boolean enabled = true;
        private Integer checkInterval = 30;
        private Integer retentionDays = 90;
    }
    
    @Data
    public static class TraceProcessorProperties {
        private Boolean enabled = true;
        private Integer samplingRate = 10;
        private Integer retentionDays = 30;
    }
}
```

## 12. 最佳实践

### 12.1 监控策略
- 实时监控关键指标
- 设置合理的告警阈值
- 定期审查告警规则
- 优化监控频率

### 12.2 日志管理
- 使用结构化日志
- 合理设置日志级别
- 定期清理历史日志
- 实现日志查询优化

### 12.3 告警管理
- 避免告警风暴
- 设置告警冷却期
- 实现告警升级机制
- 记录告警处理过程

### 12.4 性能优化
- 使用异步数据采集
- 实现数据聚合
- 优化数据库查询
- 使用缓存减少查询

### 12.5 安全性
- 实现访问控制
- 敏感数据脱敏
- 审计日志记录
- 数据加密传输

## 13. 实施计划

### Phase 1: 基础架构（2周）
- 设计数据模型
- 创建数据库表
- 实现核心接口
- 实现配置管理

### Phase 2: 监控组件（3周）
- 实现 SessionMonitor
- 实现 TaskMonitor
- 实现 MetricsCollector
- 实现 LogCollector

### Phase 3: 控制组件（2周）
- 实现 TaskController
- 实现 SessionController
- 实现任务控制逻辑
- 实现会话控制逻辑

### Phase 4: 告警系统（2周）
- 实现 AlertProcessor
- 实现告警规则引擎
- 实现告警通知
- 实现告警管理

### Phase 5: 追踪系统（1周）
- 实现 TraceProcessor
- 实现分布式追踪
- 实现追踪查询

### Phase 6: 可视化和 API（2周）
- 实现实时监控 API
- 实现数据可视化
- 实现仪表板
- 编写文档

### Phase 7: 测试和优化（1周）
- 编写单元测试
- 编写集成测试
- 性能测试和优化
- 部署到生产环境

## 14. 扩展方向

### 14.1 智能告警
- 基于机器学习的异常检测
- 自适应告警阈值
- 告警预测和预防

### 14.2 高级可视化
- 3D 可视化
- 交互式图表
- 自定义可视化组件

### 14.3 多租户支持
- 租户级别的监控
- 租户级别的告警
- 租户级别的配额

### 14.4 集成第三方工具
- Prometheus 集成
- Grafana 集成
- ELK Stack 集成
- Jaeger 集成

### 14.5 AI 辅助分析
- 自动化问题诊断
- 性能瓶颈分析
- 容量规划建议

### 14.6 移动端支持
- 移动端监控应用
- 推送通知
- 移动端告警处理

## 15. 总结

可观察性系统是 Foggy Navigator 系统的重要组成部分，提供了全面的监控、追踪和控制能力。通过实时监控会话和任务状态、支持任务的暂停/继续/中止等操作、收集和展示系统指标、提供日志追踪和告警机制，可观察性系统确保了系统的可观测性和可控性。

本文档详细描述了可观察性系统的设计目标、核心职责、系统架构、会话状态监控、任务控制机制、实时监控、指标收集和展示、日志和追踪、告警机制、数据可视化、数据库设计、配置管理、最佳实践、实施计划和扩展方向，为系统的开发和实施提供了完整的指导。
