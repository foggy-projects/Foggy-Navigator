package com.foggy.navigator.claude.worker.model.form;

import lombok.Data;

/**
 * 推进阶段表单
 */
@Data
public class AdvancePhaseForm {
    /** 可选：用户编辑后的交接信息（为空则使用已保存的 handoffArtifact） */
    private String handoffArtifact;
}
