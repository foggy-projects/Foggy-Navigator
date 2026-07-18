package com.foggy.navigator.codex.worker.service;

/**
 * Signals that a stale active task was durably repaired and the caller must retry.
 */
public final class CodexStaleTaskRepairedException extends IllegalStateException {

    public static final String CODE = "CODEX_STALE_TASK_REPAIRED";

    public CodexStaleTaskRepairedException() {
        super(CODE + ": 绑定 Worker 中已不存在上一次任务及对应 Codex CLI，残留运行状态已自动修复，请重新尝试");
    }
}
