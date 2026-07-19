package com.foggy.navigator.sdk.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.foggy.navigator.sdk.internal.HttpHelper;

import java.util.Map;

/**
 * Read-only facade for the typed-management authentication namespace.
 *
 * <p>This facade deliberately accepts only an explicit principal credential
 * header. Its caller must construct the underlying {@link HttpHelper} with no
 * legacy/admin/control/runtime authentication fields or tenant context.</p>
 */
public final class ManagementAuthApi {

    public static final String BASE_PATH = "/api/v1/management/v1/auth";
    public static final String PRINCIPAL_CREDENTIAL_HEADER = "X-Navi-Principal-Credential";

    private final HttpHelper http;
    private final Map<String, String> principalCredentialHeader;

    public ManagementAuthApi(HttpHelper http, String principalCredential) {
        if (http == null) {
            throw new IllegalArgumentException("http helper is required");
        }
        if (principalCredential == null || principalCredential.isBlank()) {
            throw new IllegalArgumentException("typed-management principal credential is required");
        }
        this.http = http;
        this.principalCredentialHeader = Map.of(PRINCIPAL_CREDENTIAL_HEADER, principalCredential);
    }

    public Map<String, Object> whoami() {
        return http.get(BASE_PATH + "/whoami", principalCredentialHeader, new TypeReference<>() {});
    }

    public Map<String, Object> permissions() {
        return http.get(BASE_PATH + "/permissions", principalCredentialHeader, new TypeReference<>() {});
    }

    public Map<String, Object> explain(Map<String, Object> form) {
        return http.post(BASE_PATH + "/explain", form, principalCredentialHeader, new TypeReference<>() {});
    }
}
