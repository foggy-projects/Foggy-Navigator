package com.foggy.navigator.common.authorization;

/** Purpose is an exact token contract, never a broad bearer scope. */
public enum ManagementTokenPurpose {
    CONTROL_ACCESS,
    SECURITY_ACTION,
    UNKNOWN
}
