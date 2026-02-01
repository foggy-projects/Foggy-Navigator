package com.foggy.navigator.coding.agent.git.model.v1;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AppConversationInfo {

    private String id;

    @JsonProperty("sandbox_id")
    private String sandboxId;

    @JsonProperty("sandbox_status")
    private String sandboxStatus;

    private String title;

    @JsonProperty("selected_repository")
    private String selectedRepository;

    @JsonProperty("selected_branch")
    private String selectedBranch;

    @JsonProperty("execution_status")
    private String executionStatus;

    @JsonProperty("conversation_url")
    private String conversationUrl;

    @JsonProperty("session_api_key")
    private String sessionApiKey;

    @JsonProperty("created_at")
    private String createdAt;

    @JsonProperty("updated_at")
    private String updatedAt;
}
