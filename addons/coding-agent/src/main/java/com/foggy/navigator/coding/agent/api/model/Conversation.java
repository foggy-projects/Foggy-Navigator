package com.foggy.navigator.coding.agent.api.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Conversation {

    private String conversationId;

    private String sandboxId;

    private String ohConversationId;

    private String userId;

    private String projectId;

    private ConversationStatus status;

    private String namespace;

    private String gitRepoUrl;

    private String branchName;

    private LocalDateTime createdAt;

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
