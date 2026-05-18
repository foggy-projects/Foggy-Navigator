package com.foggy.navigator.business.agent.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.business.agent.model.dto.UpstreamClientAppAdminPrincipal;
import com.foggy.navigator.business.agent.model.entity.UpstreamClientAppAdminCredentialEntity;
import com.foggy.navigator.business.agent.repository.UpstreamClientAppAdminCredentialRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class UpstreamClientAppAdminCredentialService {

    public static final String HEADER_ADMIN_KEY = "X-Navi-Admin-Key";
    public static final String HEADER_ADMIN_API_KEY = "X-Navi-Admin-Api-Key";
    public static final String SCOPE_CLIENT_APP_ADMIN_ALL = "CLIENT_APP_ADMIN_ALL";

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    private final UpstreamClientAppAdminCredentialRepository adminCredentialRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public UpstreamClientAppAdminPrincipal requireAccess(HttpServletRequest request, String requiredScope) {
        String adminApiKey = resolveAdminApiKey(request);
        if (!StringUtils.hasText(adminApiKey)) {
            throw new SecurityException("upstream admin credential is required");
        }

        UpstreamClientAppAdminCredentialEntity credential = adminCredentialRepository
                .findByCredentialKeyHash(SecretTokenSupport.sha256(adminApiKey))
                .orElseThrow(() -> new SecurityException("invalid upstream admin credential"));
        validateCredential(credential);

        Set<String> scopes = parseScopeSet(credential.getScopesJson());
        if (StringUtils.hasText(requiredScope) && !hasScope(scopes, requiredScope)) {
            throw new SecurityException("upstream admin credential lacks scope: " + requiredScope);
        }

        credential.setLastUsedAt(LocalDateTime.now());
        adminCredentialRepository.save(credential);

        return UpstreamClientAppAdminPrincipal.builder()
                .credentialId(credential.getCredentialId())
                .upstreamSystemId(credential.getUpstreamSystemId())
                .authorizedClientAppNamespace(credential.getAuthorizedClientAppNamespace())
                .authorizedTenantIds(parseStringSet(credential.getAuthorizedTenantIdsJson()))
                .scopes(scopes)
                .build();
    }

    public void requireTenant(UpstreamClientAppAdminPrincipal principal, String tenantId) {
        if (principal == null || !StringUtils.hasText(tenantId)
                || principal.getAuthorizedTenantIds() == null
                || !principal.getAuthorizedTenantIds().contains(tenantId)) {
            throw new SecurityException("upstream admin credential tenant mismatch");
        }
    }

    private String resolveAdminApiKey(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String value = request.getHeader(HEADER_ADMIN_KEY);
        if (StringUtils.hasText(value)) {
            return value;
        }
        return request.getHeader(HEADER_ADMIN_API_KEY);
    }

    private void validateCredential(UpstreamClientAppAdminCredentialEntity credential) {
        if (!UpstreamBootstrapRequestService.CREDENTIAL_STATUS_ACTIVE.equals(credential.getStatus())) {
            throw new SecurityException("upstream admin credential is not active");
        }
        if (credential.getRevokedAt() != null) {
            throw new SecurityException("upstream admin credential revoked");
        }
        if (credential.getExpiresAt() != null && !credential.getExpiresAt().isAfter(LocalDateTime.now())) {
            throw new SecurityException("upstream admin credential expired");
        }
    }

    private boolean hasScope(Set<String> scopes, String requiredScope) {
        return scopes.contains(SCOPE_CLIENT_APP_ADMIN_ALL) || scopes.contains(requiredScope);
    }

    private Set<String> parseStringSet(String json) {
        if (!StringUtils.hasText(json)) {
            return Set.of();
        }
        try {
            Set<String> result = new LinkedHashSet<>();
            for (String value : objectMapper.readValue(json, STRING_LIST)) {
                if (StringUtils.hasText(value)) {
                    result.add(value.trim());
                }
            }
            return result;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored upstream admin credential data is invalid", e);
        }
    }

    private Set<String> parseScopeSet(String json) {
        Set<String> values = parseStringSet(json);
        Set<String> result = new LinkedHashSet<>();
        for (String value : values) {
            String canonical = UpstreamBootstrapRequestService.canonicalizeScope(value);
            if (StringUtils.hasText(canonical)) {
                result.add(canonical);
            }
        }
        return result;
    }
}
