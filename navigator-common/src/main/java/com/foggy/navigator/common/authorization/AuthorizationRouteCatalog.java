package com.foggy.navigator.common.authorization;

import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Source-controlled catalog for the approved route manifest. It is deliberately
 * deployment-aware: identical paths in the launcher and Observer BFF remain
 * distinct route identities.
 */
@Component
public class AuthorizationRouteCatalog {

    public static final String RESOURCE_PATH = "authorization/route-manifest-v1.csv";
    public static final int EXPECTED_ENTRY_COUNT = 462;
    public static final String EXPECTED_SHA_256 =
            "bb423a4705780bbf9e88cbe0b3b67d64e830d85a8e415640fee8a786e8d71e9e";
    public static final String DEPLOYMENT_LAUNCHER = "NAVIGATOR_LAUNCHER";
    public static final String DEPLOYMENT_OBSERVER_BFF = "OBSERVER_BFF";

    private static final List<String> HEADER = List.of(
            "route_id", "deployment", "http_method", "path", "surface", "controller_method", "source",
            "current_guard", "current_target_predicate", "canonical_action", "target_accepted_principal_lanes",
            "target_resolver", "risk_tier", "migration_mode", "disposition", "review_status", "notes",
            "required_sections"
    );

    private final Map<String, AuthorizationRouteManifestEntry> entriesByRouteId;
    private final String checksum;

    public AuthorizationRouteCatalog() {
        this(readResource());
    }

    AuthorizationRouteCatalog(byte[] bytes) {
        this(bytes, true);
    }

    /** Package-visible only for parser contract tests; production always verifies the frozen digest. */
    AuthorizationRouteCatalog(byte[] bytes, boolean verifyChecksum) {
        this.checksum = sha256(bytes);
        if (verifyChecksum && !EXPECTED_SHA_256.equals(checksum)) {
            throw new IllegalStateException("Authorization route manifest checksum mismatch");
        }
        this.entriesByRouteId = Collections.unmodifiableMap(parse(bytes));
        if (entriesByRouteId.size() != EXPECTED_ENTRY_COUNT) {
            throw new IllegalStateException("Authorization route manifest must contain "
                    + EXPECTED_ENTRY_COUNT + " entries but contained " + entriesByRouteId.size());
        }
    }

    public String checksum() {
        return checksum;
    }

    public int size() {
        return entriesByRouteId.size();
    }

    public Map<String, AuthorizationRouteManifestEntry> entriesByRouteId() {
        return entriesByRouteId;
    }

    public Optional<AuthorizationRouteManifestEntry> findByRouteId(String routeId) {
        return Optional.ofNullable(entriesByRouteId.get(routeId));
    }

    public Optional<AuthorizationRouteManifestEntry> findByDeploymentMethodAndPath(String deployment,
                                                                                     String method,
                                                                                     String path) {
        return findByRouteId(routeId(deployment, method, path));
    }

    public static String routeId(String deployment, String method, String path) {
        if (deployment == null || method == null || path == null) {
            return "";
        }
        String normalizedMethod = method.toLowerCase(Locale.ROOT);
        if (DEPLOYMENT_OBSERVER_BFF.equals(deployment)) {
            return "mvc:observer-bff:" + normalizedMethod + ":" + path;
        }
        return "mvc:" + normalizedMethod + ":" + path;
    }

    private static byte[] readResource() {
        try (InputStream stream = AuthorizationRouteCatalog.class.getClassLoader()
                .getResourceAsStream(RESOURCE_PATH)) {
            if (stream == null) {
                throw new IllegalStateException("Missing authorization route manifest resource " + RESOURCE_PATH);
            }
            return stream.readAllBytes();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read authorization route manifest", exception);
        }
    }

