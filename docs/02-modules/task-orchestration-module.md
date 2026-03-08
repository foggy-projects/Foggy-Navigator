# 任务编排模块设计文档

> **实施阶段**: Phase 3+ (后续扩展)
> **当前实现**: Phase 1 使用简化版，参见 [Agent框架设计](../agent-framework-requirements.md)
> **本文档作用**: 系统终态参考，指导后续迭代

---

## 1. 概述

### 1.1 模块定位
任务编排模块是动态 Agent 编排系统的核心组件，负责将复杂的用户请求分解为可执行的任务步骤，协调任务的执行顺序，处理任务执行过程中的动态调整和异常情况。

### 1.2 设计目标
- **灵活性**: 支持动态任务调整和重规划
- **可靠性**: 保证任务执行的可靠性和一致性
- **可扩展性**: 支持多种任务类型和执行模式
- **可观测性**: 提供完整的任务执行追踪和监控
- **高性能**: 支持并行执行和资源优化

### 1.3 核心特性
- 任务分解与规划
- 串行/并行执行
- 动态任务调整（任务栈、子任务、重规划）
- 任务状态管理
- 任务恢复与断点续传
- 任务依赖管理
- 错误处理与重试

## 2. 核心概念

### 2.1 任务（Task）
任务是完成特定目标的一个或多个步骤的集合。

### 2.2 任务步骤（TaskStep）
任务是可执行的最小单元，代表一个具体的操作。

### 2.3 任务计划（TaskPlan）
任务计划是任务的完整描述，包括所有步骤、依赖关系和执行策略。

### 2.4 任务上下文（TaskContext）
任务上下文包含任务执行过程中的状态、变量和元数据。

### 2.5 任务栈（TaskStack）
任务栈用于管理嵌套任务，支持任务的保存和恢复。

## 3. 数据模型设计

### 3.1 任务计划（TaskPlan）

```java
@Data
public class TaskPlan {
    private String id;
    private String name;
    private String description;
    private String userId;
    private String sessionId;
    private List<TaskStep> steps;
    private ExecutionMode executionMode;
    private boolean continueOnError;
    private TaskPriority priority;
    private Map<String, Object> parameters;
    private TaskStatus status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

public enum ExecutionMode {
    SEQUENTIAL,    // 串行执行
    PARALLEL,      // 并行执行
    MIXED          // 混合执行
}

public enum TaskPriority {
    LOW,
    MEDIUM,
    HIGH,
    URGENT
}

public enum TaskStatus {
    PENDING,
    RUNNING,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELLED
}
```

### 3.2 任务步骤（TaskStep）

```java
@Data
public class TaskStep {
    private String id;
    private String name;
    private String description;
    private StepType type;
    private List<String> dependencies;
    private Map<String, Object> parameters;
    private List<ToolSpecification> tools;
    private String prompt;
    private String toolName;
    private StepCondition condition;
    private int retryCount;
    private int maxRetries;
    private long timeoutMs;
}

public enum StepType {
    LLM_CALL,      // LLM 调用
    TOOL_CALL,     // 工具调用
    CONDITIONAL,   // 条件分支
    LOOP,         // 循环
    PARALLEL,     // 并行执行
    SUBTASK       // 子任务
}

public class StepCondition {
    private String expression;
    private Map<String, Object> variables;
    private ConditionOperator operator;
}

public enum ConditionOperator {
    EQUALS,
    NOT_EQUALS,
    GREATER_THAN,
    LESS_THAN,
    CONTAINS,
    NOT_CONTAINS
}
```

### 3.3 任务上下文（TaskContext）

```java
@Data
public class TaskContext {
    private String taskId;
    private TaskPlan plan;
    private int currentStepIndex;
    private Map<String, Object> variables;
    private Map<String, Object> outputs;
    private TaskStatus status;
    private LocalDateTime pausedAt;
    private String pauseReason;
    private String resumeFromTaskId;
    private String parentTaskId;
    private List<String> childTaskIds;
    private Map<String, Object> metadata;
}
```

### 3.4 任务执行结果（TaskExecutionResult）

```java
@Data
public class TaskExecutionResult {
    private String taskId;
    private boolean success;
    private String error;
    private Map<String, StepExecutionResult> stepResults;
    private Map<String, Object> outputs;
    private long durationMs;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private List<String> interruptions;
    private List<String> replans;
}

@Data
public class StepExecutionResult {
    private String stepId;
    private boolean success;
    private String error;
    private Map<String, Object> outputs;
    private long durationMs;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private boolean requiresInterruption;
    private InterruptionType interruptionType;
    private TaskPlan interruptionTask;
    private boolean requiresReplanning;
    private String replanReason;
    private List<Message> messages;
}

public enum InterruptionType {
    SUBTASK,      // 子任务
    NEW_TASK,     // 新任务
    REPLAN        // 重规划
}
```

