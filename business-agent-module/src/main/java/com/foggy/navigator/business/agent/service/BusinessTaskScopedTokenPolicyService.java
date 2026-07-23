package com.foggy.navigator.business.agent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.business.agent.config.BusinessTaskScopedTokenProperties;
import com.foggy.navigator.business.agent.model.dto.BusinessTaskScopedTokenDTO;
import com.foggy.navigator.business.agent.model.entity.BusinessTaskScopedTokenEntity;
import com.foggy.navigator.business.agent.model.entity.ClientAppFunctionGrantEntity;
import com.foggy.navigator.business.agent.repository.BusinessFunctionRepository;
import com.foggy.navigator.business.agent.repository.ClientAppFunctionGrantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class BusinessTaskScopedTokenPolicyService {

    public static final int CURRENT_TOKEN_VERSION = 2;
    public static final int INITIAL_GENERATION = 1;
    public static final String AUDIENCE_WORKER_GATEWAY = "WORKER_GATEWAY";
    public static final String IDENTITY_ASSURANCE_CLIENT_APP_DELEGATED = "client-app-delegated";
    public static final String FUNCTION_SCOPE_SOURCE_CLIENT_APP_GRANTS = "CLIENT_APP_GRANTS";
    public static final String FUNCTION_SCOPE_SOURCE_REQUEST_EXPLICIT_EMPTY = "REQUEST_EXPLICIT_EMPTY";
    public static final String FUNCTION_SCOPE_SOURCE_REQUEST_ALLOWLIST = "REQUEST_ALLOWLIST";

    private static final TypeReference<List<FunctionScopeEntry>> FUNCTION_SCOPE_LIST_TYPE = new TypeReference<>() {
    };

    private final ClientAppFunctionGrantRepository functionGrantRepository;
    private final BusinessFunctionRepository functionRepository;
    private final ObjectMapper objectMapper;
    private final BusinessTaskScopedTokenProperties properties;

    public FunctionScopeSummary initializeNewToken(BusinessTaskScopedTokenEntity token) {
        return initializeNewToken(token, FunctionScopeRequest.unspecified());
    }

    public FunctionScopeSummary initializeNewToken(
            BusinessTaskScopedTokenEntity token,
            FunctionScopeRequest request) {
        if (token == null) {
            throw new IllegalArgumentException("token is required");
        }
        requireText(token.getTenantId(), "token tenantId is required");
        requireText(token.getClientAppId(), "token clientAppId is required");

        FunctionScopeResolution resolution = resolveFunctionScope(
                token.getTenantId(), token.getClientAppId(), request);

        LocalDateTime issuedAt = LocalDateTime.now();
        token.setTokenVersion(CURRENT_TOKEN_VERSION);
        token.setGeneration(INITIAL_GENERATION);
        token.setAudience(AUDIENCE_WORKER_GATEWAY);
        token.setIdentityAssurance(IDENTITY_ASSURANCE_CLIENT_APP_DELEGATED);
        token.setFunctionScopeJson(writeScope(resolution.scope()));
        token.setIssuedAt(issuedAt);
        token.setExpiresAt(issuedAt.plus(properties.effectiveTtl()));
        return resolution.summary();
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

    public FunctionScopeSummary summarizeFunctionScope(String functionScopeJson, String source) {
        Set<FunctionScopeEntry> scope = readScope(functionScopeJson);
        return resolution(scope, source).summary();
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

    private FunctionScopeResolution resolveFunctionScope(
            String tenantId,
            String clientAppId,
            FunctionScopeRequest request) {
        FunctionScopeRequest effectiveRequest = request != null ? request : FunctionScopeRequest.unspecified();
        Set<FunctionScopeEntry> grantScope = snapshotEnabledClientAppFunctions(tenantId, clientAppId);
        if (!effectiveRequest.provided()) {
            return resolution(grantScope, FUNCTION_SCOPE_SOURCE_CLIENT_APP_GRANTS);
        }
        if (effectiveRequest.functionCodes() == null) {
            throw new IllegalArgumentException("FUNCTION_SCOPE_EXPLICIT_NULL: allowedFunctions must be an array");
        }
        if (effectiveRequest.functionCodes().isEmpty()) {
            return resolution(Set.of(), FUNCTION_SCOPE_SOURCE_REQUEST_EXPLICIT_EMPTY);
        }

        Set<String> requestedCodes = new LinkedHashSet<>();
        for (String code : effectiveRequest.functionCodes()) {
            if (!StringUtils.hasText(code)) {
                throw new IllegalArgumentException("INVALID_FUNCTION_CODE: allowedFunctions contains a blank code");
            }
            requestedCodes.add(code.trim());
        }

        Map<String, Set<FunctionScopeEntry>> grantsByFunction = new LinkedHashMap<>();
        for (FunctionScopeEntry entry : grantScope) {
            grantsByFunction.computeIfAbsent(entry.functionId(), ignored -> new LinkedHashSet<>()).add(entry);
        }

        Set<FunctionScopeEntry> selectedScope = new LinkedHashSet<>();
        for (String functionCode : requestedCodes) {
            if (functionRepository.findByTenantIdAndFunctionId(tenantId, functionCode).isEmpty()) {
                throw new IllegalArgumentException("UNKNOWN_FUNCTION_CODE: requested BusinessFunction does not exist");
            }
            Set<FunctionScopeEntry> grantedEntries = grantsByFunction.get(functionCode);
            if (grantedEntries == null || grantedEntries.isEmpty()) {
                throw new SecurityException(
                        "FUNCTION_SCOPE_OWNERSHIP_MISMATCH: requested BusinessFunction is not granted to this ClientApp");
            }
            selectedScope.addAll(grantedEntries);
        }
        return resolution(selectedScope, FUNCTION_SCOPE_SOURCE_REQUEST_ALLOWLIST);
    }

    private FunctionScopeResolution resolution(Set<FunctionScopeEntry> scope, String source) {
        Set<FunctionScopeEntry> stableScope = scope.stream()
                .sorted(Comparator.comparing(FunctionScopeEntry::functionId)
                        .thenComparing(FunctionScopeEntry::version))
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        int functionCount = (int) stableScope.stream()
                .map(FunctionScopeEntry::functionId)
                .distinct()
                .count();
        return new FunctionScopeResolution(
                stableScope,
                new FunctionScopeSummary(functionCount, source, stableScope.isEmpty()));
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

    private record FunctionScopeResolution(
            Set<FunctionScopeEntry> scope,
            FunctionScopeSummary summary) {
    }

    public record FunctionScopeRequest(boolean provided, List<String> functionCodes) {

        public static FunctionScopeRequest unspecified() {
            return new FunctionScopeRequest(false, null);
        }

        public static FunctionScopeRequest explicit(List<String> functionCodes) {
            return new FunctionScopeRequest(true, functionCodes);
        }
    }

    public record FunctionScopeSummary(
            int effectiveFunctionCount,
            String source,
            boolean empty) {
    }
}
