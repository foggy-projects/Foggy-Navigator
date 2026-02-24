package com.foggy.navigator.spi.config;

import com.foggy.navigator.common.dto.ApiCredentialDTO;
import com.foggy.navigator.common.form.ApiCredentialForm;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface ApiCredentialManager {

    String saveCredential(String tenantId, ApiCredentialForm form);

    void updateCredential(String id, ApiCredentialForm form);

    void deleteCredential(String id);

    List<ApiCredentialDTO> listCredentials(String tenantId);

    Optional<ApiCredentialDTO> getCredential(String id);

    List<ApiCredentialDTO> listCredentialsByCategory(String tenantId, String category);

    Optional<ApiCredentialDTO> getCredentialByName(String tenantId, String name);

    String getDecryptedApiKey(String credentialId);

    Map<String, String> getExtraHeaders(String credentialId);

    boolean hasAnyCredential(String tenantId);

    String testConnection(String baseUrl, String apiKey, String authType, String authHeaderName);
}
