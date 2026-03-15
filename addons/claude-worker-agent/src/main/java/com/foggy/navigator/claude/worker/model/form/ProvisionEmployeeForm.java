package com.foggy.navigator.claude.worker.model.form;

import lombok.Data;

import java.util.Map;

/**
 * 员工 Provisioning 表单
 * 一站式创建员工用户 + 工作目录 + 初始化文件
 */
@Data
public class ProvisionEmployeeForm {

    /** 第三方系统的员工 ID（必填） */
    private String externalUserId;

    /** 员工显示名（可选） */
    private String displayName;

    /** 员工密码（可选，不传则自动生成） */
    private String password;

    /** Worker ID（必填） */
    private String workerId;

    /** 工作目录路径（必填） */
    private String directoryPath;

    /** 项目名称（可选） */
    private String projectName;

    /** 初始化文件：文件相对路径 → 内容（必填，至少包含 CLAUDE.md） */
    private Map<String, String> files;
}
