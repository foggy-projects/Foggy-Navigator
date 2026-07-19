package com.foggy.navigator.business.agent.model.form;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.foggy.navigator.common.authorization.OpaqueSecretMaterial;

import java.util.regex.Pattern;

/**
 * Input for a single, exact management security action authorization.
 *
 * <p>The client supplies restricted references, never precomputed digests or
 * raw target/impact/reason material. The endpoint service canonicalizes those
 * references and produces their digests server-side. Proofs are converted to
 * redacted in-memory material on deserialization and are never exposed through
 * a getter or response DTO.</p>
 */
public final class ManagementSecurityActionAuthorizeForm {

    private static final int MAX_ACTION_ID_LENGTH = 160;
    private static final int MAX_REFERENCE_LENGTH = 512;
    private static final int MAX_PROOF_LENGTH = 16_384;
    private static final Pattern ACTION_ID = Pattern.compile("[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*");
    private static final Pattern REFERENCE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/@#{}-]*");

    private String actionId;
    private String targetReference;
    private String impactReference;
    private String reasonReference;
    private OpaqueSecretMaterial stepUpProof;
    private OpaqueSecretMaterial approvalProof;

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

    @JsonSetter("stepUpProof")
    public void setStepUpProof(String stepUpProof) {
        requireProof(stepUpProof);
        this.stepUpProof = OpaqueSecretMaterial.of(stepUpProof);
    }

    @JsonSetter("approvalProof")
    public void setApprovalProof(String approvalProof) {
        requireProof(approvalProof);
        this.approvalProof = OpaqueSecretMaterial.of(approvalProof);
    }

    /**
     * Client assertions are never trusted in place of verifier-owned step-up
     * or approval evidence.
     */
    @JsonSetter("stepUpSatisfied")
    public void rejectClientStepUpSatisfied(Object ignored) {
        throw new IllegalArgumentException("client step-up assertions are not accepted");
    }

    @JsonSetter("approvalSatisfied")
    public void rejectClientApprovalSatisfied(Object ignored) {
        throw new IllegalArgumentException("client approval assertions are not accepted");
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
        throw new IllegalArgumentException("unsupported management security authorization input");
    }

    public CanonicalActionInput toCanonicalActionInput() {
        return new CanonicalActionInput(
                requireActionId(actionId),
                requireReference(targetReference),
                requireReference(impactReference),
                requireReference(reasonReference),
                requireProofMaterial(stepUpProof),
                requireProofMaterial(approvalProof));
    }

    private static String requireActionId(String value) {
        if (value == null || value.isBlank() || value.length() > MAX_ACTION_ID_LENGTH
                || !ACTION_ID.matcher(value).matches()) {
            throw new IllegalArgumentException("actionId must be a stable canonical action identifier");
        }
        return value;
    }

    private static String requireReference(String value) {
        if (value == null || value.isBlank() || value.length() > MAX_REFERENCE_LENGTH
                || !value.equals(value.trim()) || !REFERENCE.matcher(value).matches()) {
            throw new IllegalArgumentException("management action reference is invalid");
        }
        return value;
    }

    private static void requireProof(String value) {
        if (value == null || value.isBlank() || value.length() > MAX_PROOF_LENGTH) {
            throw new IllegalArgumentException("management security proof is required");
        }
    }

    private static OpaqueSecretMaterial requireProofMaterial(OpaqueSecretMaterial value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("management security proof is required");
        }
        return value;
    }

    @Override
    public String toString() {
        return "ManagementSecurityActionAuthorizeForm[actionId=" + actionId
                + ", targetReference=[redacted]"
                + ", impactReference=[redacted]"
                + ", reasonReference=[redacted]"
                + ", stepUpProof=[redacted], approvalProof=[redacted]]";
    }

    /**
     * Validated, request-local input. It remains deliberately non-serializable
     * and redacts references in diagnostics.
     */
    public static final class CanonicalActionInput {

        private final String actionId;
        private final String targetReference;
        private final String impactReference;
        private final String reasonReference;
        private final OpaqueSecretMaterial stepUpProof;
        private final OpaqueSecretMaterial approvalProof;

        private CanonicalActionInput(
                String actionId,
                String targetReference,
                String impactReference,
                String reasonReference,
                OpaqueSecretMaterial stepUpProof,
                OpaqueSecretMaterial approvalProof
        ) {
            this.actionId = actionId;
            this.targetReference = targetReference;
            this.impactReference = impactReference;
            this.reasonReference = reasonReference;
            this.stepUpProof = stepUpProof;
            this.approvalProof = approvalProof;
        }

        public String actionId() {
            return actionId;
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

        public OpaqueSecretMaterial stepUpProof() {
            return stepUpProof;
        }

        public OpaqueSecretMaterial approvalProof() {
            return approvalProof;
        }

        @Override
        public String toString() {
            return "CanonicalActionInput[actionId=" + actionId
                    + ", targetReference=[redacted], impactReference=[redacted], reasonReference=[redacted]"
                    + ", stepUpProof=[redacted], approvalProof=[redacted]]";
        }
    }
}
