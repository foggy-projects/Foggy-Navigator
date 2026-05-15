package com.foggy.navigator.business.agent.model.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "skill_bundle", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"tenantId", "clientAppId", "scope", "accountId", "skillId"})
})
public class SkillBundleEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 128, unique = true)
    private String bundleId;

    @Column(nullable = false, length = 128)
    private String tenantId;

    @Column(nullable = false, length = 128)
    private String clientAppId;

    @Column(nullable = false, length = 64)
    private String scope;

    @Column(nullable = false, length = 128)
    private String accountId;

    @Column(nullable = false, length = 128)
    private String skillId;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(columnDefinition = "LONGTEXT")
    private String markdownBody;

    @Column(nullable = false, length = 32)
    private String contextVisibility = "isolated";

    @Column(columnDefinition = "LONGTEXT")
    private String resourcesJson;

    @Column(columnDefinition = "LONGTEXT")
    private String functionsJson;

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
