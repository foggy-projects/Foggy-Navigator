package com.foggy.navigator.langgraph.worker.model.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Approval audit record for LangGraph Biz Worker tasks.
 * <p>
 * Java side is responsible for all approval record persistence (Doc 31 §16.4).
 * Worker only fires events and suspends frames — "管杀不管埋".
 */
@Data
@Entity
@Table(name = "langgraph_approvals", indexes = {
        @Index(name = "idx_lga_task_id", columnList = "taskId"),
        @Index(name = "idx_lga_session_id", columnList = "sessionId")
})
public class LanggraphApprovalEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 64, nullable = false)
    private String taskId;

    @Column(length = 64, nullable = false)
    private String sessionId;

    @Column(length = 64, nullable = false)
    private String userId;

    /** Approval type identifier from Skill (e.g. "manual_dispatch") */
    @Column(length = 64, nullable = false)
    private String approvalType;

    /** Human-readable summary of what needs approval */
    @Column(columnDefinition = "TEXT")
    private String summary;

    /** Business payload attached to the approval request (JSON) */
    @Column(columnDefinition = "TEXT")
    private String payload;

    /** Status: PENDING, APPROVED, REJECTED */
    @Column(length = 32, nullable = false)
    private String status;

    /** Result after approval decision */
    @Column(length = 32)
    private String approvalResult;

    /** Reviewer's comment */
    @Column(columnDefinition = "TEXT")
    private String comment;

    /** Who approved/rejected */
    @Column(length = 64)
    private String reviewedBy;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    private LocalDateTime reviewedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
