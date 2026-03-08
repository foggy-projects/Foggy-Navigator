package com.foggy.navigator.codereview.model.form;

import lombok.Data;

/**
 * 创建/更新代码审核配置的表单
 */
@Data
public class CodeReviewConfigForm {
    /** Git 提供者配置 ID（用于获取 GitLab token 和 baseUrl） */
    private String gitProviderConfigId;
    /** GitLab 项目数字 ID */
    private Long gitlabProjectId;
    /** 项目显示名称 */
    private String projectName;
    /** 使用哪个 Claude Worker */
    private String workerId;
    /** 逗号分隔的触发事件: "open,reopen,update" */
    private String triggerEvents;
    /** 审核评论语言: "zh" 或 "en" */
    private String reviewLanguage;
    /** 最大 diff 行数 */
    private Integer maxDiffLines;
    /** 启停开关 */
    private Boolean isActive;
}
