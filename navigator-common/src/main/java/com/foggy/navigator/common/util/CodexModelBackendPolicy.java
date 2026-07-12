package com.foggy.navigator.common.util;

import java.util.Collection;
import java.util.Locale;
import java.util.Set;

/** Backend-level Codex model constraints shared by configuration and probes. */
public final class CodexModelBackendPolicy {

    private static final Set<String> APP_SERVER_ULTRA_MODELS = Set.of(
            "codex-ultra",
            "codex-latest",
            "codex-terra",
            "gpt-5.6-sol",
            "gpt-5.6-terra");

    private CodexModelBackendPolicy() {
    }

    public static void validate(String workerBackend, String modelName,
                                Collection<String> availableModels) {
        String backend = ProviderRouteRegistry.canonicalWorkerBackend(workerBackend)
                .orElse(workerBackend == null ? "" : workerBackend.trim());
        if (!ProviderRouteRegistry.BACKEND_OPENAI_CODEX.equals(backend)
                && !ProviderRouteRegistry.BACKEND_OPENAI_CODEX_APP_SERVER.equals(backend)) {
            return;
        }
        validateModel(backend, modelName);
        if (availableModels != null) {
            availableModels.forEach(model -> validateModel(backend, model));
        }
    }

    public static void validateModel(String workerBackend, String model) {
        if (model == null || model.isBlank()) return;
        String backend = ProviderRouteRegistry.canonicalWorkerBackend(workerBackend)
                .orElse(workerBackend == null ? "" : workerBackend.trim());
        String normalized = model.trim().toLowerCase(Locale.ROOT);
        String baseModel = normalized.contains(":")
                ? normalized.substring(0, normalized.indexOf(':')) : normalized;
        if ("gpt-5.4-mini".equals(baseModel)) {
            throw new IllegalArgumentException(
                    "UNSUPPORTED_CODEX_MODEL: gpt-5.4-mini is retired");
        }
        boolean ultra = "codex-ultra".equals(normalized) || normalized.endsWith(":ultra");
        if (ProviderRouteRegistry.BACKEND_OPENAI_CODEX.equals(backend) && ultra) {
            throw new IllegalArgumentException(
                    "CODEX_ULTRA_APP_SERVER_REQUIRED: Ultra requires OPENAI_CODEX_APP_SERVER");
        }
        if (ProviderRouteRegistry.BACKEND_OPENAI_CODEX_APP_SERVER.equals(backend)
                && ultra && !APP_SERVER_ULTRA_MODELS.contains(baseModel)) {
            throw new IllegalArgumentException(
                    "CODEX_APP_SERVER_MODEL_UNSUPPORTED: Ultra is supported only for Codex Sol or Terra");
        }
    }
}
