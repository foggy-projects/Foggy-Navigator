package com.foggy.navigator.spi.agent;

import com.foggy.navigator.common.dto.a2a.A2aAgentCard;
import com.foggy.navigator.common.dto.a2a.A2aContext;
import com.foggy.navigator.common.dto.a2a.A2aTask;

import java.util.Optional;

/**
 * 内层 Agent 接口 — 接收已解析的上下文，执行实际任务。
 * 外层 ContextResolvingA2aAgent 负责上下文解析后委托给此接口。
 */
public interface InnerA2aAgent {
    A2aAgentCard getAgentCard();
    A2aTask sendTask(A2aContext context);
    Optional<A2aTask> getTask(String taskId);
    void cancelTask(String taskId);

    /**
     * 检查指定的 agent session 是否有正在运行的任务。
     * ContextResolvingA2aAgent 在多轮续接时调用，防止向正在执行的 session 发送新任务。
     *
     * @param agentSessionRef agent 侧的会话标识（claudeSessionId / codexThreadId）
     * @return true 表示有 RUNNING 任务，应拒绝新请求
     */
    default boolean isSessionBusy(String agentSessionRef) {
        return false;
    }

    /**
     * 查找近期重复请求对应的任务；默认不去重。
     */
    default Optional<A2aTask> findRecentDuplicate(A2aContext context) {
        return Optional.empty();
    }

    /**
     * 在新任务成功创建后记录 dedup 键；默认不处理。
     */
    default void rememberDuplicate(A2aContext context, A2aTask task) {
        // Optional hook for implementations with request dedup support.
    }

    // ── Abort Coordination SPI（供 AbortCoordinatingA2aAgent 装饰层调用） ──

    /**
     * 解析远端 Worker 任务标识。
     * <p>
     * 装饰层在执行中止前调用此方法，获取统一的远端任务 ID。
     * 同一 Provider 的 abort / status / subscribe 应复用同一解析规则。
     *
     * @param taskId 平台侧 taskId
     * @return 解析结果，包含优先 ID、备选 ID 和 fallback 策略
     */
    default RemoteTaskIdResolution resolveRemoteTaskId(String taskId) {
        return RemoteTaskIdResolution.of(taskId, false);
    }

    /**
     * 执行远端中止 + 本地流清理 + 状态落库 + 事件发布。
     * <p>
     * 这是"真正停止执行"的内部动作，由装饰层在完成 terminal-state guard 后调用。
     * 不包含 Provider 专属后置钩子（见 {@link #onPostAbort}）。
     *
     * @param taskId       平台侧 taskId
     * @param remoteTaskId 已解析的远端 Worker 任务标识（可能为 null）
     */
    default void abortWorkerTask(String taskId, String remoteTaskId) {
        // 向后兼容：未迁移的实现走 legacy cancelTask
        cancelTask(taskId);
    }

    /**
     * Provider 专属的 abort 后置钩子。
     * <p>
     * 在 abortWorkerTask 完成后由装饰层调用。
     * 例如 Claude 在此更新 session interaction state 和扫描 checkpoints。
     * 默认为空操作。
     *
     * @param taskId 平台侧 taskId
     */
    default void onPostAbort(String taskId) {
        // no-op by default
    }
}
