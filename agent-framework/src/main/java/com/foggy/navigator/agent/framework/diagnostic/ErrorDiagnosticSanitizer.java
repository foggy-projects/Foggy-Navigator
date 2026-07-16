package com.foggy.navigator.agent.framework.diagnostic;

import java.util.Locale;
import java.util.regex.Pattern;

/** Central, versioned last-line sanitizer for bounded diagnostic text. */
public final class ErrorDiagnosticSanitizer {

    public static final int VERSION = ErrorDiagnosticInput.REDACTION_VERSION;
    public static final int MAX_DIAGNOSTIC_LENGTH = 1024;

    private static final Pattern CONTROL = Pattern.compile("[\\p{Cc}&&[^\\r\\n\\t]]");
    private static final Pattern AUTHORIZATION = Pattern.compile(
            "(?i)\\b(?:authorization\\s*[:=]\\s*)?(?:bearer|basic)\\s+[a-z0-9._~+/=-]{6,}");
    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)\\b(?:api[_-]?key|token|secret|password|cookie|sharing[_-]?key|task[_-]?token|credential)"
                    + "\\s*[:=]\\s*[^\\s,;]+"
    );
    private static final Pattern URL = Pattern.compile("(?i)https?://[^\\s]+|[a-z][a-z0-9+.-]*://[^\\s]+");
    private static final Pattern WINDOWS_PATH = Pattern.compile("(?i)(?:[a-z]:\\\\|\\\\\\\\)[^\\s\"']+");
    private static final Pattern UNIX_PATH = Pattern.compile("(?<![a-zA-Z0-9._-])/(?:home|Users|var|tmp|opt|etc|workspace|mnt)/[^\\s\"']+");
    private static final Pattern EMAIL = Pattern.compile("(?i)\\b[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,}\\b");
    private static final Pattern IPV4 = Pattern.compile("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b");

    private ErrorDiagnosticSanitizer() {
    }

    public static String sanitize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String sanitized = CONTROL.matcher(value).replaceAll("");
        sanitized = AUTHORIZATION.matcher(sanitized).replaceAll("[credential]");
        sanitized = SECRET_ASSIGNMENT.matcher(sanitized).replaceAll("[credential]");
        sanitized = URL.matcher(sanitized).replaceAll("[url]");
        sanitized = WINDOWS_PATH.matcher(sanitized).replaceAll("[path]");
        sanitized = UNIX_PATH.matcher(sanitized).replaceAll("[path]");
        sanitized = EMAIL.matcher(sanitized).replaceAll("[email]");
        sanitized = IPV4.matcher(sanitized).replaceAll("[ip]");
        sanitized = sanitized.replaceAll("[ \\t]+", " ").replaceAll("(?:\\r?\\n){3,}", "\n\n").trim();
        if (sanitized.isEmpty()) {
            return null;
        }
        return sanitized.length() <= MAX_DIAGNOSTIC_LENGTH
                ? sanitized
                : sanitized.substring(0, MAX_DIAGNOSTIC_LENGTH - 1) + "…";
    }

    public static String sanitizeType(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (!normalized.matches("[A-Za-z0-9_$.-]{1,160}")) {
            return "UnknownError";
        }
        return normalized;
    }

    public static ErrorCategory classify(String stableCode) {
        String code = stableCode == null ? "" : stableCode.toUpperCase(Locale.ROOT);
        if (containsAny(code, "AUTH", "UNAUTHORIZED", "CREDENTIAL", "LOGIN")) return ErrorCategory.AUTHENTICATION;
        if (containsAny(code, "FORBIDDEN", "PERMISSION", "DENIED")) return ErrorCategory.AUTHORIZATION;
        if (containsAny(code, "CONFIG", "MODEL_UNSUPPORTED", "NOT_CONFIGURED", "INVALID_REQUEST")) return ErrorCategory.CONFIGURATION;
        if (containsAny(code, "RATE_LIMIT", "QUOTA", "TOO_MANY")) return ErrorCategory.RATE_LIMIT;
        if (containsAny(code, "TIMEOUT", "TIMED_OUT")) return ErrorCategory.TIMEOUT;
        if (containsAny(code, "CANCEL", "ABORT")) return ErrorCategory.CANCELLED;
        if (containsAny(code, "NETWORK", "UNREACHABLE", "DISCONNECT", "STREAM")) return ErrorCategory.NETWORK;
        if (!code.isBlank()) return ErrorCategory.RUNTIME;
        return ErrorCategory.UNKNOWN;
    }

    private static boolean containsAny(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(needle)) return true;
        }
        return false;
    }
}
