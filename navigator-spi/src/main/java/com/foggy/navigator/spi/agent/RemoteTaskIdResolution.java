package com.foggy.navigator.spi.agent;

/**
 * 远端任务标识解析结果 -- 统一回答"调用 Worker 时应传哪个 task id"。
 * <p>
 * 每个 Provider 通过 {@link InnerA2aAgent#resolveRemoteTaskId(String)} 返回此对象，
 * 确保 abort / status / subscribe 使用同一套解析规则。
 *
 * @param primaryId       优先使用的远端标识（通常是 workerTaskId）
 * @param fallbackId      备选标识（通常是平台 taskId），可为 null
 * @param fallbackAllowed 是否允许在 primaryId 缺失时使用 fallbackId
 * @param resolvedId      最终解析结果：primaryId 非空则用 primaryId，否则视 fallbackAllowed 决定
 */
public record RemoteTaskIdResolution(
        String primaryId,
        String fallbackId,
        boolean fallbackAllowed,
        String resolvedId
) {

    /**
     * 仅提供 primaryId，不允许 fallback。
     * <p>
     * 适用于 Codex 等严格依赖 workerTaskId 的 Provider。
     */
    public static RemoteTaskIdResolution of(String primaryId, boolean fallbackAllowed) {
        String resolved = (primaryId != null && !primaryId.isBlank()) ? primaryId : null;
        return new RemoteTaskIdResolution(primaryId, null, fallbackAllowed, resolved);
    }

    /**
     * 提供 primaryId + fallbackId，允许在 primaryId 缺失时回退。
     * <p>
     * 适用于 Claude 等支持平台 taskId 别名的 Provider。
     */
    public static RemoteTaskIdResolution withFallback(String primaryId, String fallbackId, boolean fallbackAllowed) {
        String resolved;
        if (primaryId != null && !primaryId.isBlank()) {
            resolved = primaryId;
        } else if (fallbackAllowed && fallbackId != null && !fallbackId.isBlank()) {
            resolved = fallbackId;
        } else {
            resolved = null;
        }
        return new RemoteTaskIdResolution(primaryId, fallbackId, fallbackAllowed, resolved);
    }
}
