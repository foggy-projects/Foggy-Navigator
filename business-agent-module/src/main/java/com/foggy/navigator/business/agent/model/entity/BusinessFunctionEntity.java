package com.foggy.navigator.business.agent.model.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "business_function", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"tenantId", "functionId"})
})
public class BusinessFunctionEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 128)
    private String tenantId;

    @Column(name = "business_object_id", length = 128)
    private String businessObjectId;

    @Column(nullable = false, length = 255)
    private String functionId;

    @Column(nullable = false, length = 128)
    private String domain;

    @Column(length = 255)
    private String name;

    @Column(length = 1000)
    private String description;

    @Column(length = 64)
    private String currentVersion;

    @Column(length = 128)
    private String exposure;

    @Column(length = 64)
    private String riskLevel;

    @Column(nullable = false)
    private Boolean approvalRequired = false;

    @Column(nullable = false)
    private Boolean idempotencyRequired = false;

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