### 3.5 任务栈（TaskStack）

```java
public class TaskStack {
    private final Deque<TaskContext> stack = new ArrayDeque<>();
    
    public void push(TaskContext context) {
        stack.push(context);
    }
    
    public TaskContext pop() {
        return stack.pop();
    }
    
    public TaskContext peek() {
        return stack.peek();
    }
    
    public boolean isEmpty() {
        return stack.isEmpty();
    }
    
    public int size() {
        return stack.size();
    }
    
    public void clear() {
        stack.clear();
    }
}
```

## 4. 动态任务调整机制

### 4.1 任务栈模式

任务栈模式用于处理嵌套任务，类似函数调用栈。

```java
@Service
public class TaskStackManager {
    private final Map<String, TaskStack> taskStacks = new ConcurrentHashMap<>();
    
    public void pushContext(String taskId, TaskContext context) {
        TaskStack stack = taskStacks.computeIfAbsent(taskId, k -> new TaskStack());
        stack.push(context);
    }
    
    public TaskContext popContext(String taskId) {
        TaskStack stack = taskStacks.get(taskId);
        if (stack == null || stack.isEmpty()) {
            throw new IllegalStateException("Task stack is empty for task: " + taskId);
        }
        return stack.pop();
    }
    
    public TaskContext peekContext(String taskId) {
        TaskStack stack = taskStacks.get(taskId);
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        return stack.peek();
    }
    
    public boolean isEmpty(String taskId) {
        TaskStack stack = taskStacks.get(taskId);
        return stack == null || stack.isEmpty();
    }
}
```

### 4.2 子任务模式

子任务模式将新任务作为当前任务的子任务，形成树形结构。

```java
@Service
public class SubTaskManager {
    
    public TaskPlan createSubTask(TaskStep parentStep, TaskContext parentContext) {
        TaskPlan subTask = new TaskPlan();
        subTask.setId(generateTaskId());
        subTask.setParentId(parentContext.getTaskId());
        subTask.setName("Subtask for: " + parentStep.getName());
        subTask.setDescription(parentStep.getDescription());
        subTask.setExecutionMode(ExecutionMode.SEQUENTIAL);
        
        // 继承父任务的变量
        subTask.setParameters(new HashMap<>(parentContext.getVariables()));
        
        return subTask;
    }
    
    public void linkSubTask(TaskContext parentContext, TaskContext subContext) {
        if (parentContext.getChildTaskIds() == null) {
            parentContext.setChildTaskIds(new ArrayList<>());
        }
        parentContext.getChildTaskIds().add(subContext.getTaskId());
        subContext.setParentId(parentContext.getTaskId());
    }
    
    public List<TaskContext> getChildTasks(String parentTaskId) {
        return taskRepository.findByParentTaskId(parentTaskId);
    }
}
```

### 4.3 动态重规划模式

动态重规划模式让AI根据执行情况重新规划任务流程。

```java
@Service
public class TaskReplanner {
    
    private final ChatLanguageModel llm;
    
    public TaskPlan replan(TaskPlan originalPlan, int currentIndex, 
                          StepExecutionResult failure, Map<String, Object> context) {
        String prompt = buildReplanPrompt(originalPlan, currentIndex, failure, context);
        String response = llm.generate(prompt);
        return parseTaskPlan(response);
    }
    
    private String buildReplanPrompt(TaskPlan plan, int currentIndex, 
                                   StepExecutionResult failure, Map<String, Object> context) {
        return String.format("""
            任务执行过程中需要重新规划。
            
            ## 原始任务
            名称：%s
            描述：%s
            
            ## 当前执行情况
            当前步骤：%s（第 %d 步）
            步骤描述：%s
            执行结果：%s
            错误信息：%s
            
            ## 当前上下文
            %s
            
            ## 已完成的步骤
            %s
            
            请重新规划任务流程，返回JSON格式，包含：
            - steps: 重新规划的步骤列表
            - executionMode: 执行模式
            - continueOnError: 是否在错误时继续
            """,
            plan.getName(),
            plan.getDescription(),
            plan.getSteps().get(currentIndex).getName(),
            currentIndex + 1,
            plan.getSteps().get(currentIndex).getDescription(),
            failure.isSuccess() ? "成功" : "失败",
            failure.getError(),
            JsonUtil.toJson(context),
            formatCompletedSteps(plan, currentIndex)
        );
    }
    
    private String formatCompletedSteps(TaskPlan plan, int currentIndex) {
        return IntStream.range(0, currentIndex)
            .mapToObj(i -> String.format("- 步骤 %d: %s", i + 1, plan.getSteps().get(i).getName()))
            .collect(Collectors.joining("\n"));
    }
}
```

