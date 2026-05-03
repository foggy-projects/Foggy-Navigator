package com.foggy.navigator.business.agent.model.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "client_app_upstream_user_grant", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"tenantId", "clientAppId", "upstreamUserId"})
})
public class ClientAppUpstreamUserGrantEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 128, unique = true)
    private String grantId;

    @Column(nullable = false, length = 128)
    private String tenantId;

    @Column(nullable = false, length = 128)
    private String clientAppId;

    @Column(nullable = false, length = 255)
    private String upstreamUserId;

    @Column(nullable = false, length = 64)
    private String status;

    @Column(length = 128)
    private String createdBy;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = this.createdAt;
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
