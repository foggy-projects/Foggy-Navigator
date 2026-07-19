package com.foggy.navigator.business.agent.model.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.foggy.navigator.common.authorization.AuthorizationReasonCode;
import com.foggy.navigator.common.authorization.ManagementAuthorizationExplanation;
import com.foggy.navigator.common.authorization.PolicyDecisionV1;

import java.time.Instant;

/** Public-safe, non-binding authorization preflight result. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class ManagementAuthorizationExplainResponseDTO {

    private final boolean allowed;
    private final AuthorizationReasonCode reasonCode;
    private final boolean nonBinding;
    private final String actionId;
    private final String schemaVersion;
    private final String policyVersion;
    private final String actionCatalogVersion;
    private final String serverBuild;
    private final String decisionId;
    private final String correlationId;
    private final Instant evaluatedAt;

    private ManagementAuthorizationExplainResponseDTO(
            boolean allowed,
            AuthorizationReasonCode reasonCode,
            boolean nonBinding,
            String actionId,
            String schemaVersion,
            String policyVersion,
            String actionCatalogVersion,
            String serverBuild,
            String decisionId,
            String correlationId,
            Instant evaluatedAt
    ) {
        this.allowed = allowed;
        this.reasonCode = reasonCode;
        this.nonBinding = nonBinding;
        this.actionId = actionId;
        this.schemaVersion = schemaVersion;
        this.policyVersion = policyVersion;
        this.actionCatalogVersion = actionCatalogVersion;
        this.serverBuild = serverBuild;
        this.decisionId = decisionId;
        this.correlationId = correlationId;
        this.evaluatedAt = evaluatedAt;
    }

    public static ManagementAuthorizationExplainResponseDTO from(ManagementAuthorizationExplanation explanation) {
        if (explanation == null || explanation.decision() == null) {
            throw new IllegalArgumentException("management authorization explanation is required");
        }
        PolicyDecisionV1 decision = explanation.decision();
        return new ManagementAuthorizationExplainResponseDTO(
                explanation.allowed(),
                explanation.reasonCode(),
                explanation.nonBinding(),
                decision.actionId(),
                decision.schemaVersion(),
                decision.policyVersion(),
                decision.actionCatalogVersion(),
                decision.serverBuild(),
                decision.decisionId(),
                decision.correlationId(),
                decision.evaluatedAt());
    }

    public boolean isAllowed() {
        return allowed;
    }

    public AuthorizationReasonCode getReasonCode() {
        return reasonCode;
    }

    public boolean isNonBinding() {
        return nonBinding;
    }

    public String getActionId() {
        return actionId;
    }

    public String getSchemaVersion() {
        return schemaVersion;
    }

    public String getPolicyVersion() {
        return policyVersion;
    }

    public String getActionCatalogVersion() {
        return actionCatalogVersion;
    }

    public String getServerBuild() {
        return serverBuild;
    }

    public String getDecisionId() {
        return decisionId;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public Instant getEvaluatedAt() {
        return evaluatedAt;
    }
}
