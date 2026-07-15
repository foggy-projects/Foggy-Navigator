package com.foggy.navigator.business.agent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.business.agent.config.BusinessTaskScopedTokenProperties;
import com.foggy.navigator.business.agent.model.dto.BusinessTaskScopedTokenDTO;
import com.foggy.navigator.business.agent.model.entity.BusinessTaskScopedTokenEntity;
import com.foggy.navigator.business.agent.model.entity.ClientAppFunctionGrantEntity;
import com.foggy.navigator.business.agent.repository.ClientAppFunctionGrantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class BusinessTaskScopedTokenPolicyService {

    public static final int CURRENT_TOKEN_VERSION = 2;
    public static final int INITIAL_GENERATION = 1;
    public static final String AUDIENCE_WORKER_GATEWAY = "WORKER_GATEWAY";
    public static final String IDENTITY_ASSURANCE_CLIENT_APP_DELEGATED = "client-app-delegated";

    private static final TypeReference<List<FunctionScopeEntry>> FUNCTION_SCOPE_LIST_TYPE = new TypeReference<>() {
    };

    private final ClientAppFunctionGrantRepository functionGrantRepository;
    private final ObjectMapper objectMapper;
    private final BusinessTaskScopedTokenProperties properties;

    public void initializeNewToken(BusinessTaskScopedTokenEntity token) {
        if (token == null) {
            throw new IllegalArgumentException("token is required");
        }
        requireText(token.getTenantId(), "token tenantId is required");
        requireText(token.getClientAppId(), "token clientAppId is required");

        LocalDateTime issuedAt = LocalDateTime.now();
        token.setTokenVersion(CURRENT_TOKEN_VERSION);
        token.setGeneration(INITIAL_GENERATION);
        token.setAudience(AUDIENCE_WORKER_GATEWAY);
        token.setIdentityAssurance(IDENTITY_ASSURANCE_CLIENT_APP_DELEGATED);
        token.setFunctionScopeJson(writeScope(snapshotEnabledClientAppFunctions(
                token.getTenantId(), token.getClientAppId())));
        token.setIssuedAt(issuedAt);
        token.setExpiresAt(issuedAt.plus(properties.effectiveTtl()));
    }

    /** Upper bound used to retain terminal authorization tombstones. */
    public java.time.Duration maximumCapabilityLifetime() {
        return properties.effectiveMaxTtl();
    }

    public void requireGatewayToken(BusinessTaskScopedTokenDTO token) {
        if (token == null) {
            throw new IllegalArgumentException("token is required");
        }
        if (!Integer.valueOf(CURRENT_TOKEN_VERSION).equals(token.getTokenVersion())) {
            throw new SecurityException("unsupported task token version");
        }
        if (token.getGeneration() == null || token.getGeneration() < INITIAL_GENERATION) {
            throw new SecurityException("invalid task token generation");
        }
        if (!AUDIENCE_WORKER_GATEWAY.equals(token.getAudience())) {
            throw new SecurityException("task token audience mismatch");
        }
        if (!IDENTITY_ASSURANCE_CLIENT_APP_DELEGATED.equals(token.getIdentityAssurance())) {
            throw new SecurityException("task token identity assurance mismatch");
        }
        readScope(token.getFunctionScopeJson());
    }

    public boolean allowsFunction(BusinessTaskScopedTokenDTO token, String functionId, String version) {
        if (!StringUtils.hasText(functionId) || !StringUtils.hasText(version)) {
            return false;
        }
        return readScope(token.getFunctionScopeJson()).contains(
                new FunctionScopeEntry(functionId.trim(), version.trim()));
    }

    public void requireFunctionAllowed(BusinessTaskScopedTokenDTO token, String functionId, String version) {
        if (!allowsFunction(token, functionId, version)) {
            throw new SecurityException("business function is outside task token scope");
        }
    }

    private Set<FunctionScopeEntry> snapshotEnabledClientAppFunctions(String tenantId, String clientAppId) {
        Set<FunctionScopeEntry> scope = new LinkedHashSet<>();
        List<ClientAppFunctionGrantEntity> grants =
                functionGrantRepository.findByTenantIdAndClientAppId(tenantId, clientAppId);
        if (grants == null) {
            return scope;
        }
        grants.stream()
                .filter(grant -> BusinessFunctionRegistryService.STATUS_ENABLED.equals(grant.getStatus()))
                .filter(grant -> StringUtils.hasText(grant.getFunctionId()) && StringUtils.hasText(grant.getVersion()))
                .map(grant -> new FunctionScopeEntry(
                        grant.getFunctionId().trim(), grant.getVersion().trim()))
                .sorted(Comparator.comparing(FunctionScopeEntry::functionId)
                        .thenComparing(FunctionScopeEntry::version))
                .forEach(scope::add);
        return scope;
    }

    private String writeScope(Set<FunctionScopeEntry> scope) {
        try {
            return objectMapper.writeValueAsString(scope);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize task token function scope", e);
        }
    }

    private Set<FunctionScopeEntry> readScope(String functionScopeJson) {
        if (!StringUtils.hasText(functionScopeJson)) {
            throw new SecurityException("task token function scope is missing");
        }
        try {
            List<FunctionScopeEntry> values = objectMapper.readValue(
                    functionScopeJson, FUNCTION_SCOPE_LIST_TYPE);
            if (values == null) {
                throw new SecurityException("task token function scope is invalid");
            }
            if (values.stream().anyMatch(value -> value == null ||
                    !StringUtils.hasText(value.functionId()) ||
                    !StringUtils.hasText(value.version()))) {
                throw new SecurityException("task token function scope is invalid");
            }
            return new LinkedHashSet<>(values);
        } catch (SecurityException e) {
            throw e;
        } catch (Exception e) {
            throw new SecurityException("task token function scope is invalid", e);
        }
    }

    private void requireText(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(message);
        }
    }

    private record FunctionScopeEntry(String functionId, String version) {
    }
}
