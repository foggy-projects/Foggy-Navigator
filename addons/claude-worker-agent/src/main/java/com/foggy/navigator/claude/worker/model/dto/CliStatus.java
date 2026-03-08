package com.foggy.navigator.claude.worker.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Claude Code CLI 进程存活状态
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CliStatus {
    /** CLI 进程是否存活 */
    private boolean alive;
    /** Worker 服务是否可达 */
    private boolean workerReachable;
    /** 任务是否还在 Worker task_registry 中 */
    private boolean taskInRegistry;
    /** 检测来源：task_status / process_list / fallback / health_check */
    private String source;
    /** 补充说明 */
    private String detail;

    public static CliStatus unreachable(String reason) {
        return CliStatus.builder()
                .alive(false).workerReachable(false)
                .source("health_check").detail(reason).build();
    }
}
