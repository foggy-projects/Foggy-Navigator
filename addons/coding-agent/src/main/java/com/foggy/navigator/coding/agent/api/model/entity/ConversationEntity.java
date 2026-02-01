package com.foggy.navigator.coding.agent.api.model.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "conversations")
public class ConversationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String conversationId;

    @Column(length = 64)
    private String sandboxId;

    @Column(length = 128)
    private String ohConversationId;

    @Column(nullable = false, length = 64)
    private String userId;

    @Column(nullable = false, length = 64)
    private String projectId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ConversationStatus status;

    @Column(length = 128)
    private String namespace;

    @Column(length = 512)
    private String gitRepoUrl;

    @Column(length = 64)
    private String branchName;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    public enum ConversationStatus {
        STARTING,
        WAITING_FOR_SANDBOX,
        PREPARING_REPOSITORY,
        READY,
        RUNNING,
        IDLE,
        PAUSED,
        ERROR,
        STOPPED
    }
}
