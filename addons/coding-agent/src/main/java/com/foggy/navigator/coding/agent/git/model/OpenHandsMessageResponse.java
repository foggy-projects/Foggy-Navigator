package com.foggy.navigator.coding.agent.git.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OpenHandsMessageResponse {

    private String message_id;

    private String conversation_id;

    private String content;

    private String timestamp;
}
