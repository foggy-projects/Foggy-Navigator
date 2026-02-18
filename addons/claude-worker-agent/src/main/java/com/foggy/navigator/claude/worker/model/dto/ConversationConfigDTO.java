package com.foggy.navigator.claude.worker.model.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会话配置 DTO（不含 authToken）
 */
@Data
@Builder
public class ConversationConfigDTO {
    private String sessionId;
    private boolean pinned;
    private LocalDateTime pinnedAt;
    private String customTitle;
    private String authMode;
    private boolean authBound;
    private String baseUrl;
}
