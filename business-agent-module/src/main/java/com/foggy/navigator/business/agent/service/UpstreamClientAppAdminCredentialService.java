package com.foggy.navigator.business.agent.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.business.agent.model.dto.UpstreamAdminCredentialDTO;
import com.foggy.navigator.business.agent.model.dto.UpstreamClientAppAdminPrincipal;
import com.foggy.navigator.business.agent.model.entity.UpstreamClientAppAdminCredentialEntity;
import com.foggy.navigator.business.agent.repository.UpstreamClientAppAdminCredentialRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class UpstreamClientAppAdminCredentialService {

    public static final String HEADER_ADMIN_KEY = "X-Navi-Admin-Key";
    public static final String SCOPE_CLIENT_APP_ADMIN_ALL = "CLIENT_APP_ADMIN_ALL";

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    private final UpstreamClientAppAdminCredentialRepository adminCredentialRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public UpstreamClientAppAdminPrincipal requireAccess(HttpServletRequest request, String requiredScope) {
        String primaryHeaderValue = request == null ? null : request.getHeader(HEADER_ADMIN_KEY);
        boolean primaryHeaderPresent = StringUtils.hasText(primaryHeaderValue);
        String adminApiKey = primaryHeaderValue;
        if (!StringUtils.hasText(adminApiKey)) {
            log.warn("Upstream admin credential missing: method={}, path={}, remoteAddr={}, requiredScope={}, {}Present={}",
                    requestMethod(request), requestUri(request), remoteAddr(request), requiredScope,
                    HEADER_ADMIN_KEY, primaryHeaderPresent);
            throw new SecurityException("upstream admin credential is required");
        }

        String credentialKeyHash = SecretTokenSupport.sha256(adminApiKey);
        UpstreamClientAppAdminCredentialEntity credential = adminCredentialRepository
                .findByCredentialKeyHash(credentialKeyHash)
                .orElseThrow(() -> {
                    log.warn("Upstream admin credential not found: method={}, path={}, remoteAddr={}, requiredScope={}, header={}, keyHashPrefix={}",
                            requestMethod(request), requestUri(request), remoteAddr(request), requiredScope,
                            HEADER_ADMIN_KEY, hashPrefix(credentialKeyHash));
                    return new SecurityException("invalid upstream admin credential");
                });
        String invalidMessage = invalidCredentialMessage(credential);
        if (invalidMessage != null) {
            log.warn("Upstream admin credential rejected: method={}, path={}, remoteAddr={}, requiredScope={}, credentialId={}, upstreamSystemId={}, status={}, revokedAt={}, expiresAt={}, reason={}",
                    requestMethod(request), requestUri(request), remoteAddr(request), requiredScope,
                    credential.getCredentialId(), credential.getUpstreamSystemId(), credential.getStatus(),
                    credential.getRevokedAt(), credential.getExpiresAt(), invalidMessage);
            throw new SecurityException(invalidMessage);
        }

        Set<String> scopes = parseScopeSet(credential.getScopesJson());
        if (StringUtils.hasText(requiredScope) && !hasScope(scopes, requiredScope)) {
            log.warn("Upstream admin credential lacks scope: method={}, path={}, remoteAddr={}, requiredScope={}, credentialId={}, upstreamSystemId={}, scopes={}",
                    requestMethod(request), requestUri(request), remoteAddr(request), requiredScope,
                    credential.getCredentialId(), credential.getUpstreamSystemId(), scopes);
            throw new SecurityException("upstream admin credential lacks scope: " + requiredScope);
        }

        credential.setLastUsedAt(LocalDateTime.now());
        adminCredentialRepository.save(credential);

        return UpstreamClientAppAdminPrincipal.builder()
                .credentialId(credential.getCredentialId())
                .principalId(credential.getUpstreamSystemId())
                .upstreamSystemId(credential.getUpstreamSystemId())
                .authorizedClientAppNamespace(credential.getAuthorizedClientAppNamespace())
                .authorizedTenantIds(parseStringSet(credential.getAuthorizedTenantIdsJson()))
                .scopes(scopes)
                .build();
    }

    @Transactional
    public UpstreamAdminCredentialDTO inspectCurrentCredential(HttpServletRequest request) {
        String adminApiKey = request == null ? null : request.getHeader(HEADER_ADMIN_KEY);
        if (!StringUtils.hasText(adminApiKey)) {
            throw new SecurityException("upstream admin credential is required");
        }
        String credentialKeyHash = SecretTokenSupport.sha256(adminApiKey);
        UpstreamClientAppAdminCredentialEntity credential = adminCredentialRepository
                .findByCredentialKeyHash(credentialKeyHash)
                .orElseThrow(() -> new SecurityException("invalid upstream admin credential"));
        String invalidMessage = invalidCredentialMessage(credential);
        if (invalidMessage != null) {
            throw new SecurityException(invalidMessage);
        }
        credential.setLastUsedAt(LocalDateTime.now());
        adminCredentialRepository.save(credential);
        UpstreamAdminCredentialDTO dto = new UpstreamAdminCredentialDTO();
        dto.setCredentialId(credential.getCredentialId());
        dto.setPrincipalId(credential.getUpstreamSystemId());
        dto.setCredentialKeyPrefix(credential.getCredentialKeyPrefix());
        dto.setCredentialKeySuffix(credential.getCredentialKeySuffix());
        dto.setUpstreamSystemId(credential.getUpstreamSystemId());
        dto.setAuthorizedTenantIds(parseStringSet(credential.getAuthorizedTenantIdsJson()).stream().toList());
        dto.setAuthorizedClientAppNamespace(credential.getAuthorizedClientAppNamespace());
        dto.setScopes(parseScopeSet(credential.getScopesJson()).stream().toList());
        dto.setStatus(credential.getStatus());
        dto.setExpiresAt(credential.getExpiresAt());
        dto.setRevokedAt(credential.getRevokedAt());
        dto.setLastUsedAt(credential.getLastUsedAt());
        dto.setSourceRequestId(credential.getSourceRequestId());
        dto.setCreatedAt(credential.getCreatedAt());
        dto.setUpdatedAt(credential.getUpdatedAt());
        return dto;
    }

    public void requireTenant(UpstreamClientAppAdminPrincipal principal, String tenantId) {
        if (principal == null || !StringUtils.hasText(tenantId)
                || principal.getAuthorizedTenantIds() == null
                || !principal.getAuthorizedTenantIds().contains(tenantId)) {
            throw new SecurityException("upstream admin credential tenant mismatch");
        }
    }

    public void requireScope(UpstreamClientAppAdminPrincipal principal, String requiredScope) {
        if (principal == null || !StringUtils.hasText(requiredScope)) {
            throw new SecurityException("upstream admin credential is required");
        }
        String canonical = UpstreamBootstrapRequestService.canonicalizeScope(requiredScope);
        Set<String> scopes = canonicalScopeSet(principal.getScopes());
        if (!StringUtils.hasText(canonical) || !hasScope(scopes, canonical)) {
            throw new SecurityException("upstream admin credential lacks scope: " + requiredScope);
        }
    }

    private String invalidCredentialMessage(UpstreamClientAppAdminCredentialEntity credential) {
        if (!UpstreamBootstrapRequestService.CREDENTIAL_STATUS_ACTIVE.equals(credential.getStatus())) {
            return "upstream admin credential is not active";
        }
        if (credential.getRevokedAt() != null) {
            return "upstream admin credential revoked";
        }
        if (credential.getExpiresAt() != null && !credential.getExpiresAt().isAfter(LocalDateTime.now())) {
            return "upstream admin credential expired";
        }
        return null;
    }

    private String requestMethod(HttpServletRequest request) {
        return request == null ? null : request.getMethod();
    }

    private String requestUri(HttpServletRequest request) {
        return request == null ? null : request.getRequestURI();
    }

    private String remoteAddr(HttpServletRequest request) {
        return request == null ? null : request.getRemoteAddr();
    }

    private String hashPrefix(String hash) {
        if (!StringUtils.hasText(hash)) {
            return null;
        }
        return hash.length() <= 12 ? hash : hash.substring(0, 12);
    }

    private boolean hasScope(Set<String> scopes, String requiredScope) {
        if (scopes.contains(SCOPE_CLIENT_APP_ADMIN_ALL) || scopes.contains(requiredScope)) {
            return true;
        }
        // Compatibility bridge for pre-1.0.6 upstream admin credentials. Existing admin
        // keys with ClientApp manage authority may issue a runtime key for the ClientApp
        // they are already allowed to manage; the controller still validates tenant and
        // upstream-system ownership before creating the credential.
        return UpstreamBootstrapRequestService.SCOPE_CLIENT_APP_RUNTIME_KEY_ISSUE.equals(requiredScope)
                && scopes.contains(UpstreamBootstrapRequestService.SCOPE_CLIENT_APP_MANAGE);
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
        return canonicalScopeSet(values);
    }

    private Set<String> canonicalScopeSet(Set<String> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        Set<String> result = new LinkedHashSet<>();
        for (String value : values) {
            for (String token : value.split("[,\\s]+")) {
                String canonical = UpstreamBootstrapRequestService.canonicalizeScope(token);
                if (StringUtils.hasText(canonical)) {
                    result.add(canonical);
                }
            }
        }
        return result;
    }
}
