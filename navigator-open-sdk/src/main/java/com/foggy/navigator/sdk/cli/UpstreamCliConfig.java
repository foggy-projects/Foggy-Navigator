package com.foggy.navigator.sdk.cli;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

final class UpstreamCliConfig {
    static final String DEFAULT_BASE_URL = "http://localhost:8112";

    enum LocalState {
        VALID,
        INVALID,
        UNVERIFIED
    }

    enum TypedCredentialSource {
        DIRECT,
        EXPLICIT_ENV,
        MISSING,
        EXPLICIT_ENV_MISSING,
        AMBIGUOUS
    }

    private static final Set<String> LEGACY_CREDENTIAL_KEYS = Set.of(
            "NAVI_ADMIN_API_KEY", "NAVIGATOR_ADMIN_API_KEY",
            "NAVI_ADMIN_TOKEN", "NAVIGATOR_ADMIN_TOKEN",
            "NAVI_OPERATOR_API_KEY", "NAVIGATOR_OPERATOR_API_KEY",
            "NAVI_CONTROL_API_KEY", "NAVIGATOR_CONTROL_API_KEY",
            "NAVI_USER_API_KEY",
            "NAVI_CLIENT_APP_KEY", "CLIENT_APP_KEY",
            "NAVI_CLIENT_APP_SECRET", "CLIENT_APP_SECRET",
                "NAVI_CLIENT_APP_ACCESS_TOKEN", "CLIENT_APP_RUNTIME_TOKEN",
                "NAVI_CLIENT_APP_RUNTIME_TOKEN",
                "NAVI_UPSTREAM_USER_TOKEN", "TMS_STAFF_SESSION_TOKEN",
                "NAVI_ADMIN_KEY_CLAIM_TOKEN", "NAVI_LLM_API_KEY",
                "NAVI_WORKER_CREDENTIAL", "NAVI_TASK_SCOPED_TOKEN", "NAVI_RUNTIME_CREDENTIAL");

    private final Map<String, String> values;
    private final Path profilePath;
    private final Path cwd;
    private final TypedCredentialSource typedCredentialSource;
    private final List<String> extraSensitiveValues;

    private UpstreamCliConfig(Map<String, String> values,
                              Path profilePath,
                              Path cwd,
                              TypedCredentialSource typedCredentialSource,
                              List<String> extraSensitiveValues) {
        this.values = values;
        this.profilePath = profilePath;
        this.cwd = cwd;
        this.typedCredentialSource = typedCredentialSource;
        this.extraSensitiveValues = List.copyOf(extraSensitiveValues);
    }

    static UpstreamCliConfig load(CliArguments args, Map<String, String> env, Path cwd) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("NAVI_BASE_URL", DEFAULT_BASE_URL);
        values.put("TMS_WEB_BASE_URL", "http://localhost:12580");
        values.put("BASIC_BASE_URL", "http://localhost:10001");
        values.put("NAVI_TENANT_ID", "");
        values.put("NAVI_CLIENT_APP_ID", "");
        values.put("NAVI_AGENT_CODE", "");
        values.put("NAVI_POLL_INTERVAL_SECONDS", "4");
        values.put("NAVI_E2E_MOCK_LLM_URL", "http://localhost:8200");

        Path profile = resolveProfilePath(args, env, cwd);
        if (profile != null && Files.exists(profile)) {
            values.putAll(readProfile(profile));
            applyAliases(values);
        }
        for (String key : envKeys()) {
            if (env.containsKey(key) && hasText(env.get(key))) {
                values.put(key, env.get(key));
            }
        }
        applyAliases(values);

        String directPrincipalCredential = values.get("NAVI_PRINCIPAL_CREDENTIAL");
        String explicitPrincipalCredentialEnv = args.option("principal-credential-env");
        String explicitPrincipalCredential = hasText(explicitPrincipalCredentialEnv)
                ? env.get(explicitPrincipalCredentialEnv) : null;
        TypedCredentialSource typedCredentialSource = resolveTypedCredentialSource(
                directPrincipalCredential, explicitPrincipalCredentialEnv, explicitPrincipalCredential);
        List<String> extraSensitiveValues = new ArrayList<>();
        addSensitiveValue(extraSensitiveValues, directPrincipalCredential);
        addSensitiveValue(extraSensitiveValues, explicitPrincipalCredential);

