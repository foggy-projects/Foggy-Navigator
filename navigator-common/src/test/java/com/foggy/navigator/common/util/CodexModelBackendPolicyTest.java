package com.foggy.navigator.common.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CodexModelBackendPolicyTest {

    @Test
    void sdkRejectsUltraFromDefaultOrAvailableModelCatalog() {
        assertThrows(IllegalArgumentException.class, () -> CodexModelBackendPolicy.validate(
                ProviderRouteRegistry.BACKEND_OPENAI_CODEX,
                "codex-latest:ultra", List.of("codex-latest:max")));
        assertThrows(IllegalArgumentException.class, () -> CodexModelBackendPolicy.validate(
                ProviderRouteRegistry.BACKEND_OPENAI_CODEX,
                "codex-latest:max", List.of("codex-terra:ultra")));
    }

    @Test
    void appServerAllowsSolAndTerraUltraButRejectsLunaOrUnknownUltra() {
        assertDoesNotThrow(() -> CodexModelBackendPolicy.validate(
                ProviderRouteRegistry.BACKEND_OPENAI_CODEX_APP_SERVER,
                "gpt-5.6-sol:ultra", List.of("codex-terra:ultra")));
        assertThrows(IllegalArgumentException.class, () -> CodexModelBackendPolicy.validate(
                ProviderRouteRegistry.BACKEND_OPENAI_CODEX_APP_SERVER,
                "codex-luna:ultra", List.of()));
        assertThrows(IllegalArgumentException.class, () -> CodexModelBackendPolicy.validate(
                ProviderRouteRegistry.BACKEND_OPENAI_CODEX_APP_SERVER,
                "gpt-5.7-sol:ultra", List.of()));
    }

    @Test
    void bothCodexBackendsRejectRetiredMini() {
        assertThrows(IllegalArgumentException.class, () -> CodexModelBackendPolicy.validateModel(
                ProviderRouteRegistry.BACKEND_OPENAI_CODEX, "gpt-5.4-mini:high"));
        assertThrows(IllegalArgumentException.class, () -> CodexModelBackendPolicy.validateModel(
                ProviderRouteRegistry.BACKEND_OPENAI_CODEX_APP_SERVER, "gpt-5.4-mini"));
    }

    @Test
    void nonUltraFutureModelsRemainExtensible() {
        assertDoesNotThrow(() -> CodexModelBackendPolicy.validate(
                ProviderRouteRegistry.BACKEND_OPENAI_CODEX,
                "gpt-5.7-sol:max", List.of("custom-future-tier")));
    }
}
