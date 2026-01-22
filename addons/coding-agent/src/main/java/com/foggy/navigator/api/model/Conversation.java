package com.foggy.navigator.api.model;

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
        READY,
        RUNNING,
        IDLE,
        ERROR,
        STOPPED
    }
}