### 4.4 任务暂停与恢复

```java
@Service
public class TaskPauseResumeManager {
    
    public void pauseTask(TaskContext context, String reason) {
        context.setStatus(TaskStatus.PAUSED);
        context.setPausedAt(LocalDateTime.now());
        context.setPauseReason(reason);
        taskRepository.save(context);
    }
    
    public void resumeTask(String taskId, String resumeFromTaskId) {
        TaskContext context = taskRepository.findById(taskId);
        if (context == null) {
            throw new IllegalArgumentException("Task not found: " + taskId);
        }
        
        context.setStatus(TaskStatus.RUNNING);
        context.setResumeFromTaskId(resumeFromTaskId);
        taskRepository.save(context);
        
        orchestrator.executeWithContext(context);
    }
    
    public List<TaskContext> getPausedTasks(String userId) {
        return taskRepository.findByUserIdAndStatus(userId, TaskStatus.PAUSED);
    }
}
```

## 5. 任务执行引擎

### 5.1 任务编排器接口

```java
public interface TaskOrchestrator {
    TaskPlan decompose(String userInput, Long sessionId);
    TaskExecutionResult execute(TaskPlan plan);
    TaskExecutionResult executeWithContext(TaskContext context);
    TaskStatus getStatus(String taskId);
    void pause(String taskId, String reason);
    void resume(String taskId, String resumeFromTaskId);
    void cancel(String taskId);
    TaskExecutionResult getResult(String taskId);
}
```

### 5.2 混合任务编排器实现

