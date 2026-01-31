package com.foggy.navigator.foundation.git.model.v1;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AppConversationStartRequest {

    @JsonProperty("conversation_id")
    private String conversationId;

    @JsonProperty("initial_message")
    private String initialMessage;

    @JsonProperty("llm_model")
    private String llmModel;

    @JsonProperty("selected_repository")
    private String selectedRepository;

    @JsonProperty("selected_branch")
    private String selectedBranch;

    @JsonProperty("git_provider")
    private String gitProvider;

    private String title;

    @JsonProperty("agent_type")
    private String agentType;
}
