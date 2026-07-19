package com.foggy.navigator.common.authorization;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression coverage for BUG-002. The policy must be declared by the
 * source-controlled catalog, never inferred by evaluator naming heuristics.
 */
class AuthorizationRequiredSectionCatalogRegressionTest {

    private static final Map<String, Boolean> RUNTIME_CAPABILITY_BY_ROUTE = Map.ofEntries(
            Map.entry("mvc:get:/api/v1/open/accounts/me/context-files", false),
            Map.entry("mvc:get:/api/v1/open/accounts/me/context-files/{fileName}", false),
            Map.entry("mvc:put:/api/v1/open/accounts/me/context-files/{fileName}", false),
            Map.entry("mvc:post:/api/v1/open/accounts/me/skill-bundles/sync", false),
            Map.entry("mvc:post:/api/v1/open/agents/{agentId}/ask", true),
            Map.entry("mvc:post:/api/v1/open/agents/{agentId}/preflight", false),
            Map.entry("mvc:get:/api/v1/open/agents/{agentId}/sessions", false),
            Map.entry("mvc:get:/api/v1/open/agents/{agentId}/sessions/{contextId}/messages", false),
            Map.entry("mvc:get:/api/v1/open/agents/{agentId}/tasks", false),
            Map.entry("mvc:get:/api/v1/open/agents/{agentId}/tasks/{taskId}", false),
            Map.entry("mvc:post:/api/v1/open/agents/{agentId}/tasks/{taskId}/cancel", false),
            Map.entry("mvc:get:/api/v1/open/agents/{agentId}/tasks/{taskId}/diagnostics", false),
            Map.entry("mvc:get:/api/v1/open/agents/{agentId}/tasks/{taskId}/evidence", false),
            Map.entry("mvc:get:/api/v1/open/agents/{agentId}/tasks/{taskId}/messages", false),
            Map.entry("mvc:get:/api/v1/open/business-agent/sessions", false),
            Map.entry("mvc:get:/api/v1/open/business-agent/sessions/{contextId}/messages", false),
            Map.entry("mvc:post:/api/v1/open/client-apps/runtime-token", false),
            Map.entry("mvc:get:/api/v1/open/frame-reports", false),
            Map.entry("mvc:get:/api/v1/open/skills/{skillId}/files/slice", false),
            Map.entry("mvc:get:/api/v1/open/skills/{skillId}/files/tree", false)
    );

    private static final String RUNTIME_TOKEN_EXCHANGE_ROUTE =
            "mvc:post:/api/v1/open/client-apps/runtime-token";

    private static final Map<String, String> WORKER_GATEWAY_ACTION_BY_ROUTE = Map.of(
            "mvc:get:/internal/worker-gateway/v1/business-functions", "gateway.function.list",
            "mvc:post:/internal/worker-gateway/v1/business-functions/{functionId}/invoke", "gateway.function.invoke",
            "mvc:get:/internal/worker-gateway/v1/business-functions/{functionId}/schema", "gateway.function.schema.read",
            "mvc:post:/internal/worker-gateway/v1/tool-messages", "gateway.tool-message.report"
    );

    private static final Set<AuthorizationRequiredSection> WORKER_GATEWAY_REQUIRED_SECTIONS = Set.of(
            AuthorizationRequiredSection.PRINCIPAL,
            AuthorizationRequiredSection.CREDENTIAL,
            AuthorizationRequiredSection.TRUST,
            AuthorizationRequiredSection.TARGET,
            AuthorizationRequiredSection.CAPABILITY,
            AuthorizationRequiredSection.WORKER_ROUTE);

    private final AuthorizationRouteCatalog catalog = new AuthorizationRouteCatalog();

    @Test
    void everyCatalogRowMustCarryAnExplicitRequiredSectionDeclaration() throws IOException {
        List<List<String>> rows = sourceRows();
        List<String> header = rows.get(0);

        assertTrue(header.contains("required_sections"),
                "BUG-002: every catalog row needs an explicit required_sections declaration");
        int requiredSectionsIndex = header.indexOf("required_sections");
        assertEquals(AuthorizationRouteCatalog.EXPECTED_ENTRY_COUNT, rows.size() - 1);
        assertTrue(rows.subList(1, rows.size()).stream()
                        .map(row -> row.get(requiredSectionsIndex))
                        .allMatch(value -> value != null && !value.isBlank()),
                "NONE must be written explicitly when an action requires no sparse typed section");
        assertTrue(rows.subList(1, rows.size()).stream().allMatch(row -> row.size() == header.size()),
                "every row must retain the complete catalog schema");
    }

