package com.foggy.navigator.agent.framework.router;

import com.foggy.navigator.agent.framework.session.Message;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 分派请求
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DelegationRequest {
    private String sourceSessionId;
    private String sourceAgentId;
    private String targetAgentId;
    private String userId;
    private String tenantId;
    private String intent;
    private List<Message> contextMessages;
    private Map<String, Object> parameters;
}
