package com.foggy.navigator.common.event;

import org.springframework.context.ApplicationEvent;
import lombok.Builder;
import lombok.Getter;

@Getter
public class WorkerGatewayResumeEvent extends ApplicationEvent {

    /**
     * Worker conversation resume notification.
     *
     * <p>This event tells a Worker runtime that a suspension decision happened so it
     * can update conversation state and continue natural-language generation. It is
     * not the owner of approved business function side effects; those are executed
     * by the Java Business Function Suspension service from the original persisted
     * suspension context.
     */
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
