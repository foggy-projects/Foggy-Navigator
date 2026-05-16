package com.foggy.navigator.session.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class SessionConfigDTO {

    private String sessionId;
    private boolean pinned;
    private LocalDateTime pinnedAt;
    private String customTitle;
    private String authMode;
    private boolean authBound;
    private String authModelConfigId;
    private String baseUrl;
    private String maskedAuthToken;
    private List<String> tags;
    private String interactionState;
    private String milestoneId;
}
