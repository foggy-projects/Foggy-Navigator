package com.foggy.navigator.claude.worker.model.entity;

import com.foggy.navigator.claude.worker.model.converter.CodexConfigConverter;
import com.foggy.navigator.common.entity.BaseWorkerEntity;
import com.foggy.navigator.common.model.CodexConfig;
import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Claude Worker 注册信息 —— 继承 BaseWorkerEntity，保留 Claude 特有字段。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Entity
@Table(name = "claude_workers", indexes = {
    @Index(name = "idx_cw_user_id", columnList = "userId")
})
public class ClaudeWorkerEntity extends BaseWorkerEntity {

    // ── Claude 特有字段 ──

    /** Code Server 公网 URL */
    @Column(length = 512)
    private String codeServerPublicUrl;

    /** Code Server 内网 URL */
    @Column(length = 512)
    private String codeServerInternalUrl;

    /** Code Server 密码（加密存储） */
    @Column(columnDefinition = "TEXT")
    private String codeServerPassword;

    /** Code Server folder 路径前缀（用于 Windows→WSL 路径转换，如 /mnt/{drive}） */
    @Column(length = 256)
    private String codeServerFolderPrefix;

    /** SSH 用户名 */
    @Column(length = 128)
    private String sshUsername;

    /** SSH 端口，默认 22 */
    private Integer sshPort;

    /** SSH 密码（加密存储） */
    @Column(columnDefinition = "TEXT")
    private String sshPassword;

    /** Codex Worker 配置（JSON 存储：baseUrl, authToken, model） */
    @Convert(converter = CodexConfigConverter.class)
    @Column(columnDefinition = "TEXT")
    private CodexConfig codexConfig;
}
