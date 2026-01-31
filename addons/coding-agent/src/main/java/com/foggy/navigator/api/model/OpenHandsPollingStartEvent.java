package com.foggy.navigator.api.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class OpenHandsPollingStartEvent {
    private String conversationId;
    private String userId;
    private String ohConversationId;
}
