package com.foggy.navigator.agent.framework.diagnostic;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ErrorDiagnosticSanitizerTest {

    @Test
    void redactsCredentialsUrlsPathsAndIdentityHints() {
        String raw = "Bearer sk-secret123 api_key=abc123456 https://user:pass@example.com/x?q=secret "
                + "/home/sa/workspace/project/file.java user@example.com 10.0.0.8";

        String sanitized = ErrorDiagnosticSanitizer.sanitize(raw);

        assertFalse(sanitized.contains("secret123"));
        assertFalse(sanitized.contains("abc123456"));
        assertFalse(sanitized.contains("/home/sa"));
        assertFalse(sanitized.contains("example.com"));
        assertTrue(sanitized.contains("[credential]"));
        assertTrue(sanitized.contains("[url]"));
        assertTrue(sanitized.contains("[path]"));
        assertTrue(sanitized.contains("[email]"));
        assertTrue(sanitized.contains("[ip]"));
    }

    @Test
    void classifiesStableCodes() {
        assertEquals(ErrorCategory.AUTHENTICATION,
                ErrorDiagnosticSanitizer.classify("CODEX_AUTH_REQUIRED"));
        assertEquals(ErrorCategory.RATE_LIMIT,
                ErrorDiagnosticSanitizer.classify("CODEX_RATE_LIMITED"));
        assertEquals(ErrorCategory.NETWORK,
                ErrorDiagnosticSanitizer.classify("CODEX_WORKER_STREAM_DISCONNECTED"));
    }
}
