package com.foggy.navigator.business.agent.model.form;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import java.util.regex.Pattern;

/**
 * Restricted-reference-only non-binding preflight input. The endpoint service
 * canonicalizes and hashes references; client correlation identifiers,
 * precomputed digests and raw target/impact/reason material are not accepted.
 */
public final class ManagementAuthorizationExplainForm {

    private static final int MAX_ROUTE_ID_LENGTH = 320;
    private static final int MAX_ACTION_ID_LENGTH = 160;
    private static final Pattern ROUTE_ID = Pattern.compile("mvc(?::observer-bff)?:[a-z]+:/[a-zA-Z0-9_./{}-]+$");
    private static final Pattern ACTION_ID = Pattern.compile("[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*");
    private static final int MAX_REFERENCE_LENGTH = 512;
    private static final Pattern REFERENCE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/@#{}-]*");

    private String routeId;
    private String actionId;
    private String targetReference;
    private String impactReference;
    private String reasonReference;

    public String getRouteId() {
        return routeId;
    }

    public void setRouteId(String routeId) {
        this.routeId = routeId;
    }

    public String getActionId() {
        return actionId;
    }

    public void setActionId(String actionId) {
        this.actionId = actionId;
    }

    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    public String getTargetReference() {
        return targetReference;
    }

    public void setTargetReference(String targetReference) {
        this.targetReference = targetReference;
    }

    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    public String getImpactReference() {
        return impactReference;
    }

    public void setImpactReference(String impactReference) {
        this.impactReference = impactReference;
    }

    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    public String getReasonReference() {
        return reasonReference;
    }

    public void setReasonReference(String reasonReference) {
        this.reasonReference = reasonReference;
    }

    @JsonSetter("correlationId")
    public void rejectClientCorrelationId(Object ignored) {
        throw new IllegalArgumentException("client correlationId is not accepted");
    }

    @JsonSetter("targetDigest")
    public void rejectClientTargetDigest(Object ignored) {
        throw new IllegalArgumentException("client management action digests are not accepted");
    }

    @JsonSetter("impactDigest")
    public void rejectClientImpactDigest(Object ignored) {
        throw new IllegalArgumentException("client management action digests are not accepted");
    }

    @JsonSetter("reasonDigest")
    public void rejectClientReasonDigest(Object ignored) {
        throw new IllegalArgumentException("client management action digests are not accepted");
    }

    @JsonAnySetter
    public void rejectUnknownField(String ignoredName, Object ignoredValue) {
        throw new IllegalArgumentException("unsupported management authorization preflight input");
    }

    public CanonicalExplainInput toCanonicalExplainInput() {
        return new CanonicalExplainInput(
                requireRouteId(routeId),
                requireActionId(actionId),
                optionalReference(targetReference),
                optionalReference(impactReference),
                optionalReference(reasonReference));
    }

    private static String requireRouteId(String value) {
        if (value == null || value.isBlank() || value.length() > MAX_ROUTE_ID_LENGTH
                || !ROUTE_ID.matcher(value).matches()) {
            throw new IllegalArgumentException("routeId must be a canonical registered route identifier");
        }
        return value;
    }

    private static String requireActionId(String value) {
        if (value == null || value.isBlank() || value.length() > MAX_ACTION_ID_LENGTH
                || !ACTION_ID.matcher(value).matches()) {
            throw new IllegalArgumentException("actionId must be a stable canonical action identifier");
        }
        return value;
    }

    private static String optionalReference(String value) {
        if (!hasText(value)) {
            return null;
        }
        if (value.length() > MAX_REFERENCE_LENGTH || !value.equals(value.trim()) || !REFERENCE.matcher(value).matches()) {
            throw new IllegalArgumentException("management action reference is invalid");
        }
        return value;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    @Override
    public String toString() {
        return "ManagementAuthorizationExplainForm[routeId=" + routeId
                + ", actionId=" + actionId
                + ", targetReference=[redacted]"
                + ", impactReference=[redacted]"
                + ", reasonReference=[redacted]]";
    }

    /** Validated preflight input; canonical hashing happens in the endpoint service. */
    public static final class CanonicalExplainInput {

        private final String routeId;
        private final String actionId;
        private final String targetReference;
        private final String impactReference;
        private final String reasonReference;

        private CanonicalExplainInput(
                String routeId,
                String actionId,
                String targetReference,
                String impactReference,
                String reasonReference
        ) {
            if ((targetReference == null || impactReference == null || reasonReference == null)
                    && (targetReference != null || impactReference != null || reasonReference != null)) {
                throw new IllegalArgumentException("management action references must be supplied together");
            }
            this.routeId = routeId;
            this.actionId = actionId;
            this.targetReference = targetReference;
            this.impactReference = impactReference;
            this.reasonReference = reasonReference;
        }

        public String routeId() {
            return routeId;
        }

        public String actionId() {
            return actionId;
        }

        public boolean hasActionBinding() {
            return targetReference != null;
        }

        public String targetReference() {
            return targetReference;
        }

        public String impactReference() {
            return impactReference;
        }

        public String reasonReference() {
            return reasonReference;
        }

        @Override
        public String toString() {
            return "CanonicalExplainInput[routeId=" + routeId + ", actionId=" + actionId
                    + ", targetReference=[redacted], impactReference=[redacted], reasonReference=[redacted]]";
        }
    }
}
