package com.foggy.navigator.business.agent.model.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "client_app_upstream_route", uniqueConstraints = {
        @UniqueConstraint(name = "uk_client_app_upstream_route", columnNames = {"tenantId", "clientAppId", "upstreamRef"})
}, indexes = {
        @Index(name = "idx_caur_client_app", columnList = "clientAppId"),
        @Index(name = "idx_caur_status", columnList = "status")
})
public class ClientAppUpstreamRouteEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 128)
    private String tenantId;

    @Column(nullable = false, length = 128)
    private String clientAppId;

    @Column(nullable = false, length = 128)
    private String upstreamRef;

    @Column(nullable = false, length = 1024)
    private String baseUrl;

    @Column(length = 128)
    private String userTokenHeader;

    @Column(nullable = false, length = 64)
    private String status;

    @Column(length = 512)
    private String description;

    @Column(length = 128)
    private String createdBy;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
