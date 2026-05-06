package com.foggy.navigator.common.event;

import org.springframework.context.ApplicationEvent;
import lombok.Builder;
import lombok.Getter;

@Getter
public class WorkerGatewayResumeEvent extends ApplicationEvent {

    private final String taskId;
    private final String sessionId;
    private final String businessSessionId;
    private final String suspendId;
    private final String approvalResult;
    private final String comment;

    // Binding Context
    private final String tenantId;
    private final String clientAppId;
    private final String upstreamUserId;
    private final String functionId;
    private final String inputHash;

    @Builder
    public WorkerGatewayResumeEvent(Object source, String taskId, String sessionId, String businessSessionId, String suspendId, String approvalResult, String comment,
                                    String tenantId, String clientAppId, String upstreamUserId, String functionId, String inputHash) {
        super(source);
        this.taskId = taskId;
        this.sessionId = sessionId;
        this.businessSessionId = businessSessionId;
        this.suspendId = suspendId;
        this.approvalResult = approvalResult;
        this.comment = comment;
        this.tenantId = tenantId;
        this.clientAppId = clientAppId;
        this.upstreamUserId = upstreamUserId;
        this.functionId = functionId;
        this.inputHash = inputHash;
    }
}
