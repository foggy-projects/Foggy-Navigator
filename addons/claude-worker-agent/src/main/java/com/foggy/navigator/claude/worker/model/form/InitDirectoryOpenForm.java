package com.foggy.navigator.claude.worker.model.form;

import lombok.Data;

import java.util.Map;

/**
 * Open API 目录初始化表单
 * 创建工作目录并写入文件（不绑定员工身份）
 */
@Data
public class InitDirectoryOpenForm {

    /** Worker ID（必填） */
    private String workerId;

    /** 目录路径（必填） */
    private String path;

    /** 项目名称（可选） */
    private String projectName;

    /** 初始化文件：文件相对路径 → 内容（必填） */
    private Map<String, String> files;
}
