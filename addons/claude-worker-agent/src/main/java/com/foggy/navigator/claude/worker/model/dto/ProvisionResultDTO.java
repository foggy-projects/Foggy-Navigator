package com.foggy.navigator.claude.worker.model.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 员工 Provisioning 结果
 */
@Data
@Builder
public class ProvisionResultDTO {

    /** 第三方员工 ID */
    private String externalUserId;

    /** Navigator 内部用户 ID */
    private String userId;

    /** Navigator 用户名（员工可用此登录） */
    private String username;

    /** 员工密码（仅首次创建时返回） */
    private String password;

    /** 工作目录 ID */
    private String directoryId;

    /** 工作目录路径 */
    private String directoryPath;

    /** A2A Agent ID（可用于向 Agent 发送查询） */
    private String agentId;

    /** 是否新创建了用户（false 表示已存在） */
    private boolean userCreated;

    /** 是否新创建了目录（false 表示已存在） */
    private boolean directoryCreated;
}