```java
@Service
public class HybridTaskOrchestrator implements TaskOrchestrator {
    
    private final ChatLanguageModel llm;
    private final ToolExecutor toolExecutor;
    private final TaskRepository taskRepository;
    private final TaskStackManager stackManager;
    private final SubTaskManager subTaskManager;
    private final TaskReplanner replanner;
    private final TaskPauseResumeManager pauseResumeManager;
    private final ExecutorService executorService;
    
    @Override
    public TaskPlan decompose(String userInput, Long sessionId) {
        String prompt = buildDecompositionPrompt(userInput);
        String response = llm.generate(prompt);
        TaskPlan plan = parseTaskPlan(response);
        
        plan.setSessionId(sessionId.toString());
        plan.setStatus(TaskStatus.PENDING);
        plan.setCreatedAt(LocalDateTime.now());
        
        taskRepository.save(plan);
        return plan;
    }
    
    private String buildDecompositionPrompt(String userInput) {
        return String.format("""
            请将以下用户请求分解为可执行的任务步骤。
            
            用户请求：%s
            
            请返回JSON格式，包含：
            - name: 任务名称
            - description: 任务描述
            - steps: 步骤列表，每个步骤包含：
              - id: 步骤ID
              - name: 步骤名称
              - description: 步骤描述
              - type: 步骤类型（LLM_CALL, TOOL_CALL, CONDITIONAL, LOOP, PARALLEL, SUBTASK）
              - dependencies: 依赖的步骤ID列表
              - parameters: 步骤参数
              - tools: 需要的工具列表（如果是TOOL_CALL）
              - prompt: LLM提示词（如果是LLM_CALL）
              - toolName: 工具名称（如果是TOOL_CALL）
              - condition: 条件表达式（如果是CONDITIONAL）
              - retryCount: 重试次数
              - maxRetries: 最大重试次数
              - timeoutMs: 超时时间（毫秒）
            - executionMode: 执行模式（SEQUENTIAL, PARALLEL, MIXED）
            - continueOnError: 是否在错误时继续
            - priority: 任务优先级（LOW, MEDIUM, HIGH, URGENT）
            """, userInput);
    }
    
    @Override
    public TaskExecutionResult execute(TaskPlan plan) {
        TaskContext context = new TaskContext();
        context.setTaskId(plan.getId());
        context.setPlan(plan);
        context.setCurrentStepIndex(0);
        context.setVariables(new HashMap<>(plan.getParameters()));
        context.setOutputs(new HashMap<>());
        context.setStatus(TaskStatus.RUNNING);
        
        return executeWithContext(context);
    }
    
    @Override
    public TaskExecutionResult executeWithContext(TaskContext context) {
        TaskPlan plan = context.getPlan();
        TaskExecutionResult result = new TaskExecutionResult();
        result.setTaskId(context.getTaskId());
        result.setStartedAt(LocalDateTime.now());
        
        // 更新任务状态
        context.setStatus(TaskStatus.RUNNING);
        taskRepository.save(context);
        
        try {
            switch (plan.getExecutionMode()) {
                case SEQUENTIAL:
                    result = executeSequential(context);
                    break;
                case PARALLEL:
                    result = executeParallel(context);
                    break;
                case MIXED:
                    result = executeMixed(context);
                    break;
            }
            
            context.setStatus(TaskStatus.COMPLETED);
            result.setSuccess(true);
            
        } catch (Exception e) {
            context.setStatus(TaskStatus.FAILED);
            result.setSuccess(false);
            result.setError(e.getMessage());
        }
        
        result.setCompletedAt(LocalDateTime.now());
        result.setDurationMs(Duration.between(result.getStartedAt(), result.getCompletedAt()).toMillis());
        
        taskRepository.save(context);
        return result;
    }
    
    private TaskExecutionResult executeSequential(TaskContext context) {
        TaskPlan plan = context.getPlan();
        TaskExecutionResult result = new TaskExecutionResult();
        
        for (int i = context.getCurrentStepIndex(); i < plan.getSteps().size(); i++) {
            TaskStep step = plan.getSteps().get(i);
            context.setCurrentStepIndex(i);
            
            // 保存执行状态
            saveExecutionState(context);
            
            // 检查依赖
            if (!checkDependencies(step, result.getStepResults())) {
                continue;
            }
            
            // 执行步骤
            StepExecutionResult stepResult = executeStep(step, context);
            
            // 处理中断
            if (stepResult.requiresInterruption()) {
                return handleInterruption(context, stepResult);
            }
            
            // 处理重规划
            if (stepResult.requiresReplanning()) {
                return handleReplanning(context, stepResult);
            }
            
            // 更新上下文
            context.getVariables().putAll(stepResult.getOutputs());
            result.addStepResult(step.getId(), stepResult);
            
            // 如果失败且不继续执行
            if (!stepResult.isSuccess() && !plan.isContinueOnError()) {
                result.setSuccess(false);
                result.setError("Step " + step.getId() + " failed: " + stepResult.getError());
                break;
            }
        }
        
        return result;
    }
    
    private TaskExecutionResult executeParallel(TaskContext context) {
        TaskPlan plan = context.getPlan();
        TaskExecutionResult result = new TaskExecutionResult();
        
        // 并行执行所有步骤
        List<Future<StepExecutionResult>> futures = plan.getSteps().stream()
            .map(step -> executorService.submit(() -> executeStep(step, context)))
            .collect(Collectors.toList());
        
        // 收集结果
        Map<String, StepExecutionResult> stepResults = new HashMap<>();
        for (int i = 0; i < plan.getSteps().size(); i++) {
            try {
                StepExecutionResult stepResult = futures.get(i).get();
                stepResults.put(plan.getSteps().get(i).getId(), stepResult);
                context.getVariables().putAll(stepResult.getOutputs());
            } catch (Exception e) {
                StepExecutionResult errorResult = new StepExecutionResult();
                errorResult.setSuccess(false);
                errorResult.setError(e.getMessage());
                stepResults.put(plan.getSteps().get(i).getId(), errorResult);
            }
        }
        
        result.setStepResults(stepResults);
        result.setSuccess(true);
        return result;
    }
    
    private TaskExecutionResult executeMixed(TaskContext context) {
        TaskPlan plan = context.getPlan();
        TaskExecutionResult result = new TaskExecutionResult();
        
        for (int i = context.getCurrentStepIndex(); i < plan.getSteps().size(); i++) {
            TaskStep step = plan.getSteps().get(i);
            context.setCurrentStepIndex(i);
            
            saveExecutionState(context);
            
            if (!checkDependencies(step, result.getStepResults())) {
                continue;
            }
            
            if (step.getType() == StepType.PARALLEL) {
                // 并行执行子步骤
                TaskExecutionResult parallelResult = executeParallelSteps(step, context);
                result.addStepResults(parallelResult.getStepResults());
            } else {
                // 串行执行
                StepExecutionResult stepResult = executeStep(step, context);
                
                if (stepResult.requiresInterruption()) {
                    return handleInterruption(context, stepResult);
                }
                
                if (stepResult.requiresReplanning()) {
                    return handleReplanning(context, stepResult);
                }
                
                context.getVariables().putAll(stepResult.getOutputs());
                result.addStepResult(step.getId(), stepResult);
                
                if (!stepResult.isSuccess() && !plan.isContinueOnError()) {
                    result.setSuccess(false);
                    break;
                }
            }
        }
        
        return result;
    }
    
    private TaskExecutionResult executeParallelSteps(TaskStep parallelStep, TaskContext context) {
        TaskExecutionResult result = new TaskExecutionResult();
        
        List<TaskStep> subSteps = parallelStep.getSubSteps();
        List<Future<StepExecutionResult>> futures = subSteps.stream()
            .map(step -> executorService.submit(() -> executeStep(step, context)))
            .collect(Collectors.toList());
        
        Map<String, StepExecutionResult> stepResults = new HashMap<>();
        for (int i = 0; i < subSteps.size(); i++) {
            try {
                StepExecutionResult stepResult = futures.get(i).get();
                stepResults.put(subSteps.get(i).getId(), stepResult);
                context.getVariables().putAll(stepResult.getOutputs());
            } catch (Exception e) {
                StepExecutionResult errorResult = new StepExecutionResult();
                errorResult.setSuccess(false);
                errorResult.setError(e.getMessage());
                stepResults.put(subSteps.get(i).getId(), errorResult);
            }
        }
        
        result.setStepResults(stepResults);
        return result;
    }
    
    private StepExecutionResult executeStep(TaskStep step, TaskContext context) {
        StepExecutionResult result = new StepExecutionResult();
        result.setStepId(step.getId());
        result.setStartedAt(LocalDateTime.now());
        
        try {
            switch (step.getType()) {
                case LLM_CALL:
                    result = executeLLMCall(step, context);
                    break;
                case TOOL_CALL:
                    result = executeToolCall(step, context);
                    break;
                case CONDITIONAL:
                    result = executeConditional(step, context);
                    break;
                case LOOP:
                    result = executeLoop(step, context);
                    break;
                case SUBTASK:
                    result = executeSubtask(step, context);
                    break;
                default:
                    throw new IllegalArgumentException("Unknown step type: " + step.getType());
            }
        } catch (Exception e) {
            result.setSuccess(false);
            result.setError(e.getMessage());
            
            // 重试逻辑
            if (step.getRetryCount() < step.getMaxRetries()) {
                step.setRetryCount(step.getRetryCount() + 1);
                return executeStep(step, context);
            }
        }
        
        result.setCompletedAt(LocalDateTime.now());
        result.setDurationMs(Duration.between(result.getStartedAt(), result.getCompletedAt()).toMillis());
        return result;
    }
    
    private StepExecutionResult executeLLMCall(TaskStep step, TaskContext context) {
        StepExecutionResult result = new StepExecutionResult();
        
        String prompt = replaceVariables(step.getPrompt(), context.getVariables());
        String response = llm.generate(prompt);
        
        result.setSuccess(true);
        result.setOutput("response", response);
        
        return result;
    }
    
    private StepExecutionResult executeToolCall(TaskStep step, TaskContext context) {
        StepExecutionResult result = new StepExecutionResult();
        
        ToolExecutionRequest request = ToolExecutionRequest.builder()
            .id(UUID.randomUUID().toString())
            .name(step.getToolName())
            .arguments(JsonUtil.toJson(step.getParameters()))
            .build();
        
        ToolExecutionResult toolResult = toolExecutor.execute(request, step.getTools());
        
        result.setSuccess(toolResult.success());
        result.setOutput("result", toolResult.output());
        
        return result;
    }
    
    private StepExecutionResult executeConditional(TaskStep step, TaskContext context) {
        StepExecutionResult result = new StepExecutionResult();
        
        boolean conditionMet = evaluateCondition(step.getCondition(), context.getVariables());
        
        if (conditionMet) {
            result.setSuccess(true);
            result.setOutput("conditionMet", true);
        } else {
            result.setSuccess(true);
            result.setOutput("conditionMet", false);
        }
        
        return result;
    }
    
    private StepExecutionResult executeLoop(TaskStep step, TaskContext context) {
        StepExecutionResult result = new StepExecutionResult();
        
        int iterations = (int) step.getParameters().get("iterations");
        List<Object> loopResults = new ArrayList<>();
        
        for (int i = 0; i < iterations; i++) {
            context.getVariables().put("loop_index", i);
            StepExecutionResult iterationResult = executeStep(step.getLoopBody(), context);
            loopResults.add(iterationResult.getOutputs());
        }
        
        result.setSuccess(true);
        result.setOutput("loopResults", loopResults);
        
        return result;
    }
    
    private StepExecutionResult executeSubtask(TaskStep step, TaskContext context) {
        StepExecutionResult result = new StepExecutionResult();
        
        TaskPlan subTask = subTaskManager.createSubTask(step, context);
        TaskContext subContext = new TaskContext();
        subContext.setTaskId(subTask.getId());
        subContext.setPlan(subTask);
        subContext.setCurrentStepIndex(0);
        subContext.setVariables(new HashMap<>(context.getVariables()));
        
        TaskExecutionResult subResult = executeWithContext(subContext);
        
        result.setSuccess(subResult.isSuccess());
        result.setOutput("subtaskResult", subResult);
        
        return result;
    }
    
    private TaskExecutionResult handleInterruption(TaskContext context, StepExecutionResult stepResult) {
        InterruptionType type = stepResult.getInterruptionType();
        
        switch (type) {
            case SUBTASK:
                return handleSubtaskInterruption(context, stepResult);
            case NEW_TASK:
                return handleNewTaskInterruption(context, stepResult);
            case REPLAN:
                return handleReplanningInterruption(context, stepResult);
            default:
                throw new IllegalArgumentException("Unknown interruption type: " + type);
        }
    }
    
    private TaskExecutionResult handleSubtaskInterruption(TaskContext context, StepExecutionResult stepResult) {
        stackManager.pushContext(context.getTaskId(), context);
        
        TaskPlan subTask = stepResult.getInterruptionTask();
        TaskContext subContext = new TaskContext();
        subContext.setTaskId(subTask.getId());
        subContext.setPlan(subTask);
        subContext.setCurrentStepIndex(0);
        subContext.setVariables(new HashMap<>(context.getVariables()));
        
        TaskExecutionResult subResult = executeWithContext(subContext);
        
        TaskContext resumedContext = stackManager.popContext(context.getTaskId());
        resumedContext.getVariables().putAll(subResult.getOutputs());
        
        return executeWithContext(resumedContext);
    }
    
    private TaskExecutionResult handleNewTaskInterruption(TaskContext context, StepExecutionResult stepResult) {
        pauseResumeManager.pauseTask(context, "Executing new task");
        
        TaskPlan newTask = stepResult.getInterruptionTask();
        TaskExecutionResult newResult = execute(newTask);
        
        pauseResumeManager.resumeTask(context.getTaskId(), newResult.getTaskId());
        
        return executeWithContext(context);
    }
    
    private TaskExecutionResult handleReplanningInterruption(TaskContext context, StepExecutionResult stepResult) {
        TaskPlan currentPlan = context.getPlan();
        int currentIndex = context.getCurrentStepIndex();
        
        TaskPlan newPlan = replanner.replan(
            currentPlan,
            currentIndex,
            stepResult,
            context.getVariables()
        );
        
        context.setPlan(newPlan);
        context.setCurrentStepIndex(0);
        
        return executeWithContext(context);
    }
    
    private boolean checkDependencies(TaskStep step, Map<String, StepExecutionResult> stepResults) {
        if (step.getDependencies() == null || step.getDependencies().isEmpty()) {
            return true;
        }
        
        for (String depId : step.getDependencies()) {
            StepExecutionResult depResult = stepResults.get(depId);
            if (depResult == null || !depResult.isSuccess()) {
                return false;
            }
        }
        
        return true;
    }
    
    private boolean evaluateCondition(StepCondition condition, Map<String, Object> variables) {
        String expression = replaceVariables(condition.getExpression(), variables);
        
        switch (condition.getOperator()) {
            case EQUALS:
                return evaluateEquals(expression, variables);
            case NOT_EQUALS:
                return !evaluateEquals(expression, variables);
            case GREATER_THAN:
                return evaluateGreaterThan(expression, variables);
            case LESS_THAN:
                return evaluateLessThan(expression, variables);
            case CONTAINS:
                return evaluateContains(expression, variables);
            case NOT_CONTAINS:
                return !evaluateContains(expression, variables);
            default:
                return false;
        }
    }
    
    private String replaceVariables(String template, Map<String, Object> variables) {
        String result = template;
        for (Map.Entry<String, Object> entry : variables.entrySet()) {
            result = result.replace("${" + entry.getKey() + "}", String.valueOf(entry.getValue()));
        }
        return result;
    }
    
    @Override
    public TaskStatus getStatus(String taskId) {
        TaskContext context = taskRepository.findById(taskId);
        return context != null ? context.getStatus() : null;
    }
    
    @Override
    public void pause(String taskId, String reason) {
        TaskContext context = taskRepository.findById(taskId);
        if (context != null) {
            pauseResumeManager.pauseTask(context, reason);
        }
    }
    
    @Override
    public void resume(String taskId, String resumeFromTaskId) {
        pauseResumeManager.resumeTask(taskId, resumeFromTaskId);
    }
    
    @Override
    public void cancel(String taskId) {
        TaskContext context = taskRepository.findById(taskId);
        if (context != null) {
            context.setStatus(TaskStatus.CANCELLED);
            taskRepository.save(context);
        }
    }
    
    @Override
    public TaskExecutionResult getResult(String taskId) {
        return taskRepository.findResultByTaskId(taskId);
    }
}
```

