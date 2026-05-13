package com.foggy.navigator.business.agent.model.dto;

import com.foggy.navigator.common.entity.SessionMessageEntity;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class BusinessAgentSessionMessageDTO {
    private String messageId;
    private String contextId;
    private String sessionId;
    private String taskId;
    private String role;
    private String content;
    private String metadata;
    private LocalDateTime createdAt;

    public static BusinessAgentSessionMessageDTO fromEntity(SessionMessageEntity entity, String contextId) {
        if (entity == null) {
            return null;
        }
        BusinessAgentSessionMessageDTO dto = new BusinessAgentSessionMessageDTO();
        dto.setMessageId(entity.getId());
        dto.setContextId(contextId);
        dto.setSessionId(entity.getSessionId());
        dto.setTaskId(entity.getTaskId());
        dto.setRole(entity.getRole());
        dto.setContent(entity.getContent());
        dto.setMetadata(entity.getMetadata());
        dto.setCreatedAt(entity.getCreatedAt());
        return dto;
    }
}
