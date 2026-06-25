package com.foggy.navigator.common.util;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Shared route metadata between model workerBackend and A2A/TaskQueryProvider types.
 */
public final class ProviderRouteRegistry {

    public static final String BACKEND_OPENAI_CODEX = "OPENAI_CODEX";
    public static final String BACKEND_CLAUDE_CODE = "CLAUDE_CODE";
    public static final String BACKEND_GEMINI_CLI = "GEMINI_CLI";
    public static final String BACKEND_LANGGRAPH_BIZ = "LANGGRAPH_BIZ";

    public static final String PROVIDER_CODEX_WORKER = "codex-worker";
    public static final String PROVIDER_CODEX_BIZ_WORKER = "codex-biz-worker";
    public static final String PROVIDER_CLAUDE_WORKER = "claude-worker";
    public static final String PROVIDER_GEMINI_WORKER = "gemini-worker";
    public static final String PROVIDER_LANGGRAPH_BIZ_WORKER = "langgraph-biz-worker";

    private static final Map<String, String> WORKER_BACKEND_TO_PROVIDER_TYPE = Map.of(
            BACKEND_OPENAI_CODEX, PROVIDER_CODEX_WORKER,
            BACKEND_CLAUDE_CODE, PROVIDER_CLAUDE_WORKER,
            BACKEND_GEMINI_CLI, PROVIDER_GEMINI_WORKER,
            BACKEND_LANGGRAPH_BIZ, PROVIDER_LANGGRAPH_BIZ_WORKER
    );

    private static final Set<String> KNOWN_PROVIDER_TYPES = Set.of(
            PROVIDER_CODEX_WORKER,
            PROVIDER_CODEX_BIZ_WORKER,
            PROVIDER_CLAUDE_WORKER,
            PROVIDER_GEMINI_WORKER,
            PROVIDER_LANGGRAPH_BIZ_WORKER
    );

    private static final Map<String, Set<String>> COMPATIBLE_EXECUTION_PROVIDERS_BY_MODEL_PROVIDER = Map.of(
            PROVIDER_CODEX_WORKER, Set.of(PROVIDER_CODEX_WORKER, PROVIDER_CODEX_BIZ_WORKER)
    );

    private ProviderRouteRegistry() {
    }

    public static Optional<String> providerTypeForWorkerBackend(String workerBackend) {
        return canonicalWorkerBackend(workerBackend)
                .map(WORKER_BACKEND_TO_PROVIDER_TYPE::get);
    }

    public static String providerTypeForWorkerBackendOrNull(String workerBackend) {
        return providerTypeForWorkerBackend(workerBackend).orElse(null);
    }

    public static boolean isKnownProviderType(String providerType) {
        String normalizedProviderType = trimToNull(providerType);
        return normalizedProviderType != null && KNOWN_PROVIDER_TYPES.contains(normalizedProviderType);
    }

    public static boolean isKnownWorkerBackend(String workerBackend) {
        return canonicalWorkerBackend(workerBackend).isPresent();
    }

    public static Optional<String> canonicalWorkerBackend(String workerBackend) {
        String normalizedWorkerBackend = normalizeWorkerBackend(workerBackend);
        if (normalizedWorkerBackend == null || !WORKER_BACKEND_TO_PROVIDER_TYPE.containsKey(normalizedWorkerBackend)) {
            return Optional.empty();
        }
        return Optional.of(normalizedWorkerBackend);
    }

    public static String canonicalWorkerBackendOrNull(String workerBackend) {
        return canonicalWorkerBackend(workerBackend).orElse(null);
    }

    public static Optional<String> workerBackendForRouteToken(String routeToken) {
        String normalizedRouteToken = normalizeWorkerBackend(routeToken);
        if (normalizedRouteToken == null) {
            return Optional.empty();
        }
        return switch (normalizedRouteToken) {
            case BACKEND_OPENAI_CODEX, "CODEX", "CODEX_WORKER", "CODEX_BIZ_WORKER" ->
                    Optional.of(BACKEND_OPENAI_CODEX);
            case BACKEND_CLAUDE_CODE, "CLAUDE", "CLAUDE_WORKER" -> Optional.of(BACKEND_CLAUDE_CODE);
            case BACKEND_GEMINI_CLI, "GEMINI", "GEMINI_WORKER" -> Optional.of(BACKEND_GEMINI_CLI);
            case BACKEND_LANGGRAPH_BIZ, "LANGGRAPH", "LANGGRAPH_BIZ_WORKER" -> Optional.of(BACKEND_LANGGRAPH_BIZ);
            default -> Optional.empty();
        };
    }

    public static String workerBackendForRouteTokenOrNull(String routeToken) {
        return workerBackendForRouteToken(routeToken).orElse(null);
    }

    public static boolean isModelProviderCompatible(String modelProviderType, String executionProviderType) {
        String normalizedModelProvider = trimToNull(modelProviderType);
        String normalizedExecutionProvider = trimToNull(executionProviderType);
        if (normalizedModelProvider == null || normalizedExecutionProvider == null) {
            return false;
        }
        if (Objects.equals(normalizedModelProvider, normalizedExecutionProvider)) {
            return true;
        }
        return COMPATIBLE_EXECUTION_PROVIDERS_BY_MODEL_PROVIDER
                .getOrDefault(normalizedModelProvider, Set.of())
                .contains(normalizedExecutionProvider);
    }

    public static Set<String> knownProviderTypes() {
        return KNOWN_PROVIDER_TYPES;
    }

    public static Set<String> knownWorkerBackends() {
        return WORKER_BACKEND_TO_PROVIDER_TYPE.keySet();
    }

    public static String normalizeWorkerBackend(String workerBackend) {
        String normalizedWorkerBackend = trimToNull(workerBackend);
        if (normalizedWorkerBackend == null) {
            return null;
        }
        return normalizedWorkerBackend
                .replace('-', '_')
                .toUpperCase(Locale.ROOT);
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
