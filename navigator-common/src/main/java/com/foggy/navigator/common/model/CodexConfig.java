package com.foggy.navigator.common.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Codex Worker 配置（JSON 存储在 ClaudeWorkerEntity 中）
 * <p>
 * 当 Worker 同时运行 codex-agent-worker 服务时，通过该配置连接。
 * 字段存储在数据库 JSON 列中，authToken 加密存储。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CodexConfig {

    /** Codex Worker 服务地址，例如 http://localhost:3032 */
    private String baseUrl;

    /** Bearer token（数据库中加密存储） */
    private String authToken;

    /** 默认模型，例如 codex-mini-latest */
    private String model;
}
