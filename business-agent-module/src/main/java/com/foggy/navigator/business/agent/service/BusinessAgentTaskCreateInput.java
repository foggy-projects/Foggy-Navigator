package com.foggy.navigator.business.agent.service;

import com.foggy.navigator.business.agent.model.form.CreateBusinessAgentTaskForm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Immutable, process-local snapshot of one Business Task create form.
 *
 * <p>The opaque client context is intentionally retained only so the fresh transaction can bind
 * the same request content after receipt admission. It must never be logged or persisted as
 * command evidence.</p>
 */
record BusinessAgentTaskCreateInput(
        String clientAppId,
        String sessionId,
        String contextId,
        String upstreamUserId,
        String agentId,
        String skillId,
        String skillName,
        String workerPoolId,
        String requestedModelConfigId,
        String modelVariant,
        String directoryId,
        String resumeFromTaskId,
        String clientContextJson,
        String workdir,
        List<String> allowedDirs,
        List<String> allowedTools) {

    BusinessAgentTaskCreateInput {
        allowedDirs = immutableCopyPreservingNulls(allowedDirs);
        allowedTools = immutableCopyPreservingNulls(allowedTools);
    }

    static BusinessAgentTaskCreateInput snapshot(CreateBusinessAgentTaskForm form) {
        if (form == null) {
            throw new IllegalArgumentException("form is required");
        }
        return new BusinessAgentTaskCreateInput(
                form.getClientAppId(),
                form.getSessionId(),
                form.getContextId(),
                form.getUpstreamUserId(),
                form.getAgentId(),
                form.getSkillId(),
                form.getSkillName(),
                form.getWorkerPoolId(),
                form.getRequestedModelConfigId(),
                form.getModelVariant(),
                form.getDirectoryId(),
                form.getResumeFromTaskId(),
                form.getClientContextJson(),
                form.getWorkdir(),
                form.getAllowedDirs(),
                form.getAllowedTools());
    }

    CreateBusinessAgentTaskForm toForm() {
        CreateBusinessAgentTaskForm form = new CreateBusinessAgentTaskForm();
        form.setClientAppId(clientAppId);
        form.setSessionId(sessionId);
        form.setContextId(contextId);
        form.setUpstreamUserId(upstreamUserId);
        form.setAgentId(agentId);
        form.setSkillId(skillId);
        form.setSkillName(skillName);
        form.setWorkerPoolId(workerPoolId);
        form.setRequestedModelConfigId(requestedModelConfigId);
        form.setModelVariant(modelVariant);
        form.setDirectoryId(directoryId);
        form.setResumeFromTaskId(resumeFromTaskId);
        form.setClientContextJson(clientContextJson);
        form.setWorkdir(workdir);
        form.setAllowedDirs(mutableCopy(allowedDirs));
        form.setAllowedTools(mutableCopy(allowedTools));
        return form;
    }

    @Override
    public String toString() {
        return "BusinessAgentTaskCreateInput[clientAppId=" + clientAppId
                + ", sessionId=" + sessionId
                + ", agentId=" + agentId
                + ", requestContent=REDACTED]";
    }

    private static List<String> immutableCopyPreservingNulls(List<String> values) {
        if (values == null) {
            return null;
        }
        return Collections.unmodifiableList(new ArrayList<>(values));
    }

    private static List<String> mutableCopy(List<String> values) {
        return values == null ? null : new ArrayList<>(values);
    }
}

record BusinessAgentTaskPreparedFreshCreate(
        BusinessAgentTaskCreatePlan plan,
        BusinessAgentTaskCreateInput input) {

    BusinessAgentTaskPreparedFreshCreate {
        Objects.requireNonNull(plan, "plan must not be null");
        Objects.requireNonNull(input, "input must not be null");
    }

    @Override
    public String toString() {
        return "BusinessAgentTaskPreparedFreshCreate[semanticFingerprint="
                + plan.semanticFingerprint() + ", requestContent=REDACTED]";
    }
}
