package com.foggy.navigator.common.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodexModelCanonicalizerTest {

    @Test
    void logicalLunaVariantMatchesItsPhysicalModelFamily() {
        assertTrue(CodexModelCanonicalizer.matchesPhysicalTuple(
                ProviderRouteRegistry.PROVIDER_CODEX_WORKER,
                "gpt-5.6-luna", "codex-luna:high"));
        assertTrue(CodexModelCanonicalizer.matchesPhysicalTuple(
                ProviderRouteRegistry.PROVIDER_CODEX_APP_SERVER_WORKER,
                "codex-luna:high", "gpt-5.6-luna"));
    }

    @Test
    void canonicalizationDoesNotBroadenProviderOrModelFamily() {
        assertFalse(CodexModelCanonicalizer.matchesPhysicalTuple(
                ProviderRouteRegistry.PROVIDER_CLAUDE_WORKER,
                "gpt-5.6-luna", "codex-luna:high"));
        assertFalse(CodexModelCanonicalizer.matchesPhysicalTuple(
                ProviderRouteRegistry.PROVIDER_CODEX_WORKER,
                "gpt-5.6-luna", "codex-terra:high"));
        assertFalse(CodexModelCanonicalizer.matchesPhysicalTuple(
                ProviderRouteRegistry.PROVIDER_CODEX_WORKER,
                "gpt-5.6-luna", "codex-luna:not-an-effort"));
        assertFalse(CodexModelCanonicalizer.matchesPhysicalTuple(
                ProviderRouteRegistry.PROVIDER_CODEX_WORKER,
                "gpt-5.6-luna", " custom-luna:high"));
    }

    @Test
    void manifestPhysicalFamilyMustBeCanonicalAndContentFree() {
        assertTrue(CodexModelCanonicalizer.isCanonicalPhysicalModelFamily(
                "gpt-5.6-luna"));
        assertFalse(CodexModelCanonicalizer.isCanonicalPhysicalModelFamily(
                "gpt-5.6-luna:high"));
        assertFalse(CodexModelCanonicalizer.isCanonicalPhysicalModelFamily(
                "codex-luna"));
        assertFalse(CodexModelCanonicalizer.isCanonicalPhysicalModelFamily(null));
    }
}