        applyOptions(values, args, env);
        return new UpstreamCliConfig(values, profile, cwd, typedCredentialSource, extraSensitiveValues);
    }

    String get(String key) {
        return values.get(key);
    }

    String required(String key, String description) {
        String value = get(key);
        if (!hasText(value)) {
            throw new UpstreamCliException(description + " is required (" + key + ")");
        }
        return value;
    }

    int pollIntervalSeconds() {
        String value = values.getOrDefault("NAVI_POLL_INTERVAL_SECONDS", "4");
        try {
            return Math.max(1, Integer.parseInt(value));
        } catch (NumberFormatException e) {
            throw new UpstreamCliException("NAVI_POLL_INTERVAL_SECONDS must be a positive integer");
        }
    }

    Path profilePath() {
        return profilePath;
    }

    boolean profileIsGitIgnored() {
        if (profilePath == null) {
            return true;
        }
        if (!Files.exists(profilePath)) {
            return true;
        }
        if (!isUnder(profilePath, cwd)) {
            return true;
        }
        return isUnder(profilePath, cwd.resolve("temp")) || GitIgnoreSupport.isIgnored(cwd, profilePath);
    }

    List<String> sensitiveValues() {
        List<String> secrets = new ArrayList<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (isSensitiveKey(entry.getKey()) && hasText(entry.getValue())) {
                secrets.add(entry.getValue());
            }
        }
        for (String key : sensitiveKeys()) {
            String value = values.get(key);
            if (hasText(value) && !secrets.contains(value)) {
                secrets.add(value);
            }
        }
        for (String value : extraSensitiveValues) {
            if (hasText(value) && !secrets.contains(value)) {
                secrets.add(value);
            }
        }
        return secrets;
    }

    boolean profileExists() {
        return profilePath != null && Files.exists(profilePath);
    }

    void setValue(String key, String value) {
        values.put(key, value);
    }

    void assertProfileWritable() {
        assertProfileWritable(profilePath);
    }

    void assertProfileWritable(Path targetProfile) {
        if (targetProfile == null) {
            throw new UpstreamCliException("Profile path is required");
        }
        if (!profileCanBeWrittenSafely(targetProfile)) {
            throw new UpstreamCliException("Profile path is not git-ignored: " + targetProfile);
        }
    }

    void writeProfileValue(String key, String value) {
        writeProfileValue(profilePath, key, value, true);
    }

    void writeProfileValue(Path targetProfile, String key, String value) {
        writeProfileValue(targetProfile, key, value, targetProfile != null && targetProfile.equals(profilePath));
    }

    void writeProfileValues(Path targetProfile, Map<String, String> updates, Set<String> removeKeys) {
        assertProfileWritable(targetProfile);
        if (updates == null || updates.isEmpty()) {
            throw new UpstreamCliException("Profile updates are required");
        }
        writeProfileValuesUnchecked(targetProfile, updates, removeKeys,
                targetProfile != null && targetProfile.equals(profilePath));
    }

    List<String> presentCredentialKeys(String... keys) {
        List<String> present = new ArrayList<>();
        for (String key : keys) {
            if (hasText(values.get(key))) {
                present.add(key);
            }
        }
        return present;
    }

    private void writeProfileValue(Path targetProfile, String key, String value, boolean updateLoadedValues) {
        assertProfileWritable(targetProfile);
        writeProfileValueUnchecked(targetProfile, key, value, updateLoadedValues);
    }

    private void writeProfileValueUnchecked(Path targetProfile, String key, String value, boolean updateLoadedValues) {
        writeProfileValuesUnchecked(targetProfile, Map.of(key, value), Set.of(), updateLoadedValues);
    }

    private void writeProfileValuesUnchecked(Path targetProfile,
                                            Map<String, String> updates,
                                            Set<String> removeKeys,
                                            boolean updateLoadedValues) {
        if (targetProfile == null) {
            throw new UpstreamCliException("Profile path is required");
        }
        try {
            Path parent = targetProfile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            List<String> lines = Files.exists(targetProfile)
                    ? Files.readAllLines(targetProfile, StandardCharsets.UTF_8)
                    : new ArrayList<>();
            Set<String> pendingUpdates = new LinkedHashSet<>(updates.keySet());
            for (int i = lines.size() - 1; i >= 0; i--) {
                String line = lines.get(i);
                String trimmed = line.trim();
                if (trimmed.startsWith("#") || trimmed.startsWith("!")) {
                    continue;
                }
                int equals = line.indexOf('=');
                if (equals <= 0) {
                    continue;
                }
                String existingKey = line.substring(0, equals).trim();
                if (removeKeys != null && removeKeys.contains(existingKey)) {
                    lines.remove(i);
                    continue;
                }
                if (updates.containsKey(existingKey)) {
                    if (pendingUpdates.remove(existingKey)) {
                        lines.set(i, existingKey + "=" + updates.get(existingKey));
                    } else {
                        lines.remove(i);
                    }
                }
            }
            for (String key : updates.keySet()) {
                if (pendingUpdates.contains(key)) {
                    lines.add(key + "=" + updates.get(key));
                }
            }
            Path temp = targetProfile.resolveSibling(targetProfile.getFileName() + ".tmp");
            Files.write(temp, lines, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            try {
                Files.move(temp, targetProfile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, targetProfile, StandardCopyOption.REPLACE_EXISTING);
            }
            if (updateLoadedValues) {
                if (removeKeys != null) {
                    removeKeys.forEach(values::remove);
                }
                values.putAll(updates);
            }
        } catch (IOException e) {
            throw new UpstreamCliException("Failed to write profile: " + targetProfile, e);
        }
    }

    Map<String, String> values() {
        return values;
    }

    TypedCredentialSource typedCredentialSource() {
        return typedCredentialSource;
    }

    String principalCredential() {
        return values.get("NAVI_PRINCIPAL_CREDENTIAL");
    }

    boolean hasLegacyCredentialSource() {
        return !presentLegacyCredentialKeys().isEmpty();
    }

    LocalState profileSafetyState() {
        if (!profileExists()) {
            return LocalState.UNVERIFIED;
        }
        return profileIsGitIgnored() ? LocalState.VALID : LocalState.INVALID;
    }

    LocalState typedMetadataState() {
        String[] metadata = {
                values.get("NAVI_NAVIGATOR_INSTANCE_ID"),
                values.get("NAVI_ENVIRONMENT_PROFILE"),
                values.get("NAVI_EXPECTED_PRINCIPAL_TYPE"),
                values.get("NAVI_EXPECTED_CREDENTIAL_LANE")};
        long present = Arrays.stream(metadata).filter(UpstreamCliConfig::hasText).count();
        if (present == 0) {
            return LocalState.UNVERIFIED;
        }
        if (present != metadata.length) {
            return LocalState.INVALID;
        }
        return compatiblePrincipalAndLane(
                values.get("NAVI_EXPECTED_PRINCIPAL_TYPE"),
                values.get("NAVI_EXPECTED_CREDENTIAL_LANE")) ? LocalState.VALID : LocalState.INVALID;
    }

    LocalState typedCredentialSourceState() {
        if (typedCredentialSource == TypedCredentialSource.AMBIGUOUS
                || typedCredentialSource == TypedCredentialSource.EXPLICIT_ENV_MISSING
                || hasLegacyCredentialSource() && typedCredentialSource != TypedCredentialSource.MISSING) {
            return LocalState.INVALID;
        }
        return typedCredentialSource == TypedCredentialSource.MISSING
                ? LocalState.UNVERIFIED : LocalState.VALID;
    }

    LocalState configState() {
        List<LocalState> states = List.of(profileSafetyState(), typedMetadataState(), typedCredentialSourceState());
        if (states.contains(LocalState.INVALID)) {
            return LocalState.INVALID;
        }
        return states.stream().allMatch(LocalState.VALID::equals)
                ? LocalState.VALID : LocalState.UNVERIFIED;
    }

    String legacyPlatformLaneAvailability() {
        return laneAvailability("NAVI_ADMIN_API_KEY", new String[]{
                "NAVI_CONTROL_API_KEY", "NAVIGATOR_CONTROL_API_KEY", "NAVI_ADMIN_TOKEN", "NAVIGATOR_ADMIN_TOKEN",
                "NAVI_OPERATOR_API_KEY", "NAVIGATOR_OPERATOR_API_KEY", "NAVI_CLIENT_APP_KEY", "CLIENT_APP_KEY",
                "NAVI_CLIENT_APP_SECRET", "CLIENT_APP_SECRET", "NAVI_CLIENT_APP_ACCESS_TOKEN",
                "NAVI_CLIENT_APP_RUNTIME_TOKEN", "CLIENT_APP_RUNTIME_TOKEN", "NAVI_PRINCIPAL_CREDENTIAL",
                "NAVI_USER_API_KEY", "NAVI_ADMIN_KEY_CLAIM_TOKEN", "NAVI_WORKER_CREDENTIAL", "NAVI_TASK_SCOPED_TOKEN"});
    }

    String clientAppControlLaneAvailability() {
        return laneAvailability("NAVI_CONTROL_API_KEY", new String[]{
                "NAVI_ADMIN_API_KEY", "NAVIGATOR_ADMIN_API_KEY", "NAVI_ADMIN_TOKEN", "NAVIGATOR_ADMIN_TOKEN",
                "NAVI_OPERATOR_API_KEY", "NAVIGATOR_OPERATOR_API_KEY", "NAVI_CLIENT_APP_KEY", "CLIENT_APP_KEY",
                "NAVI_CLIENT_APP_SECRET", "CLIENT_APP_SECRET", "NAVI_CLIENT_APP_ACCESS_TOKEN",
                "NAVI_CLIENT_APP_RUNTIME_TOKEN", "CLIENT_APP_RUNTIME_TOKEN", "NAVI_PRINCIPAL_CREDENTIAL",
                "NAVI_USER_API_KEY", "NAVI_ADMIN_KEY_CLAIM_TOKEN", "NAVI_WORKER_CREDENTIAL", "NAVI_TASK_SCOPED_TOKEN"});
    }

    String runtimeLaneAvailability() {
        List<String> foreign = presentCredentialKeys("NAVI_ADMIN_API_KEY", "NAVIGATOR_ADMIN_API_KEY",
                "NAVI_ADMIN_TOKEN", "NAVIGATOR_ADMIN_TOKEN", "NAVI_OPERATOR_API_KEY", "NAVIGATOR_OPERATOR_API_KEY",
                "NAVI_CONTROL_API_KEY", "NAVIGATOR_CONTROL_API_KEY", "NAVI_PRINCIPAL_CREDENTIAL",
                "NAVI_USER_API_KEY", "NAVI_ADMIN_KEY_CLAIM_TOKEN", "NAVI_WORKER_CREDENTIAL", "NAVI_TASK_SCOPED_TOKEN");
        if (!foreign.isEmpty()) {
            return "MIXED";
        }
        return presentCredentialKeys("NAVI_CLIENT_APP_KEY", "CLIENT_APP_KEY", "NAVI_CLIENT_APP_SECRET", "CLIENT_APP_SECRET",
                "NAVI_CLIENT_APP_ACCESS_TOKEN", "NAVI_CLIENT_APP_RUNTIME_TOKEN", "CLIENT_APP_RUNTIME_TOKEN").isEmpty()
                ? "MISSING" : "AVAILABLE";
    }

    String typedManagementAuthorityAvailability() {
        if (typedCredentialSource == TypedCredentialSource.MISSING) {
            return "NOT_CONFIGURED";
        }
        if (typedCredentialSourceState() == LocalState.INVALID || typedMetadataState() != LocalState.VALID) {
            return "INVALID";
        }
        return "LOCALLY_CONFIGURED_NOT_AUTHORIZED";
    }

    private String laneAvailability(String requiredKey, String[] forbiddenKeys) {
        if (!presentCredentialKeys(forbiddenKeys).isEmpty()) {
            return "MIXED";
        }
        return hasText(values.get(requiredKey)) ? "AVAILABLE" : "MISSING";
    }

    private List<String> presentLegacyCredentialKeys() {
        Set<String> present = new LinkedHashSet<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (hasText(entry.getValue()) && LEGACY_CREDENTIAL_KEYS.contains(entry.getKey().toUpperCase(Locale.ROOT))) {
                present.add(entry.getKey());
            }
        }
        return List.copyOf(present);
    }

    private static void applyOptions(Map<String, String> values, CliArguments args, Map<String, String> env) {
        putOption(values, args, "base-url", "NAVI_BASE_URL");
        putOption(values, args, "tenant-id", "NAVI_TENANT_ID");
        putOption(values, args, "client-app-id", "NAVI_CLIENT_APP_ID");
        putOption(values, args, "client-app-key", "NAVI_CLIENT_APP_KEY");
        putOption(values, args, "client-app-secret", "NAVI_CLIENT_APP_SECRET");
        putOption(values, args, "client-app-access-token", "NAVI_CLIENT_APP_ACCESS_TOKEN");
        putOption(values, args, "upstream-system-id", "NAVI_UPSTREAM_SYSTEM_ID");
        putOption(values, args, "source-system", "NAVI_UPSTREAM_SYSTEM_ID");
        putOption(values, args, "source-tenant-id", "NAVI_SOURCE_TENANT_ID");
        putOption(values, args, "requested-tenant-id", "NAVI_REQUESTED_TENANT_ID");
        putOption(values, args, "target-tenant-id", "NAVI_TARGET_TENANT_ID");
        putOption(values, args, "upstream-ref", "NAVI_UPSTREAM_REF");
        putOption(values, args, "model-profile-code", "NAVI_MODEL_PROFILE_CODE");
        putOption(values, args, "skill-id", "NAVI_SKILL_ID");
        putOption(values, args, "biz-worker-id", "NAVI_BIZ_WORKER_ID");
        putOption(values, args, "worker-pool-id", "NAVI_WORKER_POOL_ID");
        putOption(values, args, "user-api-key", "NAVI_USER_API_KEY");
        putOption(values, args, "admin-token", "NAVI_ADMIN_TOKEN");
        putOption(values, args, "admin-api-key", "NAVI_ADMIN_API_KEY");
        putOption(values, args, "operator-api-key", "NAVI_OPERATOR_API_KEY");
        putOption(values, args, "control-api-key", "NAVI_CONTROL_API_KEY");
        putEnvOption(values, args, env, "principal-credential-env", "NAVI_PRINCIPAL_CREDENTIAL");
        putOption(values, args, "request-code", "NAVI_ADMIN_KEY_REQUEST_CODE");
        putOption(values, args, "claim-token", "NAVI_ADMIN_KEY_CLAIM_TOKEN");
        putOption(values, args, "upstream-user-token", "NAVI_UPSTREAM_USER_TOKEN");
        putOption(values, args, "upstream-user-id", "NAVI_UPSTREAM_USER_ID");
        putOption(values, args, "model-config-id", "NAVI_MODEL_CONFIG_ID");
        putOption(values, args, "agent", "NAVI_AGENT_CODE");
        putOption(values, args, "interval", "NAVI_POLL_INTERVAL_SECONDS");
        putOption(values, args, "mock-url", "NAVI_E2E_MOCK_LLM_URL");
        putEnvOption(values, args, env, "client-app-secret-env", "NAVI_CLIENT_APP_SECRET");
        putEnvOption(values, args, env, "client-app-access-token-env", "NAVI_CLIENT_APP_ACCESS_TOKEN");
        putEnvOption(values, args, env, "user-api-key-env", "NAVI_USER_API_KEY");
        putEnvOption(values, args, env, "admin-token-env", "NAVI_ADMIN_TOKEN");
        putEnvOption(values, args, env, "admin-api-key-env", "NAVI_ADMIN_API_KEY");
        putEnvOption(values, args, env, "operator-api-key-env", "NAVI_OPERATOR_API_KEY");
        putEnvOption(values, args, env, "control-api-key-env", "NAVI_CONTROL_API_KEY");
        putEnvOption(values, args, env, "claim-token-env", "NAVI_ADMIN_KEY_CLAIM_TOKEN");
        putEnvOption(values, args, env, "api-key-env", "NAVI_LLM_API_KEY");
        putEnvOption(values, args, env, "upstream-user-token-env", "NAVI_UPSTREAM_USER_TOKEN");
    }

    private static void applyAliases(Map<String, String> values) {
        putAlias(values, "NAVIGATOR_BASE_URL", "NAVI_BASE_URL");
        putAlias(values, "NAVIGATOR_TENANT_ID", "NAVI_TENANT_ID");
        putAlias(values, "CLIENT_APP_ID", "NAVI_CLIENT_APP_ID");
        putAlias(values, "CLIENT_APP_KEY", "NAVI_CLIENT_APP_KEY");
        putAlias(values, "CLIENT_APP_SECRET", "NAVI_CLIENT_APP_SECRET");
        putAlias(values, "CLIENT_APP_RUNTIME_TOKEN", "NAVI_CLIENT_APP_ACCESS_TOKEN");
        putAlias(values, "NAVIGATOR_ADMIN_TOKEN", "NAVI_ADMIN_TOKEN");
        putAlias(values, "NAVIGATOR_ADMIN_API_KEY", "NAVI_ADMIN_API_KEY");
        putAlias(values, "NAVIGATOR_OPERATOR_API_KEY", "NAVI_OPERATOR_API_KEY");
        putAlias(values, "NAVIGATOR_CONTROL_API_KEY", "NAVI_CONTROL_API_KEY");
        putAlias(values, "TMS_STAFF_SESSION_TOKEN", "NAVI_UPSTREAM_USER_TOKEN");
        putAlias(values, "UPSTREAM_USER_ID", "NAVI_UPSTREAM_USER_ID");
    }

    private static void putAlias(Map<String, String> values, String source, String target) {
        String sourceValue = values.get(source);
        String targetValue = values.get(target);
        if (hasText(sourceValue) && (!hasText(targetValue) || sourceValueShouldOverrideDefault(target, targetValue))) {
            values.put(target, sourceValue);
        }
    }

    private static boolean sourceValueShouldOverrideDefault(String target, String targetValue) {
        return switch (target) {
            case "NAVI_BASE_URL" -> DEFAULT_BASE_URL.equals(targetValue);
            default -> false;
        };
    }

    private static void putOption(Map<String, String> values, CliArguments args, String option, String key) {
        String value = args.option(option);
        if (hasText(value)) {
            values.put(key, value);
        }
    }

    private static void putEnvOption(Map<String, String> values, CliArguments args, Map<String, String> env,
                                     String option, String key) {
        String envName = args.option(option);
        if (hasText(envName)) {
            String value = env.get(envName);
            if (hasText(value)) {
                values.put(key, value);
            }
        }
    }

    private static Map<String, String> readProfile(Path profile) {
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(profile)) {
            props.load(in);
        } catch (IOException e) {
            throw new UpstreamCliException("Failed to read profile: " + profile, e);
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (String name : props.stringPropertyNames()) {
            values.put(name, props.getProperty(name));
        }
        return values;
    }

    private static Path resolvePath(Path cwd, String path) {
        if (!hasText(path)) {
            return null;
        }
        Path resolved = Path.of(path);
        if (!resolved.isAbsolute()) {
            resolved = cwd.resolve(resolved);
        }
        return resolved.normalize();
    }

    private static Path resolveProfilePath(CliArguments args, Map<String, String> env, Path cwd) {
        Path explicit = resolvePath(cwd, args.option("profile"));
        if (explicit != null) {
            return explicit;
        }
        Path envProfile = resolvePath(cwd, env.get("NAVI_UPSTREAM_PROFILE"));
        if (envProfile != null) {
            return envProfile;
        }
        Path projectProfile = cwd.resolve(".navigator").resolve("upstream.env").normalize();
        if (Files.exists(projectProfile)) {
            return projectProfile;
        }
        Path legacyProfile = cwd.resolve(".navi-upstream.env").normalize();
        if (Files.exists(legacyProfile)) {
            return legacyProfile;
        }
        return projectProfile;
    }

    private static boolean isUnder(Path path, Path parent) {
        return path.toAbsolutePath().normalize().startsWith(parent.toAbsolutePath().normalize());
    }

    private boolean profileCanBeWrittenSafely(Path targetProfile) {
        if (targetProfile == null) {
            return true;
        }
        if (!isUnder(targetProfile, cwd)) {
            return true;
        }
        return isUnder(targetProfile, cwd.resolve("temp")) || GitIgnoreSupport.isIgnored(cwd, targetProfile);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static TypedCredentialSource resolveTypedCredentialSource(
            String directPrincipalCredential,
            String explicitPrincipalCredentialEnv,
            String explicitPrincipalCredential
    ) {
        boolean direct = hasText(directPrincipalCredential);
        boolean explicitRequested = hasText(explicitPrincipalCredentialEnv);
        if (direct && explicitRequested) {
            return TypedCredentialSource.AMBIGUOUS;
        }
        if (explicitRequested) {
            return hasText(explicitPrincipalCredential)
                    ? TypedCredentialSource.EXPLICIT_ENV : TypedCredentialSource.EXPLICIT_ENV_MISSING;
        }
        return direct ? TypedCredentialSource.DIRECT : TypedCredentialSource.MISSING;
    }

    private static boolean compatiblePrincipalAndLane(String principalType, String credentialLane) {
        String normalizedPrincipalType = principalType.trim().toUpperCase(Locale.ROOT);
        String normalizedCredentialLane = credentialLane.trim().toUpperCase(Locale.ROOT);
        return ("INSTANCE_ROOT".equals(normalizedPrincipalType)
                && ("INSTANCE_ROOT_CONTROL".equals(normalizedCredentialLane)
                || "INSTANCE_ROOT_SECURITY".equals(normalizedCredentialLane)))
                || ("SAAS_PLATFORM".equals(normalizedPrincipalType)
                && ("SAAS_PROVISIONING".equals(normalizedCredentialLane)
                || "SAAS_SECURITY_ADMIN".equals(normalizedCredentialLane)));
    }

    private static void addSensitiveValue(List<String> values, String value) {
        if (hasText(value) && !values.contains(value)) {
            values.add(value);
        }
    }

    private static List<String> envKeys() {
        return List.of("NAVI_BASE_URL", "TMS_WEB_BASE_URL", "BASIC_BASE_URL", "NAVI_TENANT_ID",
                "NAVI_CLIENT_APP_ID", "NAVI_CLIENT_APP_KEY", "NAVI_AGENT_CODE",
                "NAVI_MODEL_CONFIG_ID", "NAVI_MODEL_VARIANT", "NAVI_MODEL",
                "NAVI_POLL_INTERVAL_SECONDS", "NAVI_E2E_MOCK_LLM_URL", "NAVI_CLIENT_APP_SECRET",
                "NAVI_CLIENT_APP_ACCESS_TOKEN", "NAVI_ADMIN_TOKEN", "NAVI_ADMIN_API_KEY", "NAVI_OPERATOR_API_KEY",
                "NAVI_CONTROL_API_KEY", "NAVI_USER_API_KEY", "NAVI_ADMIN_KEY_REQUEST_CODE", "NAVI_ADMIN_KEY_CLAIM_TOKEN",
                "NAVI_PRINCIPAL_CREDENTIAL", "NAVI_NAVIGATOR_INSTANCE_ID", "NAVI_ENVIRONMENT_PROFILE",
                "NAVI_EXPECTED_PRINCIPAL_TYPE", "NAVI_EXPECTED_CREDENTIAL_LANE",
                "NAVI_UPSTREAM_SYSTEM_ID", "NAVI_REQUESTED_TENANT_ID", "NAVI_TARGET_TENANT_ID",
                "NAVI_SOURCE_TENANT_ID", "NAVI_UPSTREAM_REF", "NAVI_UPSTREAM_MULTI_TENANT",
                "NAVI_MODEL_PROFILE_CODE", "NAVI_SKILL_ID", "NAVI_BIZ_WORKER_ID", "NAVI_WORKER_POOL_ID",
                "NAVI_PROVIDER_TYPE", "NAVI_DIRECTORY_ID", "NAVI_CODEX_HOME_KEY", "NAVI_PRIVATE_ACCOUNT_ID",
                "NAVI_CODEX_SANDBOX_MODE", "NAVI_CODEX_APPROVAL_POLICY", "NAVI_CODEX_NETWORK_ACCESS_ENABLED",
                "NAVI_CODEX_WEB_SEARCH_MODE", "NAVI_ALLOWED_TOOLS",
                "NAVI_LLM_API_KEY", "NAVI_UPSTREAM_USER_TOKEN", "TMS_STAFF_SESSION_TOKEN", "NAVIGATOR_BASE_URL", "NAVIGATOR_TENANT_ID",
                "CLIENT_APP_ID", "CLIENT_APP_KEY", "CLIENT_APP_SECRET",
                "CLIENT_APP_RUNTIME_TOKEN", "NAVI_CLIENT_APP_RUNTIME_TOKEN", "NAVI_RUNTIME_CREDENTIAL",
                "NAVI_WORKER_CREDENTIAL", "NAVI_TASK_SCOPED_TOKEN",
                "NAVIGATOR_ADMIN_TOKEN", "NAVIGATOR_ADMIN_API_KEY", "NAVIGATOR_OPERATOR_API_KEY",
                "NAVIGATOR_CONTROL_API_KEY", "NAVI_UPSTREAM_PROFILE");
    }

    private static List<String> sensitiveKeys() {
        return List.of("NAVI_CLIENT_APP_SECRET", "NAVI_CLIENT_APP_ACCESS_TOKEN", "NAVI_ADMIN_TOKEN",
                "NAVI_ADMIN_API_KEY", "NAVI_OPERATOR_API_KEY", "NAVI_CONTROL_API_KEY", "NAVI_USER_API_KEY", "NAVI_ADMIN_KEY_CLAIM_TOKEN",
                "NAVI_PRINCIPAL_CREDENTIAL",
                "NAVI_LLM_API_KEY", "NAVI_UPSTREAM_USER_TOKEN", "TMS_STAFF_SESSION_TOKEN", "NAVI_CLIENT_APP_RUNTIME_TOKEN",
                "NAVI_RUNTIME_CREDENTIAL", "NAVI_WORKER_CREDENTIAL", "NAVI_TASK_SCOPED_TOKEN", "CLIENT_APP_KEY",
                "NAVI_CLIENT_APP_KEY", "CLIENT_APP_SECRET", "CLIENT_APP_RUNTIME_TOKEN",
                "NAVIGATOR_ADMIN_TOKEN", "NAVIGATOR_ADMIN_API_KEY", "NAVIGATOR_OPERATOR_API_KEY", "NAVIGATOR_CONTROL_API_KEY");
    }

    private static boolean isSensitiveKey(String key) {
        return key.endsWith("_SECRET") || key.endsWith("_TOKEN")
                || key.endsWith("_API_KEY") || key.endsWith("_KEY") || key.endsWith("_CREDENTIAL");
    }
}
