package com.foggy.navigator.common.entity;

import com.foggy.navigator.common.enums.ConfigItemStatus;
import com.foggy.navigator.common.enums.DatasourceType;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 数据源配置 JPA Entity
 */
@Data
@Entity
@Table(name = "datasource_configs", indexes = {
    @Index(name = "idx_tenant_id", columnList = "tenantId"),
    @Index(name = "idx_status", columnList = "status")
})
public class DatasourceConfigEntity {

    @Id
    @Column(length = 64)
    private String id;

    @Column(length = 64)
    private String tenantId;

    /**
     * 数据源名称
     */
    @Column(length = 128)
    private String name;

    /**
     * 数据源类型
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private DatasourceType type;

    /**
     * 数据库类型：MySQL, PostgreSQL等（JDBC类型时使用）
     */
    @Column(length = 32)
    private String dbType;

    /**
     * 主机地址
     */
    @Column(length = 255)
    private String host;

    /**
     * 端口号
     */
    private Integer port;

    /**
     * 数据库名称
     */
    @Column(length = 128)
    private String databaseName;

    /**
     * 用户名
     */
    @Column(length = 128)
    private String username;

    /**
     * 加密后的密码
     */
    @Column(length = 512)
    private String password;

    /**
     * JDBC URL（可选）
     */
    @Column(length = 512)
    private String jdbcUrl;

    /**
     * 额外参数
     */
    @Column(length = 512)
    private String extraParams;

    /**
     * MongoDB连接字符串（可选）
     */
    @Column(length = 512)
    private String connectionString;

    /**
     * 配置状态
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 32, nullable = false)
    private ConfigItemStatus status;

    /**
     * 连接是否有效
     */
    private Boolean connectionValid;

    /**
     * 配置描述
     */
    @Column(columnDefinition = "TEXT")
    private String description;

    /**
     * 创建时间
     */
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (status == null) {
            status = ConfigItemStatus.NOT_STARTED;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