## 6. 与LangChain4j集成

### 6.1 工具执行器

```java
@Service
public class LangChain4jToolExecutor implements ToolExecutor {
    
    private final Map<String, Tool> toolRegistry = new ConcurrentHashMap<>();
    
    public void registerTool(Tool tool) {
        toolRegistry.put(tool.name(), tool);
    }
    
    @Override
    public ToolExecutionResult execute(ToolExecutionRequest request, List<ToolSpecification> tools) {
        Tool tool = toolRegistry.get(request.name());
        if (tool == null) {
            throw new IllegalArgumentException("Tool not found: " + request.name());
        }
        
        try {
            Object result = tool.execute(request.arguments());
            return ToolExecutionResult.builder()
                .success(true)
                .output(JsonUtil.toJson(result))
                .build();
        } catch (Exception e) {
            return ToolExecutionResult.builder()
                .success(false)
                .error(e.getMessage())
                .build();
        }
    }
}
```

### 6.2 LLM集成

```java
@Configuration
public class LangChain4jConfig {
    
    @Bean
    public ChatLanguageModel chatLanguageModel() {
        return OpenAiChatModel.builder()
            .apiKey(System.getenv("OPENAI_API_KEY"))
            .modelName("gpt-4")
            .temperature(0.7)
            .build();
    }
    
    @Bean
    public ToolExecutor toolExecutor() {
        return new LangChain4jToolExecutor();
    }
}
```

