package com.foggy.navigator.agent.framework.event;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * 统一 Worker 任务启动事件 —— 所有 Agent Worker 共用。
 * <p>
 * 公共字段直接暴露，Provider 特有配置通过 {@link #providerConfig} Map 传递（组合方式）。
 * <p>
 * providerConfig 常用 key：
 * <ul>
 *   <li>Claude: claudeSessionId, agentTeamsJson, images, authToken, baseUrl,
 *       permissionMode, navigatorApiKey, navigatorApiBase, extraEnvVars</li>
 *   <li>Codex: codexThreadId</li>
 * </ul>
 */
@Data
@Builder
public class WorkerTaskStartEvent {

    // ── 公共字段（所有 Provider 都需要） ──

    private String taskId;
    private String sessionId;
    private String workerId;
    private String prompt;
    private String cwd;
    private String model;
    private Integer maxTurns;
    private String apiKey;

    /** Provider 类型标识（"claude-worker" / "codex-worker" / ...） */
    private String providerType;

    /** 用户 ID（可选，部分 Provider 需要） */
    private String userId;

    /** 租户 ID（可选，部分 Provider 需要） */
    private String tenantId;

    // ── Provider 特有配置（组合方式，避免继承膨胀） ──

    /** Provider 特有配置项，key-value 形式 */
    @Builder.Default
    private Map<String, Object> providerConfig = Map.of();

    // ── 便捷访问器 ──

    @SuppressWarnings("unchecked")
    public <T> T getProviderConfigValue(String key) {
        return (T) providerConfig.get(key);
    }

    public String getProviderConfigString(String key) {
        Object v = providerConfig.get(key);
        return v != null ? v.toString() : null;
    }
}