    private static Map<String, AuthorizationRouteManifestEntry> parse(byte[] bytes) {
        Map<String, AuthorizationRouteManifestEntry> parsed = new LinkedHashMap<>();
        Map<String, Set<AuthorizationRequiredSection>> requiredSectionsByCanonicalAction = new LinkedHashMap<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new java.io.ByteArrayInputStream(bytes), StandardCharsets.UTF_8))) {
            String firstLine = reader.readLine();
            if (!HEADER.equals(parseCsvLine(firstLine))) {
                throw new IllegalStateException("Authorization route manifest header changed unexpectedly");
            }
            String line;
            int lineNumber = 1;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                List<String> values = parseCsvLine(line);
                if (values.size() != HEADER.size()) {
                    throw new IllegalStateException("Malformed authorization route manifest row " + lineNumber);
                }
                for (String value : values) {
                    if (value == null || value.isBlank()) {
                        throw new IllegalStateException("Blank required authorization route manifest field at row " + lineNumber);
                    }
                }
                Set<AuthorizationRequiredSection> requiredSections = parseRequiredSections(values.get(17), lineNumber);
                AuthorizationRouteManifestEntry entry = new AuthorizationRouteManifestEntry(
                        values.get(0), values.get(1), values.get(2), values.get(3), values.get(4), values.get(5),
                        values.get(6), values.get(7), values.get(8), values.get(9), values.get(10), values.get(11),
                        values.get(12), values.get(13), values.get(14), values.get(15), values.get(16),
                        requiredSections
                );
                if (parsed.putIfAbsent(entry.routeId(), entry) != null) {
                    throw new IllegalStateException("Duplicate authorization route id " + entry.routeId());
                }
                Set<AuthorizationRequiredSection> existing = requiredSectionsByCanonicalAction
                        .putIfAbsent(entry.canonicalAction(), entry.requiredSections());
                if (existing != null && !existing.equals(entry.requiredSections())) {
                    throw new IllegalStateException("Inconsistent required sections for canonical action "
                            + entry.canonicalAction());
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to parse authorization route manifest", exception);
        }
        return parsed;
    }

    static Set<AuthorizationRequiredSection> parseRequiredSections(String declaration, int lineNumber) {
        if (declaration == null || declaration.isBlank()) {
            throw new IllegalStateException("Missing explicit required_sections declaration at row " + lineNumber);
        }
        if ("NONE".equals(declaration)) {
            return Set.of();
        }
        EnumSet<AuthorizationRequiredSection> sections = EnumSet.noneOf(AuthorizationRequiredSection.class);
        String[] tokens = declaration.split("\\|", -1);
        for (String token : tokens) {
            if (token.isBlank()) {
                throw new IllegalStateException("Blank required section token at row " + lineNumber);
            }
            if ("NONE".equals(token)) {
                throw new IllegalStateException("NONE cannot be combined with required sections at row " + lineNumber);
            }
            final AuthorizationRequiredSection section;
            try {
                section = AuthorizationRequiredSection.valueOf(token);
            } catch (IllegalArgumentException exception) {
                throw new IllegalStateException("Unknown required section " + token + " at row " + lineNumber,
                        exception);
            }
            if (!sections.add(section)) {
                throw new IllegalStateException("Duplicate required section " + token + " at row " + lineNumber);
            }
        }
        return Set.copyOf(sections);
    }

    /** Small quote-aware parser: the approved CSV has quoted commas, so split(',') is unsafe. */
    static List<String> parseCsvLine(String line) {
        if (line == null) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        StringBuilder value = new StringBuilder();
        boolean quoted = false;
        for (int index = 0; index < line.length(); index++) {
            char current = line.charAt(index);
            if (current == '"') {
                if (quoted && index + 1 < line.length() && line.charAt(index + 1) == '"') {
                    value.append('"');
                    index++;
                } else {
                    quoted = !quoted;
                }
            } else if (current == ',' && !quoted) {
                values.add(value.toString());
                value.setLength(0);
            } else {
                value.append(current);
            }
        }
        if (quoted) {
            throw new IllegalStateException("Unclosed quote in authorization route manifest");
        }
        values.add(value.toString());
        return values;
    }

    private static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                hex.append(String.format("%02x", value));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
