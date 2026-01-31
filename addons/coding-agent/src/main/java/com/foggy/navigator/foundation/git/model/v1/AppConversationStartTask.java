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
public class AppConversationStartTask {

    @JsonProperty("conversation_id")
    private String conversationId;

    @JsonProperty("sandbox_id")
    private String sandboxId;

    private String status;

    @JsonProperty("error_message")
    private String errorMessage;
}
