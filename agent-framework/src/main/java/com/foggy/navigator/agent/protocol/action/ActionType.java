package com.foggy.navigator.agent.protocol.action;

/**
 * 用户操作类型
 */
public enum ActionType {
    CONFIRM,            // 确认/取消
    FORM_SUBMIT,        // 表单提交
    SINGLE_SELECT,      // 单选
    MULTI_SELECT,       // 多选
    FILE_UPLOAD,        // 文件上传
    CUSTOM              // 自定义
}