    @Test
    void parserRejectsAmbiguousRequiredSectionDeclarations() {
        assertEquals(Set.of(), AuthorizationRouteCatalog.parseRequiredSections("NONE", 2));
        assertEquals(EnumSet.of(AuthorizationRequiredSection.PRINCIPAL,
                        AuthorizationRequiredSection.CAPABILITY),
                AuthorizationRouteCatalog.parseRequiredSections("PRINCIPAL|CAPABILITY", 2));

        for (String declaration : List.of("", "UNKNOWN_SECTION", "PRINCIPAL|PRINCIPAL", "NONE|PRINCIPAL",
                "PRINCIPAL||TARGET")) {
            assertThrows(IllegalStateException.class,
                    () -> AuthorizationRouteCatalog.parseRequiredSections(declaration, 2),
                    () -> "catalog must reject ambiguous declaration " + declaration);
        }
    }

    @Test
    void duplicateCanonicalActionsCannotDivergeOnRequiredSections() throws IOException {
        String source = sourceManifest();
        List<String> lines = new ArrayList<>(source.lines().toList());
        List<String> header = AuthorizationRouteCatalog.parseCsvLine(lines.get(0));
        int canonicalActionIndex = header.indexOf("canonical_action");
        int requiredSectionsIndex = header.indexOf("required_sections");
        Map<String, Integer> actionCounts = new LinkedHashMap<>();
        for (String line : lines.subList(1, lines.size())) {
            List<String> row = AuthorizationRouteCatalog.parseCsvLine(line);
            actionCounts.merge(row.get(canonicalActionIndex), 1, Integer::sum);
        }
        String duplicateAction = actionCounts.entrySet().stream()
                .filter(entry -> entry.getValue() > 1)
                .map(Map.Entry::getKey)
                .findFirst()
                .orElseThrow();

        boolean changed = false;
        for (int index = 1; index < lines.size(); index++) {
            List<String> row = AuthorizationRouteCatalog.parseCsvLine(lines.get(index));
            if (!duplicateAction.equals(row.get(canonicalActionIndex))) {
                continue;
            }
            String replacement = "NONE".equals(row.get(requiredSectionsIndex)) ? "PRINCIPAL" : "NONE";
            lines.set(index, lines.get(index).substring(0, lines.get(index).lastIndexOf(',') + 1) + replacement);
            changed = true;
            break;
        }
        assertTrue(changed);

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> new AuthorizationRouteCatalog(String.join("\n", lines).getBytes(StandardCharsets.UTF_8), false));
        assertTrue(exception.getMessage().contains("Inconsistent required sections for canonical action"));
    }

    @Test
    void sourceAndEvidenceManifestRemainByteIdenticalAtTheFrozenCountAndHash() throws Exception {
        Path root = repositoryRoot();
        Path source = root.resolve("navigator-common/src/main/resources/").resolve(AuthorizationRouteCatalog.RESOURCE_PATH);
        Path evidence = root.resolve("docs/version-tracker/1.4.3-SNAPSHOT/evidence/GOV-001-p0.5-method-route-manifest.csv");
        byte[] sourceBytes = Files.readAllBytes(source);

        assertArrayEquals(sourceBytes, Files.readAllBytes(evidence));
        assertEquals(421, Files.readAllLines(source).size());
        assertEquals(AuthorizationRouteCatalog.EXPECTED_ENTRY_COUNT, sourceRows().size() - 1);
        assertEquals(AuthorizationRouteCatalog.EXPECTED_SHA_256, sha256(sourceBytes));
    }

    @Test
    void runtimeCapabilityClassificationIsAnExactTwentyIngressCatalogSnapshot() {
        Map<String, Boolean> actualCapabilityByRoute = catalog.entriesByRouteId().values().stream()
                .filter(entry -> RUNTIME_CAPABILITY_BY_ROUTE.containsKey(entry.routeId()))
                .collect(Collectors.toMap(AuthorizationRouteManifestEntry::routeId,
                        AuthorizationRouteManifestEntry::requiresCapability));

        assertEquals(RUNTIME_CAPABILITY_BY_ROUTE, actualCapabilityByRoute,
                "the catalog classifies each approved runtime ingress by its explicit route declaration");
    }

    @Test
    void workerGatewayRequirementsRemainAnExactExplicitCatalogSnapshot() {
        Map<String, AuthorizationRouteManifestEntry> gatewayEntries = catalog.entriesByRouteId().values().stream()
                .filter(entry -> WORKER_GATEWAY_ACTION_BY_ROUTE.containsKey(entry.routeId()))
                .collect(Collectors.toMap(AuthorizationRouteManifestEntry::routeId, entry -> entry));

        assertEquals(WORKER_GATEWAY_ACTION_BY_ROUTE.keySet(), gatewayEntries.keySet());
        assertEquals(WORKER_GATEWAY_ACTION_BY_ROUTE, gatewayEntries.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().canonicalAction())));
        assertTrue(gatewayEntries.values().stream()
                .allMatch(entry -> entry.requiredSections().equals(WORKER_GATEWAY_REQUIRED_SECTIONS)),
                "every Worker Gateway action explicitly requires both task capability and worker route");
    }

    @Test
    void catalogDeclaresTheApprovedSparseTypedSectionFamilies() {
        List<AuthorizationRouteManifestEntry> entries = List.copyOf(catalog.entriesByRouteId().values());
        Set<AuthorizationRequiredSection> base = EnumSet.of(
                AuthorizationRequiredSection.PRINCIPAL,
                AuthorizationRequiredSection.CREDENTIAL,
                AuthorizationRequiredSection.TRUST,
                AuthorizationRequiredSection.TARGET);

        assertEquals(6, entries.stream().filter(entry -> entry.requiredSections().isEmpty()).count(),
                "public/framework rows must say NONE explicitly");
        assertEquals(201, entries.stream().filter(entry -> entry.requires(AuthorizationRequiredSection.AUTHORITY)).count());
        assertEquals(125, entries.stream().filter(entry -> entry.requires(AuthorizationRequiredSection.PLATFORM_GRANT)).count());
        assertEquals(125, entries.stream().filter(entry -> entry.requires(AuthorizationRequiredSection.TENANT_AUTHORITY)).count());
        assertEquals(19, entries.stream().filter(entry -> entry.requires(AuthorizationRequiredSection.DELEGATION)).count());
        assertTrue(entries.stream()
                        .filter(entry -> entry.requires(AuthorizationRequiredSection.PLATFORM_GRANT))
                        .allMatch(entry -> entry.requires(AuthorizationRequiredSection.AUTHORITY)
                                && entry.requires(AuthorizationRequiredSection.TENANT_AUTHORITY)),
                "platform dynamic tenant actions require authority, platform grant, and tenant authority together");
        Set<String> expectedDelegationRoutes = RUNTIME_CAPABILITY_BY_ROUTE.keySet().stream()
                .filter(routeId -> !RUNTIME_TOKEN_EXCHANGE_ROUTE.equals(routeId))
                .collect(Collectors.toSet());
        Map<String, AuthorizationRouteManifestEntry> delegationEntries = entries.stream()
                .filter(entry -> entry.requires(AuthorizationRequiredSection.DELEGATION))
                .collect(Collectors.toMap(AuthorizationRouteManifestEntry::routeId, entry -> entry));
        assertEquals(expectedDelegationRoutes, delegationEntries.keySet());
        assertTrue(delegationEntries.values().stream()
                        .allMatch(entry -> entry.requiredSections().containsAll(base)),
                "delegation is declared per catalog entry, not inferred by evaluator naming logic");
        assertTrue(entries.stream()
                        .filter(entry -> AuthorizationRouteCatalog.DEPLOYMENT_OBSERVER_BFF.equals(entry.deployment()))
                        .allMatch(entry -> entry.requiredSections().equals(base)),
                "Observer BFF remains catalog-and-test-only with its approved sparse declaration");
    }

    private static List<List<String>> sourceRows() throws IOException {
        return sourceManifest().lines()
                .map(AuthorizationRouteCatalog::parseCsvLine)
                .collect(Collectors.toList());
    }

    private static String sourceManifest() throws IOException {
        try (InputStream stream = AuthorizationRequiredSectionCatalogRegressionTest.class.getClassLoader()
                .getResourceAsStream(AuthorizationRouteCatalog.RESOURCE_PATH)) {
            assertTrue(stream != null, "catalog resource must exist");
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("pom.xml"))
                    && Files.isRegularFile(current.resolve("docs/version-tracker/1.4.3-SNAPSHOT/README.md"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Unable to find repository root for manifest evidence verification");
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
            throw new IllegalStateException(exception);
        }
    }
}
