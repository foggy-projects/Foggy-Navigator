package com.foggy.navigator.workbench.fap.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Workbench-owned durable lane binding for new FAP conversations only.
 *
 * <p>It is intentionally not a SessionEntity and stores no transcript, ticket, grant, credential,
 * Worker route, or historical Navigator ID. The fixed lane has no setter: a conversation can
 * never move between legacy and FAP execution paths.
 */
@Getter
@Entity
@Table(
        name = "workbench_fap_conversation_bindings",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_wfcb_owner_start_request",
                columnNames = {"owner_user_id", "start_request_id"}))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WorkbenchFapConversationBindingEntity {
    public static final String EXECUTION_LANE = "FAP_V1";

    @Id
    @Column(name = "conversation_id", length = 64, nullable = false, updatable = false)
    private String conversationId;

    @Column(name = "owner_user_id", length = 128, nullable = false, updatable = false)
    private String ownerUserId;

    @Column(name = "execution_lane", length = 32, nullable = false, updatable = false)
    private String executionLane;

    @Enumerated(EnumType.STRING)
    @Column(name = "binding_status", length = 48, nullable = false)
    private BindingStatus bindingStatus;

    @Column(name = "start_request_id", length = 512, nullable = false, updatable = false)
    private String startRequestId;

    @Column(name = "display_title", length = 256, nullable = false)
    private String displayTitle;

    @Column(name = "last_task_request_id", length = 512)
    private String lastTaskRequestId;

    @Column(name = "execution_id", length = 512)
    private String executionId;

    @Column(name = "current_task_id", length = 512)
    private String currentTaskId;

    @Column(name = "worker_profile_ref", length = 512, nullable = false, updatable = false)
    private String workerProfileRef;

    @Column(name = "workspace_ref", length = 512, nullable = false, updatable = false)
    private String workspaceRef;

    @Column(name = "model_config_ref", length = 512, updatable = false)
    private String modelConfigRef;

    @Column(name = "allow_default_model_config", nullable = false, updatable = false)
    private boolean allowDefaultModelConfig;

    @Column(name = "effective_tool_scope", columnDefinition = "TEXT")
    private String effectiveToolScope;

    @Column(name = "effective_permission_scope", columnDefinition = "TEXT")
    private String effectivePermissionScope;

    @Column(name = "last_error_code", length = 128)
    private String lastErrorCode;

    @Version
    @Column(name = "entity_version", nullable = false)
    private long entityVersion;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public static WorkbenchFapConversationBindingEntity starting(
            String conversationId,
            String ownerUserId,
            String startRequestId,
            String displayTitle,
            String workerProfileRef,
            String workspaceRef,
            String modelConfigRef,
            boolean allowDefaultModelConfig) {
        WorkbenchFapConversationBindingEntity entity = new WorkbenchFapConversationBindingEntity();
        entity.conversationId = conversationId;
        entity.ownerUserId = ownerUserId;
        entity.executionLane = EXECUTION_LANE;
        entity.bindingStatus = BindingStatus.STARTING;
        entity.startRequestId = startRequestId;
        entity.displayTitle = displayTitle;
        entity.workerProfileRef = workerProfileRef;
        entity.workspaceRef = workspaceRef;
        entity.modelConfigRef = modelConfigRef;
        entity.allowDefaultModelConfig = allowDefaultModelConfig;
        return entity;
    }

    public void activate(
            String executionId,
            String taskId,
            String effectiveToolScope,
            String effectivePermissionScope) {
        this.executionId = executionId;
        this.currentTaskId = taskId;
        this.effectiveToolScope = effectiveToolScope;
        this.effectivePermissionScope = effectivePermissionScope;
        this.bindingStatus = BindingStatus.ACTIVE;
        this.lastErrorCode = null;
    }

    public void startFailed(String errorCode, boolean outcomeUnknown) {
        this.bindingStatus = outcomeUnknown
                ? BindingStatus.START_OUTCOME_UNKNOWN
                : BindingStatus.START_FAILED;
        this.lastErrorCode = errorCode;
    }

    public void advanceTask(String requestId, String taskId) {
        this.lastTaskRequestId = requestId;
        this.currentTaskId = taskId;
        this.bindingStatus = BindingStatus.ACTIVE;
        this.lastErrorCode = null;
    }

    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public enum BindingStatus {
        STARTING,
        ACTIVE,
        START_FAILED,
        START_OUTCOME_UNKNOWN
    }
}
