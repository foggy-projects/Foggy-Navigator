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
public class AppConversationStartTask {

    /** Task ID returned by POST /app-conversations */
    private String id;

    /** Actual conversation ID, populated when status reaches READY */
    @JsonProperty("app_conversation_id")
    private String appConversationId;

    @JsonProperty("sandbox_id")
    private String sandboxId;

    @JsonProperty("agent_server_url")
    private String agentServerUrl;

    private String status;

    private String detail;
}
