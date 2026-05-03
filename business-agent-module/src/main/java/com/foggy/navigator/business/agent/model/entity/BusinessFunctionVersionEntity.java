package com.foggy.navigator.business.agent.model.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "business_function_version", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"tenantId", "functionId", "version"})
})
public class BusinessFunctionVersionEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 128)
    private String tenantId;

    @Column(nullable = false, length = 255)
    private String functionId;

    @Column(nullable = false, length = 64)
    private String version;

    @Column(columnDefinition = "TEXT")
    private String manifestJson;

    @Column(columnDefinition = "TEXT")
    private String inputSchemaJson;

    @Column(columnDefinition = "TEXT")
    private String outputSchemaJson;

    @Column(columnDefinition = "TEXT")
    private String llmVisibleSummary;

    @Column(columnDefinition = "TEXT")
    private String schemaVisibleSummary;

    @Column(columnDefinition = "TEXT")
    private String adapterConfigJson;

    @Column(nullable = false, length = 64)
    private String status;

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
