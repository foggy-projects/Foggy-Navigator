package com.foggy.navigator.business.agent.service;

import com.foggy.navigator.business.agent.model.dto.ClientAppControlPlanePrincipal;
import com.foggy.navigator.business.agent.model.entity.ClientAppControlCredentialEntity;
import com.foggy.navigator.business.agent.repository.ClientAppControlCredentialRepository;
import com.foggy.navigator.common.context.UserContext;
import com.foggy.navigator.common.dto.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ClientAppControlCredentialService {

    public static final String HEADER_CONTROL_KEY = "X-Client-App-Control-Key";
    public static final String SCOPE_ALL = "CONTROL_PLANE_ALL";
    public static final String SCOPE_AGENT_BUNDLE_SYNC = "AGENT_BUNDLE_SYNC";
    public static final String SCOPE_SKILL_BUNDLE_SYNC = "SKILL_BUNDLE_SYNC";
    public static final String SCOPE_FUNCTION_MANIFEST_IMPORT = "FUNCTION_MANIFEST_IMPORT";
    public static final String SCOPE_FUNCTION_GRANT_MANAGE = "FUNCTION_GRANT_MANAGE";
    public static final String SCOPE_E2E_MODEL_ENSURE = "E2E_MODEL_ENSURE";
    public static final String SCOPE_UPSTREAM_USER_GRANT = "UPSTREAM_USER_GRANT";
    public static final String SCOPE_MODEL_CONFIG_GRANT_MANAGE = "MODEL_CONFIG_GRANT_MANAGE";
    public static final String SCOPE_MODEL_CONFIG_MANAGE = "MODEL_CONFIG_MANAGE";

    private final ClientAppControlCredentialRepository controlCredentialRepository;

    @Transactional
    public ClientAppControlPlanePrincipal requireAccess(HttpServletRequest request, String requiredScope, String clientAppId) {
        CurrentUser user = UserContext.getCurrentUser();
        if (user != null && (user.isTenantAdmin() || user.isSuperAdmin())) {
            return ClientAppControlPlanePrincipal.builder()
                    .admin(true)
                    .tenantId(resolveTenantId(user))
                    .clientAppId(clientAppId)
                    .actorUserId(user.getUserId())
                    .scopes(Set.of(SCOPE_ALL))
                    .build();
        }

        String controlKey = request == null ? null : request.getHeader(HEADER_CONTROL_KEY);
        if (!StringUtils.hasText(controlKey)) {
            throw new SecurityException("control-plane credential is required");
        }

        ClientAppControlCredentialEntity credential = controlCredentialRepository
                .findByControlKeyHash(SecretTokenSupport.sha256(controlKey))
                .orElseThrow(() -> new SecurityException("invalid control-plane credential"));
        validateCredential(credential, requiredScope, clientAppId);
        credential.setLastUsedAt(LocalDateTime.now());
        controlCredentialRepository.save(credential);

        return ClientAppControlPlanePrincipal.builder()
                .admin(false)
                .tenantId(credential.getTenantId())
                .clientAppId(credential.getClientAppId())
                .credentialId(credential.getCredentialId())
                .actorUserId(StringUtils.hasText(credential.getEffectiveUserId())
                        ? credential.getEffectiveUserId()
                        : "client-app-control:" + credential.getCredentialId())
                .scopes(parseScopes(credential.getScopes()))
                .build();
    }

    private void validateCredential(ClientAppControlCredentialEntity credential, String requiredScope, String clientAppId) {
        if (!ClientAppService.STATUS_ACTIVE.equals(credential.getStatus())) {
            throw new SecurityException("control-plane credential is not active");
        }
        if (credential.getExpiresAt() != null && credential.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new SecurityException("control-plane credential expired");
        }
        if (StringUtils.hasText(clientAppId) && !clientAppId.equals(credential.getClientAppId())) {
            throw new SecurityException("control-plane credential clientAppId mismatch");
        }
        Set<String> scopes = parseScopes(credential.getScopes());
        if (StringUtils.hasText(requiredScope) && !hasRequiredScope(scopes, requiredScope)) {
            throw new SecurityException("control-plane credential lacks scope: " + requiredScope);
        }
    }

    private boolean hasRequiredScope(Set<String> scopes, String requiredScope) {
        if (scopes.contains(SCOPE_ALL) || scopes.contains(requiredScope)) {
            return true;
        }
        if (SCOPE_MODEL_CONFIG_MANAGE.equals(requiredScope)
                && scopes.contains(SCOPE_MODEL_CONFIG_GRANT_MANAGE)) {
            return true;
        }
        // Compatibility for credentials issued before function-specific scopes were introduced.
        return (SCOPE_FUNCTION_MANIFEST_IMPORT.equals(requiredScope) || SCOPE_FUNCTION_GRANT_MANAGE.equals(requiredScope))
                && scopes.contains(SCOPE_AGENT_BUNDLE_SYNC);
    }

    public static Set<String> defaultScopes() {
        return Set.of(
                SCOPE_AGENT_BUNDLE_SYNC,
                SCOPE_SKILL_BUNDLE_SYNC,
                SCOPE_FUNCTION_MANIFEST_IMPORT,
                SCOPE_FUNCTION_GRANT_MANAGE,
                SCOPE_E2E_MODEL_ENSURE,
                SCOPE_UPSTREAM_USER_GRANT,
                SCOPE_MODEL_CONFIG_GRANT_MANAGE,
                SCOPE_MODEL_CONFIG_MANAGE);
    }

    public static String serializeScopes(Set<String> scopes) {
        if (scopes == null || scopes.isEmpty()) {
            return String.join(",", defaultScopes());
        }
        return scopes.stream()
                .filter(StringUtils::hasText)
                .map(scope -> scope.trim().replace('-', '_').toUpperCase())
                .collect(Collectors.joining(","));
    }

    private Set<String> parseScopes(String scopes) {
        if (!StringUtils.hasText(scopes)) {
            return defaultScopes();
        }
        return Arrays.stream(scopes.split(","))
                .filter(StringUtils::hasText)
                .map(scope -> scope.trim().replace('-', '_').toUpperCase())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private String resolveTenantId(CurrentUser user) {
        if (StringUtils.hasText(user.getTenantId())) {
            return user.getTenantId();
        }
        return user.getUserId();
    }
}
