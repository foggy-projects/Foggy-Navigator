package com.foggy.navigator.foundation.git.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OpenHandsConversationResponse {

    private String conversation_id;

    private String sandbox_id;

    private String status;

    private String namespace;
}
