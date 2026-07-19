package com.foggy.navigator.sdk.cli;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Build-time packaged view of the canonical Navigator route manifest for the
 * CLI's narrow explain input guard. It is deliberately not an authorization
 * engine: Navigator revalidates every route/action and target server-side.
 */
final class TypedManagementExplainCatalog {
    static final String MANIFEST_RESOURCE = "/authorization/route-manifest-v1.csv";

    private static final String TYPED_MANAGEMENT_SURFACE = "TYPED_MANAGEMENT_AUTH";
    private static final String CANONICAL_ENFORCE = "CANONICAL_ENFORCE";
    private static final String KEEP_DISPOSITION = "KEEP";
    private static final List<String> HEADER = List.of(
            "route_id", "deployment", "http_method", "path", "surface", "controller_method", "source",
            "current_guard", "current_target_predicate", "canonical_action", "target_accepted_principal_lanes",
            "target_resolver", "risk_tier", "migration_mode", "disposition", "review_status", "notes",
            "required_sections"
    );

    private final Map<String, String> actionsByRouteId;

    private TypedManagementExplainCatalog(Map<String, String> actionsByRouteId) {
        this.actionsByRouteId = Map.copyOf(actionsByRouteId);
    }

    static TypedManagementExplainCatalog load() {
        return fromCanonicalManifestBytes(readManifestResource());
    }

    static TypedManagementExplainCatalog fromCanonicalManifestBytes(byte[] bytes) {
        verifyProvenance(bytes);
        return new TypedManagementExplainCatalog(parseTypedManagementActions(bytes));
    }

    boolean matches(String routeId, String actionId) {
        return actionId != null && actionId.equals(actionsByRouteId.get(routeId));
    }

    Map<String, String> actionsByRouteId() {
        return actionsByRouteId;
    }

    private static byte[] readManifestResource() {
        try (InputStream input = TypedManagementExplainCatalog.class.getResourceAsStream(MANIFEST_RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("Typed-management canonical route manifest resource is unavailable");
            }
            return input.readAllBytes();
        } catch (IOException exception) {
            throw new IllegalStateException("Typed-management canonical route manifest cannot be read", exception);
        }
    }

    private static void verifyProvenance(byte[] bytes) {
        CliProvenance provenance = CliProvenance.load();
        if (!provenance.manifestSha256().equals(sha256(bytes))) {
            throw new IllegalStateException("Typed-management canonical route manifest checksum mismatch");
        }
        int entryCount = countManifestEntries(bytes);
        if (entryCount != provenance.manifestEntryCount()) {
            throw new IllegalStateException("Typed-management canonical route manifest entry count mismatch");
        }
    }

    static Map<String, String> parseTypedManagementActions(byte[] bytes) {
        Map<String, String> typedManagementActions = new LinkedHashMap<>();
        Set<String> allRouteIds = new HashSet<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new ByteArrayInputStream(bytes), StandardCharsets.UTF_8))) {
            String header = reader.readLine();
            if (!HEADER.equals(parseCsvLine(header))) {
                throw new IllegalStateException("Typed-management canonical route manifest header changed unexpectedly");
            }
            String line;
            int lineNumber = 1;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                List<String> values = parseCsvLine(line);
                if (values.size() != HEADER.size()) {
                    throw new IllegalStateException("Malformed typed-management canonical route manifest row " + lineNumber);
                }
                for (String value : values) {
                    if (value == null || value.isBlank()) {
                        throw new IllegalStateException("Blank typed-management canonical route manifest field at row "
                                + lineNumber);
                    }
                }
                String routeId = values.get(0);
                if (!allRouteIds.add(routeId)) {
                    throw new IllegalStateException("Duplicate typed-management canonical route id " + routeId);
                }
                if (TYPED_MANAGEMENT_SURFACE.equals(values.get(4))
                        && CANONICAL_ENFORCE.equals(values.get(13))
                        && KEEP_DISPOSITION.equals(values.get(14))) {
                    String previous = typedManagementActions.putIfAbsent(routeId, values.get(9));
                    if (previous != null) {
                        throw new IllegalStateException("Duplicate typed-management explain route id " + routeId);
                    }
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to parse typed-management canonical route manifest", exception);
        }
        if (typedManagementActions.isEmpty()) {
            throw new IllegalStateException("Typed-management canonical route manifest contains no explainable routes");
        }
        return typedManagementActions;
    }

    private static int countManifestEntries(byte[] bytes) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new ByteArrayInputStream(bytes), StandardCharsets.UTF_8))) {
            if (reader.readLine() == null) {
                throw new IllegalStateException("Typed-management canonical route manifest is empty");
            }
            int count = 0;
            while (reader.readLine() != null) {
                count++;
            }
            return count;
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to count typed-management canonical route manifest", exception);
        }
    }

    /** Small quote-aware parser matching the canonical route manifest format. */
    private static List<String> parseCsvLine(String line) {
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
            throw new IllegalStateException("Unclosed quote in typed-management canonical route manifest");
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
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
