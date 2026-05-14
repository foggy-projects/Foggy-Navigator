package com.foggy.navigator.business.agent.model.dto;

import lombok.Data;

import java.util.List;

@Data
public class BusinessAgentSessionMessagesDTO {
    private String contextId;
    private String sessionId;
    private List<BusinessAgentSessionMessageDTO> messages;
    private String nextCursor;
    private boolean hasMore;
}
