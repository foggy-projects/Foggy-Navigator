package com.foggy.navigator.session.lifecycle;

public final class LifecycleActivationReason {
    public static final String AUTHORITY_REQUIRED =
            "LIFECYCLE_SERVER_ACTIVATION_AUTHORITY_REQUIRED";
    public static final String ADMISSION_DISABLED =
            "LIFECYCLE_ACTIVATION_ADMISSION_DISABLED";
    public static final String CONTROL_DISABLED =
            "LIFECYCLE_ACTIVATION_CONTROL_DISABLED";
    public static final String CONTROL_UNAUTHORIZED =
            "LIFECYCLE_ACTIVATION_CONTROL_UNAUTHORIZED";
    public static final String LOCAL_DEVELOPMENT_TARGET_DISABLED =
            "LIFECYCLE_ACTIVATION_LOCAL_DEVELOPMENT_TARGET_DISABLED";
    public static final String TARGET_NOT_CONFIGURED =
            "LIFECYCLE_ACTIVATION_TARGET_NOT_CONFIGURED";
    public static final String TARGET_NOT_REGISTERED =
            "LIFECYCLE_ACTIVATION_TARGET_NOT_REGISTERED";
    public static final String TARGET_NOT_READY =
            "LIFECYCLE_ACTIVATION_TARGET_NOT_READY";
    public static final String TARGET_MISMATCH =
            "LIFECYCLE_ACTIVATION_TARGET_MISMATCH";
    public static final String TARGET_CONSUMED =
            "LIFECYCLE_ACTIVATION_ONE_SHOT_CONSUMED";
    public static final String MANIFEST_UNAVAILABLE =
            "LIFECYCLE_ACTIVATION_MANIFEST_UNAVAILABLE";
    public static final String MANIFEST_INVALID =
            "LIFECYCLE_ACTIVATION_MANIFEST_INVALID";
    public static final String MANIFEST_MISMATCH =
            "LIFECYCLE_ACTIVATION_MANIFEST_MISMATCH";
    public static final String DATABASE_MISMATCH =
            "LIFECYCLE_ACTIVATION_DATABASE_MISMATCH";
    public static final String CONTROLLER_INVENTORY_UNPROVEN =
            "LIFECYCLE_ACTIVATION_CONTROLLER_INVENTORY_UNPROVEN";
    public static final String CONTROLLER_DRIFT =
            "LIFECYCLE_ACTIVATION_CONTROLLER_DRIFT";
    public static final String CANDIDATE_MISMATCH =
            "LIFECYCLE_ACTIVATION_CANDIDATE_MISMATCH";
    public static final String GENERATION_NOT_ACTIVE =
            "LIFECYCLE_ACTIVATION_GENERATION_NOT_ACTIVE";
    public static final String INSTANCE_NOT_REGISTERED =
            "LIFECYCLE_ACTIVATION_INSTANCE_NOT_REGISTERED";
    public static final String PROOF_NOT_ACTIVE =
            "LIFECYCLE_ACTIVATION_PROOF_NOT_ACTIVE";
    public static final String RECEIPT_REQUIRED =
            "TERMINATION_REQUEST_RECEIPT_REQUIRED_FOR_CANARY";
    public static final String PROVIDER_NOT_ALLOWLISTED =
            "LIFECYCLE_CANARY_PROVIDER_NOT_ALLOWLISTED";
    public static final String EXACT_TUPLE_MISMATCH =
            "LIFECYCLE_CANARY_TUPLE_NOT_ALLOWLISTED";
    public static final String NEW_SESSION_REQUIRED =
            "LIFECYCLE_CANARY_NEW_SESSION_REQUIRED";
    public static final String STATIC_PROMPT_MISMATCH =
            "LIFECYCLE_CANARY_STATIC_PROMPT_MISMATCH";
    public static final String BUSINESS_ACCESS_FORBIDDEN =
            "LIFECYCLE_CANARY_BUSINESS_ACCESS_FORBIDDEN";
    public static final String WORKER_NOT_READY =
            "LIFECYCLE_ACTIVATION_WORKER_NOT_READY";
    public static final String WORKER_IDENTITY_MISMATCH =
            "LIFECYCLE_IDENTITY_FENCE_REJECTED";
    public static final String WORKER_CAPABILITY_MISMATCH =
            "LIFECYCLE_WORKER_CAPABILITY_MISMATCH";
    public static final String WORKER_PROTOCOL_MISMATCH =
            "LIFECYCLE_WORKER_PROTOCOL_MISMATCH";
    public static final String WORKER_BUILD_MISMATCH =
            "LIFECYCLE_WORKER_BUILD_MISMATCH";
    public static final String ADMISSION_BINDING_MISMATCH =
            "LIFECYCLE_ACTIVATION_ADMISSION_BINDING_MISMATCH";
    public static final String PROVIDER_EFFECT_ADMISSION_FAILED =
            "LIFECYCLE_ACTIVATION_PROVIDER_EFFECT_ADMISSION_FAILED";

    private LifecycleActivationReason() {
    }
}
