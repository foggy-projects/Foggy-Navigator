package com.foggy.navigator.session.service;

import java.util.LinkedHashMap;
import java.util.Map;

final class TaskDispatchRequestParams {

    private TaskDispatchRequestParams() {
    }

    /**
     * Request -> Map 公共转换：提取所有标准字段到 Map，供 Direct/Resume/A2A 各路径复用。
     */
    @SuppressWarnings("deprecation")
    static Map<String, Object> toCommonParams(TaskDispatchRequest request) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (request.getMetadata() != null && !request.getMetadata().isEmpty()) {
            params.putAll(request.getMetadata());
        }
        putIfNotBlank(params, "agentId", request.getAgentId());
        putIfNotBlank(params, "providerType", request.getProviderType());
        putIfNotBlank(params, "sessionId", request.getSessionId());
        putIfNotBlank(params, "contextId", request.getContextId());
        putIfNotBlank(params, "workerId", request.getWorkerId());
        putIfNotBlank(params, "prompt", request.getPrompt());
        putIfNotBlank(params, "cwd", request.getCwd());
        putIfNotBlank(params, "directoryId", request.getDirectoryId());
        putIfNotBlank(params, "model", request.getModel());
        putIfNotBlank(params, "modelConfigId", request.getModelConfigId());
        putIfNotBlank(params, "permissionMode", request.getPermissionMode());
        putIfNotBlank(params, "agentTeamsConfigId", request.getAgentTeamsConfigId());
        putIfNotBlank(params, "agentTeamsJson", request.getAgentTeamsJson());
        if (request.getContext() != null && !request.getContext().isEmpty()) {
            params.put("context", request.getContext());
        }
        // claudeSessionId / codexThreadId / geminiSessionId 不再透传，Provider 从 SessionEntity.providerStateJson 恢复。
        if (request.getMaxTurns() != null) {
            params.put("maxTurns", request.getMaxTurns());
        }
        if (request.getImages() != null && !request.getImages().isEmpty()) {
            params.put("images", String.join(",", request.getImages()));
        }
        if (request.getAttachments() != null && !request.getAttachments().isEmpty()) {
            params.put("attachments", request.getAttachments());
        }
        return params;
    }

    private static void putIfNotBlank(Map<String, Object> map, String key, String value) {
        if (value != null && !value.isBlank()) {
            map.put(key, value);
        }
    }
}