## 7. 数据库设计

### 7.1 任务计划表

```sql
CREATE TABLE task_plans (
    id VARCHAR(100) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    user_id VARCHAR(100) NOT NULL,
    session_id VARCHAR(100),
    steps JSON NOT NULL,
    execution_mode ENUM('SEQUENTIAL', 'PARALLEL', 'MIXED') NOT NULL,
    continue_on_error BOOLEAN DEFAULT FALSE,
    priority ENUM('LOW', 'MEDIUM', 'HIGH', 'URGENT') DEFAULT 'MEDIUM',
    parameters JSON,
    status ENUM('PENDING', 'RUNNING', 'PAUSED', 'COMPLETED', 'FAILED', 'CANCELLED') NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    
    INDEX idx_user_id (user_id),
    INDEX idx_session_id (session_id),
    INDEX idx_status (status),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

### 7.2 任务执行表

```sql
CREATE TABLE task_executions (
    id VARCHAR(100) PRIMARY KEY,
    plan_id VARCHAR(100) NOT NULL,
    parent_task_id VARCHAR(100),
    status ENUM('RUNNING', 'PAUSED', 'COMPLETED', 'FAILED', 'CANCELLED') NOT NULL,
    current_step_index INT DEFAULT 0,
    variables JSON,
    outputs JSON,
    paused_at TIMESTAMP,
    pause_reason TEXT,
    resume_from_task_id VARCHAR(100),
    started_at TIMESTAMP,
    completed_at TIMESTAMP,
    duration_ms BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    
    INDEX idx_plan_id (plan_id),
    INDEX idx_parent_task_id (parent_task_id),
    INDEX idx_status (status),
    
    FOREIGN KEY (plan_id) REFERENCES task_plans(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

### 7.3 步骤执行结果表

```sql
CREATE TABLE step_execution_results (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    task_id VARCHAR(100) NOT NULL,
    step_id VARCHAR(100) NOT NULL,
    success BOOLEAN NOT NULL,
    error TEXT,
    outputs JSON,
    started_at TIMESTAMP NOT NULL,
    completed_at TIMESTAMP,
    duration_ms BIGINT,
    retry_count INT DEFAULT 0,
    requires_interruption BOOLEAN DEFAULT FALSE,
    interruption_type ENUM('SUBTASK', 'NEW_TASK', 'REPLAN'),
    interruption_task_id VARCHAR(100),
    requires_replanning BOOLEAN DEFAULT FALSE,
    replan_reason TEXT,
    
    INDEX idx_task_id (task_id),
    INDEX idx_step_id (step_id),
    
    FOREIGN KEY (task_id) REFERENCES task_executions(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

### 7.4 任务栈表

```sql
CREATE TABLE task_stack (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    task_id VARCHAR(100) NOT NULL,
    stack_index INT NOT NULL,
    context JSON NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    
    UNIQUE KEY uk_task_stack (task_id, stack_index),
    INDEX idx_task_id (task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

## 8. API设计

### 8.1 任务分解API

```java
@RestController
@RequestMapping("/api/tasks")
public class TaskController {
    
    @Autowired
    private TaskOrchestrator orchestrator;
    
    @PostMapping("/decompose")
    public ResponseEntity<TaskPlan> decomposeTask(
        @RequestBody DecomposeRequest request
    ) {
        TaskPlan plan = orchestrator.decompose(
            request.getUserInput(),
            request.getSessionId()
        );
        return ResponseEntity.ok(plan);
    }
}

@Data
public class DecomposeRequest {
    private String userInput;
    private Long sessionId;
}
```

### 8.2 任务执行API

```java
@PostMapping("/execute")
public ResponseEntity<TaskExecutionResult> executeTask(
    @RequestBody ExecuteRequest request
) {
    TaskExecutionResult result = orchestrator.execute(request.getPlan());
    return ResponseEntity.ok(result);
}

@Data
public class ExecuteRequest {
    private TaskPlan plan;
}
```

### 8.3 任务状态API

```java
@GetMapping("/{taskId}/status")
public ResponseEntity<TaskStatus> getTaskStatus(
    @PathVariable String taskId
) {
    TaskStatus status = orchestrator.getStatus(taskId);
    return ResponseEntity.ok(status);
}

@GetMapping("/{taskId}/result")
public ResponseEntity<TaskExecutionResult> getTaskResult(
    @PathVariable String taskId
) {
    TaskExecutionResult result = orchestrator.getResult(taskId);
    return ResponseEntity.ok(result);
}
```

### 8.4 任务控制API

```java
@PostMapping("/{taskId}/pause")
public ResponseEntity<Void> pauseTask(
    @PathVariable String taskId,
    @RequestBody PauseRequest request
) {
    orchestrator.pause(taskId, request.getReason());
    return ResponseEntity.ok().build();
}

@PostMapping("/{taskId}/resume")
public ResponseEntity<Void> resumeTask(
    @PathVariable String taskId,
    @RequestBody ResumeRequest request
) {
    orchestrator.resume(taskId, request.getResumeFromTaskId());
    return ResponseEntity.ok().build();
}

@DeleteMapping("/{taskId}")
public ResponseEntity<Void> cancelTask(
    @PathVariable String taskId
) {
    orchestrator.cancel(taskId);
    return ResponseEntity.ok().build();
}
```

## 9. 最佳实践

### 9.1 任务分解

- 使用清晰的步骤描述
- 合理设置步骤依赖关系
- 为每个步骤设置合理的超时时间
- 配置适当的重试策略

### 9.2 错误处理

- 区分可恢复和不可恢复错误
- 为不同类型的错误设置不同的重试策略
- 记录详细的错误信息
- 提供错误恢复机制

### 9.3 性能优化

- 合理使用并行执行
- 避免过深的任务嵌套
- 及时清理已完成的任务
- 使用连接池管理资源

### 9.4 监控与日志

- 记录任务执行的完整日志
- 监控任务执行时间和成功率
- 设置告警规则
- 定期分析任务执行数据

## 10. 实施计划

### Phase 1: 基础框架（2周）
- 数据模型设计与建表
- 核心接口定义
- 基础任务执行引擎
- LangChain4j集成

### Phase 2: 动态调整（2周）
- 任务栈实现
- 子任务管理
- 动态重规划
- 任务暂停与恢复

### Phase 3: 高级特性（2周）
- 并行执行优化
- 条件分支和循环
- 错误处理与重试
- 任务依赖管理

### Phase 4: API与监控（1周）
- REST API实现
- 任务监控与告警
- 日志系统
- 性能优化

### Phase 5: 测试与优化（1周）
- 单元测试
- 集成测试
- 性能测试
- 压力测试

## 11. 扩展方向

### 11.1 多Agent协作
- 支持多个Agent协同完成任务
- Agent之间的通信机制
- 任务分配与协调

### 11.2 任务模板
- 预定义任务模板
- 模板参数化
- 模板复用

### 11.3 可视化编辑器
- 任务流程可视化
- 拖拽式任务编排
- 实时预览

### 11.4 任务市场
- 任务分享与交易
- 任务评价与推荐
- 社区贡献
