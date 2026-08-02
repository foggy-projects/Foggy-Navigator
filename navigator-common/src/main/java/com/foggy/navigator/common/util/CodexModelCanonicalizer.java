package com.foggy.navigator.common.util;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Canonical Codex model identity used at provider-effect admission fences. */
public final class CodexModelCanonicalizer {

    private static final Map<String, String> PHYSICAL_MODEL_FAMILIES = Map.of(
            "codex-latest", "gpt-5.6-sol",
            "codex-terra", "gpt-5.6-terra",
            "codex-luna", "gpt-5.6-luna",
            "gpt-5.6-sol", "gpt-5.6-sol",
            "gpt-5.6-terra", "gpt-5.6-terra",
            "gpt-5.6-luna", "gpt-5.6-luna");
    private static final Set<String> REASONING_EFFORTS = Set.of(
            "low", "medium", "high", "xhigh", "extra-high", "max", "ultra");
    private static final Set<String> CODEX_PROVIDERS = Set.of(
            ProviderRouteRegistry.PROVIDER_CODEX_WORKER,
            ProviderRouteRegistry.PROVIDER_CODEX_APP_SERVER_WORKER,
            ProviderRouteRegistry.PROVIDER_CODEX_BIZ_WORKER);

    private CodexModelCanonicalizer() {
    }

    /**
     * Compares the physical model-family component of an already resolved and
     * granted Codex request. The exact modelConfigId remains a separate,
     * mandatory tuple member and owns the logical variant/reasoning grant.
     * Unknown aliases and malformed effort suffixes never canonicalize.
     */
    public static boolean matchesPhysicalTuple(
            String providerType, String manifestModel, String resolvedModel) {
        if (Objects.equals(manifestModel, resolvedModel)) {
            return true;
        }
        if (!CODEX_PROVIDERS.contains(providerType)) {
            return false;
        }
        Optional<String> manifestPhysical = physicalModelFamily(manifestModel);
        Optional<String> resolvedPhysical = physicalModelFamily(resolvedModel);
        return manifestPhysical.isPresent()
                && manifestPhysical.equals(resolvedPhysical);
    }

    public static Optional<String> physicalModelFamily(String model) {
        if (model == null || model.isBlank() || !model.equals(model.trim())) {
            return Optional.empty();
        }
        int separator = model.lastIndexOf(':');
        String family = separator > 0 ? model.substring(0, separator) : model;
        if (separator > 0) {
            String effort = model.substring(separator + 1);
            if (!REASONING_EFFORTS.contains(effort)) {
                return Optional.empty();
            }
        }
        return Optional.ofNullable(PHYSICAL_MODEL_FAMILIES.get(family));
    }

    public static boolean isCanonicalPhysicalModelFamily(String model) {
        if (model == null) {
            return false;
        }
        return physicalModelFamily(model)
                .map(model::equals)
                .orElse(false);
    }
}
