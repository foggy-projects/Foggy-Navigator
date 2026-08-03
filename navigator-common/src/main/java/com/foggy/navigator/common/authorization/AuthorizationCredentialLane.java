package com.foggy.navigator.common.authorization;

/** Credential lanes remain deliberately separate; no lane is an authority inheritance shortcut. */
public enum AuthorizationCredentialLane {
    NAVIGATOR_JWT,
    NAVIGATOR_API_KEY,
    LEGACY_UPSTREAM_ADMIN,
    CLIENT_APP_CONTROL,
    CLIENT_APP_RUNTIME_CREDENTIAL,
    CLIENT_APP_RUNTIME_ACCESS,
    TASK_SCOPED_TOKEN,
    WORKER_CREDENTIAL,
    SHARING_KEY_CAPABILITY,
    INSTANCE_ROOT_CONTROL,
    INSTANCE_ROOT_SECURITY,
    SAAS_PROVISIONING,
    SAAS_SECURITY_ADMIN,
    UNKNOWN
}
