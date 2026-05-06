package com.foggy.navigator.business.agent.event;

import lombok.Builder;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

@Getter
public class BusinessSuspensionResumeDecisionEvent extends ApplicationEvent {

    private final String taskId;
    private final String workerTaskId;
    private final String sessionId;
    private final String workerSessionId;
    private final String suspendId;
    private final String suspensionType;
    private final String decisionStatus;
    private final String comment;
    private final String tenantId;
    private final String clientAppId;
    private final String upstreamUserId;
    private final String functionId;
    private final String version;
    private final String inputHash;

    @Builder
    public BusinessSuspensionResumeDecisionEvent(Object source,
                                                 String taskId,
                                                 String workerTaskId,
                                                 String sessionId,
                                                 String workerSessionId,
                                                 String suspendId,
                                                 String suspensionType,
                                                 String decisionStatus,
                                                 String comment,
                                                 String tenantId,
                                                 String clientAppId,
                                                 String upstreamUserId,
                                                 String functionId,
                                                 String version,
                                                 String inputHash) {
        super(source);
        this.taskId = taskId;
        this.workerTaskId = workerTaskId;
        this.sessionId = sessionId;
        this.workerSessionId = workerSessionId;
        this.suspendId = suspendId;
        this.suspensionType = suspensionType;
        this.decisionStatus = decisionStatus;
        this.comment = comment;
        this.tenantId = tenantId;
        this.clientAppId = clientAppId;
        this.upstreamUserId = upstreamUserId;
        this.functionId = functionId;
        this.version = version;
        this.inputHash = inputHash;
    }
}
