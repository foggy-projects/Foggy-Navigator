package com.foggy.navigator.common.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderRouteRegistryTest {

    @Test
    void providerTypeForWorkerBackend_mapsSupportedBackends() {
        assertEquals(ProviderRouteRegistry.PROVIDER_CODEX_WORKER,
                ProviderRouteRegistry.providerTypeForWorkerBackendOrNull(ProviderRouteRegistry.BACKEND_OPENAI_CODEX));
        assertEquals(ProviderRouteRegistry.PROVIDER_CLAUDE_WORKER,
                ProviderRouteRegistry.providerTypeForWorkerBackendOrNull(ProviderRouteRegistry.BACKEND_CLAUDE_CODE));
        assertEquals(ProviderRouteRegistry.PROVIDER_GEMINI_WORKER,
                ProviderRouteRegistry.providerTypeForWorkerBackendOrNull(ProviderRouteRegistry.BACKEND_GEMINI_CLI));
        assertEquals(ProviderRouteRegistry.PROVIDER_LANGGRAPH_BIZ_WORKER,
                ProviderRouteRegistry.providerTypeForWorkerBackendOrNull(ProviderRouteRegistry.BACKEND_LANGGRAPH_BIZ));
    }

    @Test
    void providerTypeForWorkerBackend_normalizesBackendTokens() {
        assertEquals(ProviderRouteRegistry.PROVIDER_CODEX_WORKER,
                ProviderRouteRegistry.providerTypeForWorkerBackendOrNull("openai-codex"));
        assertEquals(ProviderRouteRegistry.PROVIDER_LANGGRAPH_BIZ_WORKER,
                ProviderRouteRegistry.providerTypeForWorkerBackendOrNull(" langgraph_biz "));
    }

    @Test
    void providerTypeForWorkerBackend_returnsEmptyForUnknownBackend() {
        assertTrue(ProviderRouteRegistry.providerTypeForWorkerBackend(null).isEmpty());
        assertTrue(ProviderRouteRegistry.providerTypeForWorkerBackend(" ").isEmpty());
        assertTrue(ProviderRouteRegistry.providerTypeForWorkerBackend("UNKNOWN").isEmpty());
        assertNull(ProviderRouteRegistry.providerTypeForWorkerBackendOrNull("UNKNOWN"));
    }

    @Test
    void isKnownWorkerBackend_acceptsOnlyCanonicalBackendFamily() {
        assertTrue(ProviderRouteRegistry.isKnownWorkerBackend("openai-codex"));
        assertTrue(ProviderRouteRegistry.isKnownWorkerBackend("GEMINI_CLI"));
        assertFalse(ProviderRouteRegistry.isKnownWorkerBackend("codex-worker"));
        assertFalse(ProviderRouteRegistry.isKnownWorkerBackend("UNKNOWN"));
    }

    @Test
    void workerBackendForRouteToken_mapsProviderAndShortAliases() {
        assertEquals(ProviderRouteRegistry.BACKEND_OPENAI_CODEX,
                ProviderRouteRegistry.workerBackendForRouteTokenOrNull("codex-biz-worker"));
        assertEquals(ProviderRouteRegistry.BACKEND_CLAUDE_CODE,
                ProviderRouteRegistry.workerBackendForRouteTokenOrNull("claude"));
        assertEquals(ProviderRouteRegistry.BACKEND_GEMINI_CLI,
                ProviderRouteRegistry.workerBackendForRouteTokenOrNull("gemini-worker"));
        assertEquals(ProviderRouteRegistry.BACKEND_LANGGRAPH_BIZ,
                ProviderRouteRegistry.workerBackendForRouteTokenOrNull("langgraph"));
        assertNull(ProviderRouteRegistry.workerBackendForRouteTokenOrNull("unknown-provider"));
    }

    @Test
    void isModelProviderCompatible_allowsExactProviderMatch() {
        assertTrue(ProviderRouteRegistry.isModelProviderCompatible(
                ProviderRouteRegistry.PROVIDER_CLAUDE_WORKER,
                ProviderRouteRegistry.PROVIDER_CLAUDE_WORKER));
        assertTrue(ProviderRouteRegistry.isModelProviderCompatible("custom-provider", "custom-provider"));
    }

    @Test
    void isModelProviderCompatible_allowsCodexModelToUseCodexBizRoute() {
        assertTrue(ProviderRouteRegistry.isModelProviderCompatible(
                ProviderRouteRegistry.PROVIDER_CODEX_WORKER,
                ProviderRouteRegistry.PROVIDER_CODEX_BIZ_WORKER));
    }

    @Test
    void isModelProviderCompatible_rejectsCrossProviderMismatch() {
        assertFalse(ProviderRouteRegistry.isModelProviderCompatible(
                ProviderRouteRegistry.PROVIDER_CLAUDE_WORKER,
                ProviderRouteRegistry.PROVIDER_CODEX_WORKER));
        assertFalse(ProviderRouteRegistry.isModelProviderCompatible(null,
                ProviderRouteRegistry.PROVIDER_CODEX_WORKER));
        assertFalse(ProviderRouteRegistry.isModelProviderCompatible(
                ProviderRouteRegistry.PROVIDER_CODEX_WORKER,
                null));
    }

    @Test
    void knownProviderTypes_includesDirectCodexBizRoute() {
        assertTrue(ProviderRouteRegistry.knownProviderTypes()
                .contains(ProviderRouteRegistry.PROVIDER_CODEX_BIZ_WORKER));
    }
}
