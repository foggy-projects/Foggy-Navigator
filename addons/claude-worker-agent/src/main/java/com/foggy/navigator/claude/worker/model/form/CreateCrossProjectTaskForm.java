package com.foggy.navigator.claude.worker.model.form;

import lombok.Data;

import java.util.List;

/**
 * 创建跨项目任务表单
 */
@Data
public class CreateCrossProjectTaskForm {
    private String title;
    private String description;
    private String initialSessionId;
    private String initialDirectoryId;
    private List<PhaseForm> phases;

    @Data
    public static class PhaseForm {
        private String phaseName;
        private String prompt;
        private String agentId;
        private String directoryId;
        private String worktreeBranch;
    }
}
