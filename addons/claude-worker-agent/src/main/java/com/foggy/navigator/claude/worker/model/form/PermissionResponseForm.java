package com.foggy.navigator.claude.worker.model.form;

import lombok.Data;

/**
 * 权限响应表单
 */
@Data
public class PermissionResponseForm {
    /** 权限请求ID */
    private String permissionId;
    /** 决策: allow / deny */
    private String decision;
    /** 拒绝原因（可选） */
    private String denyMessage;
    /** 范围: once / session / always */
    private String scope;
    /** AskUserQuestion 回答（question text -> selected label） */
    private java.util.Map<String, String> answers;
    /** Plan 审批后的执行选项: bypass / acceptEdits / clearAndBypass */
    private String planAction;
}
