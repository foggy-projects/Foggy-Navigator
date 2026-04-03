package com.foggy.navigator.session.agent;

import com.foggy.navigator.common.dto.DispatchTaskDTO;
import com.foggy.navigator.common.dto.a2a.A2aAgentCard;
import com.foggy.navigator.common.dto.a2a.A2aMessage;
import com.foggy.navigator.common.dto.a2a.A2aTask;
import com.foggy.navigator.spi.agent.A2aAgent;
import com.foggy.navigator.spi.agent.InnerA2aAgent;
import com.foggy.navigator.spi.agent.RemoteTaskIdResolution;
import com.foggy.navigator.spi.agent.TaskQueryProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.Set;

/**
 * A2aAgent 装饰者 — 统一中止业务编排。
 * <p>
 * 装饰链位置：{@code AbortCoordinatingA2aAgent → ContextResolvingA2aAgent → InnerA2aAgent}
 * <p>
 * 只拦截 {@link #cancelTask(String)}，其余操作直接委托给 delegate。
 * 中止生命周期模板：
 * <ol>
 *   <li>加载任务（通过 TaskQueryProvider）</li>
 *   <li>Terminal-state guard（幂等）</li>
 *   <li>解析远端任务标识（通过 InnerA2aAgent）</li>
 *   <li>远端中止 + 流清理 + 状态落库 + 事件发布（通过 InnerA2aAgent.abortWorkerTask）</li>
 *   <li>Provider 专属后置钩子（通过 InnerA2aAgent.onPostAbort）</li>
 * </ol>
 */
public class AbortCoordinatingA2aAgent implements A2aAgent {

    private static final Logger log = LoggerFactory.getLogger(AbortCoordinatingA2aAgent.class);

    private static final Set<String> TERMINAL_STATES = Set.of("COMPLETED", "FAILED", "ABORTED");

    private final A2aAgent delegate;
    private final InnerA2aAgent innerAgent;
    private final TaskQueryProvider taskQueryProvider;

    public AbortCoordinatingA2aAgent(A2aAgent delegate,
                                     InnerA2aAgent innerAgent,
                                     TaskQueryProvider taskQueryProvider) {
        this.delegate = delegate;
        this.innerAgent = innerAgent;
        this.taskQueryProvider = taskQueryProvider;
    }

    @Override
    public A2aAgentCard getAgentCard() {
        return delegate.getAgentCard();
    }

    @Override
    public A2aTask sendTask(A2aMessage message) {
        return delegate.sendTask(message);
    }

    @Override
    public Optional<A2aTask> getTask(String taskId) {
        return delegate.getTask(taskId);
    }

    /**
     * 统一中止入口 — 执行完整的 abort 生命周期模板。
     */
    @Override
    public void cancelTask(String taskId) {
        // 1. 加载任务
        Optional<DispatchTaskDTO> taskOpt = taskQueryProvider.getTaskById(taskId);
        if (taskOpt.isEmpty()) {
            log.warn("cancelTask: task not found, taskId={}", taskId);
            throw new IllegalArgumentException("Task not found: " + taskId);
        }

        DispatchTaskDTO task = taskOpt.get();

        // 2. Terminal-state guard（幂等）
        if (TERMINAL_STATES.contains(task.getStatus())) {
            log.info("cancelTask: task {} already in terminal state ({}), skipping", taskId, task.getStatus());
            return;
        }

        // 3. 解析远端任务标识
        RemoteTaskIdResolution resolution = innerAgent.resolveRemoteTaskId(taskId);
        String remoteId = resolution.resolvedId();
        if (remoteId == null) {
            log.warn("cancelTask: remote task ID unresolved for taskId={}, resolution={}", taskId, resolution);
        }

        // 4. 远端中止 + 流清理 + 状态落库 + 事件发布
        innerAgent.abortWorkerTask(taskId, remoteId);

        // 5. Provider 专属后置钩子
        try {
            innerAgent.onPostAbort(taskId);
        } catch (Exception e) {
            log.warn("cancelTask: post-abort hook failed for taskId={}: {}", taskId, e.getMessage());
        }
    }
}
