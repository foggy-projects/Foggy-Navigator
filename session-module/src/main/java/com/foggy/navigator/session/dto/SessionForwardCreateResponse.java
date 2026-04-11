package com.foggy.navigator.session.dto;

import com.foggy.navigator.common.dto.DispatchTaskDTO;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class SessionForwardCreateResponse {

    private Long relationId;
    private String targetMode;
    private String sourceSessionId;
    private String sourceMessageId;
    private String targetSessionId;
    private DispatchTaskDTO task;
}
