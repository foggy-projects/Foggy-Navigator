package com.foggy.navigator.business.agent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.business.agent.config.BusinessTaskScopedTokenProperties;
import com.foggy.navigator.business.agent.model.dto.BusinessTaskScopedTokenDTO;
import com.foggy.navigator.business.agent.model.entity.BusinessTaskScopedTokenEntity;
import com.foggy.navigator.business.agent.model.entity.BusinessFunctionEntity;
import com.foggy.navigator.business.agent.model.entity.ClientAppFunctionGrantEntity;
import com.foggy.navigator.business.agent.repository.BusinessFunctionRepository;
import com.foggy.navigator.business.agent.repository.ClientAppFunctionGrantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BusinessTaskScopedTokenPolicyServiceTest {

    private static final String TENANT_ID = "tenant_1";
    private static final String CLIENT_APP_ID = "app_1";

    @Mock
    private ClientAppFunctionGrantRepository functionGrantRepository;
    @Mock
    private BusinessFunctionRepository functionRepository;

    private ObjectMapper objectMapper;
    private BusinessTaskScopedTokenProperties properties;
    private BusinessTaskScopedTokenPolicyService policyService;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        properties = new BusinessTaskScopedTokenProperties();
        policyService = new BusinessTaskScopedTokenPolicyService(
                functionGrantRepository,
                functionRepository,
                objectMapper,
                properties
        );
    }

    @Test
    void initializeNewToken_usesDefaultThirtyMinuteTtl() {
        when(functionGrantRepository.findByTenantIdAndClientAppId(TENANT_ID, CLIENT_APP_ID))
                .thenReturn(List.of());
        BusinessTaskScopedTokenEntity token = newTokenEntity();

        BusinessTaskScopedTokenPolicyService.FunctionScopeSummary summary =
                policyService.initializeNewToken(token);

        assertEquals(Duration.ofMinutes(30), Duration.between(token.getIssuedAt(), token.getExpiresAt()));
        assertEquals(BusinessTaskScopedTokenPolicyService.CURRENT_TOKEN_VERSION, token.getTokenVersion());
        assertEquals(BusinessTaskScopedTokenPolicyService.INITIAL_GENERATION, token.getGeneration());
        assertEquals(BusinessTaskScopedTokenPolicyService.AUDIENCE_WORKER_GATEWAY, token.getAudience());
        assertEquals(BusinessTaskScopedTokenPolicyService.IDENTITY_ASSURANCE_CLIENT_APP_DELEGATED,
                token.getIdentityAssurance());
        assertEquals(0, summary.effectiveFunctionCount());
        assertEquals(BusinessTaskScopedTokenPolicyService.FUNCTION_SCOPE_SOURCE_CLIENT_APP_GRANTS,
                summary.source());
        assertTrue(summary.empty());
    }

    @Test
    void initializeNewToken_capsConfiguredTtlAtSixtyMinutes() {
        properties.setTtl(Duration.ofMinutes(90));
        when(functionGrantRepository.findByTenantIdAndClientAppId(TENANT_ID, CLIENT_APP_ID))
                .thenReturn(List.of());
        BusinessTaskScopedTokenEntity token = newTokenEntity();

        policyService.initializeNewToken(token);

        assertEquals(Duration.ofMinutes(60), Duration.between(token.getIssuedAt(), token.getExpiresAt()));
    }

    @Test
    void initializeNewToken_snapshotsOnlyEnabledGrantsInSortedDeduplicatedOrder() throws Exception {
        when(functionGrantRepository.findByTenantIdAndClientAppId(TENANT_ID, CLIENT_APP_ID))
                .thenReturn(List.of(
                        grant("zeta", "v2", BusinessFunctionRegistryService.STATUS_ENABLED),
                        grant("alpha", "v1", BusinessFunctionRegistryService.STATUS_ENABLED),
                        grant("disabled", "v1", BusinessFunctionRegistryService.STATUS_DISABLED),
                        grant("alpha", "v1", BusinessFunctionRegistryService.STATUS_ENABLED),
                        grant(" ", "v3", BusinessFunctionRegistryService.STATUS_ENABLED),
                        grant("missing-version", " ", BusinessFunctionRegistryService.STATUS_ENABLED)
                ));
        BusinessTaskScopedTokenEntity token = newTokenEntity();

        policyService.initializeNewToken(token);

        List<Map<String, String>> scope = objectMapper.readValue(token.getFunctionScopeJson(), new TypeReference<>() {
        });
        assertEquals(List.of(
                Map.of("functionId", "alpha", "version", "v1"),
                Map.of("functionId", "zeta", "version", "v2")), scope);
        verify(functionGrantRepository).findByTenantIdAndClientAppId(TENANT_ID, CLIENT_APP_ID);
    }

    @Test
    void initializeNewToken_explicitEmptyOverridesEnabledClientAppGrants() {
        when(functionGrantRepository.findByTenantIdAndClientAppId(TENANT_ID, CLIENT_APP_ID))
                .thenReturn(List.of(grant("orders.read", "v1", BusinessFunctionRegistryService.STATUS_ENABLED)));
        BusinessTaskScopedTokenEntity token = newTokenEntity();

        BusinessTaskScopedTokenPolicyService.FunctionScopeSummary summary = policyService.initializeNewToken(
                token,
                BusinessTaskScopedTokenPolicyService.FunctionScopeRequest.explicit(List.of()));

        assertEquals("[]", token.getFunctionScopeJson());
        assertEquals(0, summary.effectiveFunctionCount());
        assertEquals(BusinessTaskScopedTokenPolicyService.FUNCTION_SCOPE_SOURCE_REQUEST_EXPLICIT_EMPTY,
                summary.source());
        assertTrue(summary.empty());
    }

    @Test
    void initializeNewToken_requestAllowlistSelectsOnlyGrantedFunctionCodes() throws Exception {
        when(functionGrantRepository.findByTenantIdAndClientAppId(TENANT_ID, CLIENT_APP_ID))
                .thenReturn(List.of(
                        grant("orders.read", "v1", BusinessFunctionRegistryService.STATUS_ENABLED),
                        grant("orders.write", "v2", BusinessFunctionRegistryService.STATUS_ENABLED)));
        when(functionRepository.findByTenantIdAndFunctionId(TENANT_ID, "orders.read"))
                .thenReturn(Optional.of(function("orders.read")));
        BusinessTaskScopedTokenEntity token = newTokenEntity();

        BusinessTaskScopedTokenPolicyService.FunctionScopeSummary summary = policyService.initializeNewToken(
                token,
                BusinessTaskScopedTokenPolicyService.FunctionScopeRequest.explicit(List.of("orders.read")));

        List<Map<String, String>> scope = objectMapper.readValue(token.getFunctionScopeJson(), new TypeReference<>() {
        });
        assertEquals(List.of(Map.of("functionId", "orders.read", "version", "v1")), scope);
        assertEquals(1, summary.effectiveFunctionCount());
        assertEquals(BusinessTaskScopedTokenPolicyService.FUNCTION_SCOPE_SOURCE_REQUEST_ALLOWLIST,
                summary.source());
        assertFalse(summary.empty());
    }

    @Test
    void initializeNewToken_rejectsUnknownFunctionCodeDistinctly() {
        when(functionGrantRepository.findByTenantIdAndClientAppId(TENANT_ID, CLIENT_APP_ID))
                .thenReturn(List.of());
        when(functionRepository.findByTenantIdAndFunctionId(TENANT_ID, "missing.function"))
                .thenReturn(Optional.empty());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> policyService.initializeNewToken(
                        newTokenEntity(),
                        BusinessTaskScopedTokenPolicyService.FunctionScopeRequest.explicit(
                                List.of("missing.function"))));

        assertTrue(error.getMessage().startsWith("UNKNOWN_FUNCTION_CODE:"));
    }

    @Test
    void initializeNewToken_rejectsClientAppGrantMismatchDistinctly() {
        when(functionGrantRepository.findByTenantIdAndClientAppId(TENANT_ID, CLIENT_APP_ID))
                .thenReturn(List.of());
        when(functionRepository.findByTenantIdAndFunctionId(TENANT_ID, "orders.read"))
                .thenReturn(Optional.of(function("orders.read")));

        SecurityException error = assertThrows(SecurityException.class,
                () -> policyService.initializeNewToken(
                        newTokenEntity(),
                        BusinessTaskScopedTokenPolicyService.FunctionScopeRequest.explicit(
                                List.of("orders.read"))));

        assertTrue(error.getMessage().startsWith("FUNCTION_SCOPE_OWNERSHIP_MISMATCH:"));
    }

    @Test
    void requireGatewayToken_rejectsLegacyV1Token() {
        BusinessTaskScopedTokenDTO token = validToken();
        token.setTokenVersion(1);

        assertThrows(SecurityException.class, () -> policyService.requireGatewayToken(token));
    }

    @Test
    void requireGatewayToken_rejectsWrongAudience() {
        BusinessTaskScopedTokenDTO token = validToken();
        token.setAudience("OTHER_AUDIENCE");

        assertThrows(SecurityException.class, () -> policyService.requireGatewayToken(token));
    }

    @Test
    void requireGatewayToken_rejectsMalformedFunctionScope() {
        BusinessTaskScopedTokenDTO token = validToken();
        token.setFunctionScopeJson("{\"functionId\":\"orders.read\"}");

        assertThrows(SecurityException.class, () -> policyService.requireGatewayToken(token));
    }

    @Test
    void functionScope_requiresExactFunctionIdAndVersion() {
        BusinessTaskScopedTokenDTO token = validToken();

        assertDoesNotThrow(() -> policyService.requireGatewayToken(token));
        assertTrue(policyService.allowsFunction(token, "orders.read", "v1"));
        assertFalse(policyService.allowsFunction(token, "orders.read", "v2"));
        assertFalse(policyService.allowsFunction(token, "orders.write", "v1"));
        assertFalse(policyService.allowsFunction(token, "orders.read", null));
        assertDoesNotThrow(() -> policyService.requireFunctionAllowed(token, "orders.read", "v1"));
        assertThrows(SecurityException.class,
                () -> policyService.requireFunctionAllowed(token, "orders.read", "v2"));
    }

    @Test
    void functionScope_usesStructuredPairsWithoutDelimiterCollision() {
        BusinessTaskScopedTokenDTO token = validToken();
        token.setFunctionScopeJson("""
                [{"functionId":"a@b","version":"c"}]
                """);

        assertTrue(policyService.allowsFunction(token, "a@b", "c"));
        assertFalse(policyService.allowsFunction(token, "a", "b@c"));
    }

    private BusinessTaskScopedTokenEntity newTokenEntity() {
        BusinessTaskScopedTokenEntity token = new BusinessTaskScopedTokenEntity();
        token.setTenantId(TENANT_ID);
        token.setClientAppId(CLIENT_APP_ID);
        return token;
    }

    private BusinessTaskScopedTokenDTO validToken() {
        BusinessTaskScopedTokenDTO token = new BusinessTaskScopedTokenDTO();
        token.setTokenVersion(BusinessTaskScopedTokenPolicyService.CURRENT_TOKEN_VERSION);
        token.setGeneration(BusinessTaskScopedTokenPolicyService.INITIAL_GENERATION);
        token.setAudience(BusinessTaskScopedTokenPolicyService.AUDIENCE_WORKER_GATEWAY);
        token.setIdentityAssurance(BusinessTaskScopedTokenPolicyService.IDENTITY_ASSURANCE_CLIENT_APP_DELEGATED);
        token.setFunctionScopeJson("""
                [
                  {"functionId":"orders.read","version":"v1"},
                  {"functionId":"orders.write","version":"v2"}
                ]
                """);
        return token;
    }

    private ClientAppFunctionGrantEntity grant(String functionId, String version, String status) {
        ClientAppFunctionGrantEntity grant = new ClientAppFunctionGrantEntity();
        grant.setFunctionId(functionId);
        grant.setVersion(version);
        grant.setStatus(status);
        return grant;
    }

    private BusinessFunctionEntity function(String functionId) {
        BusinessFunctionEntity function = new BusinessFunctionEntity();
        function.setTenantId(TENANT_ID);
        function.setFunctionId(functionId);
        return function;
    }
}
