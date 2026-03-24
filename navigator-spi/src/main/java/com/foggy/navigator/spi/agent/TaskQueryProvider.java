package com.foggy.navigator.spi.agent;

import com.foggy.navigator.common.dto.DispatchTaskDTO;

import java.util.List;
import java.util.Optional;

/**
 * 任务查询 SPI —— 各 Agent 后端实现，供 TaskDispatchFacade 聚合查询。
 * <p>
 * 与 A2aAgent 的区别：A2aAgent 面向执行（send/cancel），
 * TaskQueryProvider 面向查询（get/list），不需要 Agent 实例。
 */
public interface TaskQueryProvider {

    /** Provider 类型（与 A2aAgentProvider.getProviderType() 一致） */
    String getProviderType();

    /** 按 taskId 查询（跨用户，内部用） */
    Optional<DispatchTaskDTO> getTaskById(String taskId);

    /** 按 taskId + userId 查询（外部用，含权限校验） */
    Optional<DispatchTaskDTO> getTaskByIdAndUser(String taskId, String userId);

    /** 按 sessionId 查询该会话下的所有任务 */
    List<DispatchTaskDTO> listTasksBySession(String sessionId);

    /** 按 userId 查询活跃任务（RUNNING / AWAITING_PERMISSION） */
    List<DispatchTaskDTO> listActiveDispatchTasks(String userId);

    // ── 任务创建（前端直达 TaskService，绕过 A2A sendTask） ──

    /**
     * 直接创建任务（前端路径：发布 TaskStartEvent → StreamRelay SSE 消费）。
     * <p>
     * 与 A2aAgent.sendTask() 的区别：
     * <ul>
     *   <li>sendTask: 后端异步查询，不发布 TaskStartEvent，不启动 SSE relay</li>
     *   <li>createTaskDirect: 完整的前端任务创建路径，包含 session 创建 + 事件发布</li>
     * </ul>
     *
     * @param params 任务参数（workerId, prompt, cwd, directoryId, model, maxTurns, etc.）
     * @param userId 用户 ID
     * @param tenantId 租户 ID（可空）
     * @return 统一任务 DTO
     */
    default DispatchTaskDTO createTaskDirect(java.util.Map<String, Object> params,
                                              String userId, String tenantId) {
        throw new UnsupportedOperationException("createTaskDirect not supported by " + getProviderType());
    }

    // ── 任务操作（default 抛不支持异常，Provider 按需覆写） ──

    /** 回复权限请求 / 用户问题 */
    default void respondToTask(String taskId, String userId, java.util.Map<String, Object> response) {
        throw new UnsupportedOperationException("respond not supported by " + getProviderType());
    }

    /** 重连任务 SSE 流 */
    default void reconnectTask(String taskId, String userId) {
        throw new UnsupportedOperationException("reconnect not supported by " + getProviderType());
    }

    /** 重新同步任务状态 */
    default Object resyncTask(String taskId, String userId) {
        throw new UnsupportedOperationException("resync not supported by " + getProviderType());
    }

    /** 回退到检查点 */
    default void rewindTask(String taskId, String userId, java.util.Map<String, Object> params) {
        throw new UnsupportedOperationException("rewind not supported by " + getProviderType());
    }
}
