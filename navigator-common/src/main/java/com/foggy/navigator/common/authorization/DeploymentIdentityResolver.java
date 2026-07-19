package com.foggy.navigator.common.authorization;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.util.PlaceholderResolutionException;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Resolves the immutable server deployment identity from Spring deployment
 * configuration and active server profiles only.
 */
public final class DeploymentIdentityResolver {

    public static final String NAVIGATOR_INSTANCE_ID_PROPERTY =
            "navigator.deployment.navigator-instance-id";
    public static final String ENVIRONMENT_PROFILE_PROPERTY =
            "navigator.deployment.environment-profile";
    public static final String NAVIGATOR_INSTANCE_ID_ENVIRONMENT_VARIABLE = "NAVIGATOR_INSTANCE_ID";
    public static final String ENVIRONMENT_PROFILE_ENVIRONMENT_VARIABLE = "NAVIGATOR_ENVIRONMENT_PROFILE";
    public static final String DEV_FALLBACK_NAVIGATOR_INSTANCE_ID = "navi-local-dev";
    public static final String DEV_FALLBACK_ENVIRONMENT_PROFILE = "internal-dev";

    private static final Logger log = LoggerFactory.getLogger(DeploymentIdentityResolver.class);
    private static final Set<String> PRODUCTION_ALIASES = Set.of("prod", "production");
    private static final Set<String> PLACEHOLDER_VALUES = Set.of(
            "unknown", "placeholder", "changeme", "change-me", "todo", "tbd", "null", "none", "n/a"
    );
    private static final Set<String> REQUEST_OVERRIDE_FIELDS = Set.of(
            "xnaviinstanceid",
            "xnavienvironmentprofile",
            "xnavinavigatorinstanceid",
            "xnavinavigatorenvironmentprofile",
            "xnavigatorinstanceid",
            "xnavigatorenvironmentprofile",
            "navigatorinstanceid",
            "environmentprofile"
    );

    private DeploymentIdentityResolver() {
    }

    /**
     * Resolves one deployment identity. Only the two documented server properties
     * and Spring's active server profiles are consulted.
     *
     * @throws IllegalStateException when configured identity is incomplete or
     *                               conflicts with the active server profiles
     */
    public static DeploymentIdentity resolve(Environment environment) {
        if (environment == null) {
            throw new IllegalArgumentException("environment must not be null");
        }

        Set<String> serverProfiles = deploymentProfiles(environment);
        boolean production = serverProfiles.contains("production");
        if (serverProfiles.size() > 1) {
            throw invalid("conflicting active deployment profiles: " + String.join(", ", serverProfiles));
        }

        String configuredInstanceId = configuredValue(environment, NAVIGATOR_INSTANCE_ID_PROPERTY,
                NAVIGATOR_INSTANCE_ID_ENVIRONMENT_VARIABLE);
        String configuredEnvironmentProfile = configuredValue(environment, ENVIRONMENT_PROFILE_PROPERTY,
                ENVIRONMENT_PROFILE_ENVIRONMENT_VARIABLE);

        if (production) {
            return resolveProduction(configuredInstanceId, configuredEnvironmentProfile);
        }
        return resolveNonProduction(configuredInstanceId, configuredEnvironmentProfile, serverProfiles);
    }

    /**
     * Identifies header or body field names that must never become a source of
     * server deployment identity. Request adapters can use this to emit a stable
     * shadow rejection without consulting the supplied value.
     */
    public static boolean isServerOwnedIdentityOverrideAttempt(String fieldOrHeaderName) {
        if (!StringUtils.hasText(fieldOrHeaderName)) {
            return false;
        }
        return REQUEST_OVERRIDE_FIELDS.contains(normalizeRequestOverrideName(fieldOrHeaderName));
    }

    private static String normalizeRequestOverrideName(String value) {
        return value.trim().toLowerCase(Locale.ROOT)
                .replace("_", "")
                .replace("-", "");
    }

    private static DeploymentIdentity resolveProduction(String configuredInstanceId,
                                                        String configuredEnvironmentProfile) {
        Set<String> violations = new LinkedHashSet<>();
        if (isInvalidInstanceId(configuredInstanceId)
                || DEV_FALLBACK_NAVIGATOR_INSTANCE_ID.equalsIgnoreCase(configuredInstanceId)) {
            violations.add(NAVIGATOR_INSTANCE_ID_PROPERTY
                    + " must be an explicit non-placeholder, non-dev-fallback value in production");
        }

        String environmentProfile = canonicalEnvironmentProfile(configuredEnvironmentProfile);
        if (environmentProfile == null) {
            violations.add(ENVIRONMENT_PROFILE_PROPERTY
                    + " must be a recognized explicit production value in production");
        } else if (!"production".equals(environmentProfile)) {
            violations.add(ENVIRONMENT_PROFILE_PROPERTY
                    + " conflicts with the active production profile");
        }

        if (!violations.isEmpty()) {
            throw invalid(String.join("; ", violations));
        }
        return new DeploymentIdentity(configuredInstanceId, environmentProfile,
                DeploymentIdentitySource.CONFIGURED, true);
    }

    private static DeploymentIdentity resolveNonProduction(String configuredInstanceId,
                                                           String configuredEnvironmentProfile,
                                                           Set<String> serverProfiles) {
        boolean hasConfiguredInstanceId = StringUtils.hasText(configuredInstanceId);
        boolean hasConfiguredEnvironmentProfile = StringUtils.hasText(configuredEnvironmentProfile);
        if (!hasConfiguredInstanceId && !hasConfiguredEnvironmentProfile) {
            log.warn("Navigator deployment identity is using the explicit dev-only fallback "
                            + "(navigatorInstanceId={}, environmentProfile={}, productionUsable=false). "
                            + "Configure {} and {} for a named deployment.",
                    DEV_FALLBACK_NAVIGATOR_INSTANCE_ID,
                    DEV_FALLBACK_ENVIRONMENT_PROFILE,
                    NAVIGATOR_INSTANCE_ID_PROPERTY,
                    ENVIRONMENT_PROFILE_PROPERTY);
            return new DeploymentIdentity(DEV_FALLBACK_NAVIGATOR_INSTANCE_ID,
                    DEV_FALLBACK_ENVIRONMENT_PROFILE,
                    DeploymentIdentitySource.DEV_FALLBACK, false);
        }
        if (!hasConfiguredInstanceId || !hasConfiguredEnvironmentProfile) {
            throw invalid("both " + NAVIGATOR_INSTANCE_ID_PROPERTY + " and "
                    + ENVIRONMENT_PROFILE_PROPERTY + " must be configured together outside production");
        }
        if (isInvalidInstanceId(configuredInstanceId)) {
            throw invalid(NAVIGATOR_INSTANCE_ID_PROPERTY + " must be a non-placeholder value");
        }

        String environmentProfile = canonicalEnvironmentProfile(configuredEnvironmentProfile);
        if (environmentProfile == null) {
            throw invalid(ENVIRONMENT_PROFILE_PROPERTY + " must be a recognized deployment environment");
        }
        if ("production".equals(environmentProfile)) {
            throw invalid(ENVIRONMENT_PROFILE_PROPERTY
                    + " may be production only when a production server profile is active");
        }
        if (!serverProfiles.isEmpty() && !serverProfiles.contains(environmentProfile)) {
            throw invalid(ENVIRONMENT_PROFILE_PROPERTY + " conflicts with the active server profile "
                    + serverProfiles.iterator().next());
        }
        return new DeploymentIdentity(configuredInstanceId, environmentProfile,
                DeploymentIdentitySource.CONFIGURED, false);
    }

    private static Set<String> deploymentProfiles(Environment environment) {
        Set<String> profiles = new LinkedHashSet<>();
        Arrays.stream(environment.getActiveProfiles())
                .map(DeploymentIdentityResolver::canonicalEnvironmentProfile)
                .filter(profile -> profile != null)
                .forEach(profiles::add);
        return profiles;
    }

    private static String canonicalEnvironmentProfile(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (PRODUCTION_ALIASES.contains(normalized)) {
            return "production";
        }
        return switch (normalized) {
            case "local" -> "local";
            case "dev", "development", "internal-dev", "internal_dev" -> "internal-dev";
            case "test" -> "test";
            case "stage", "staging" -> "staging";
            default -> null;
        };
    }

    private static boolean isInvalidInstanceId(String value) {
        if (!StringUtils.hasText(value)) {
            return true;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.startsWith("${")
                || PLACEHOLDER_VALUES.contains(normalized)
                || normalized.startsWith("example")
                || normalized.contains("placeholder")
                || normalized.contains("change-me")
                || normalized.contains("changeme")
                || normalized.startsWith("unknown-")
                || normalized.endsWith("-unknown")
                || normalized.contains("-unknown-")
                || value.chars().anyMatch(Character::isWhitespace);
    }

    private static String configuredValue(Environment environment, String key, String fallbackKey) {
        try {
            String configuredValue = normalize(environment.getProperty(key));
            String fallbackValue = normalize(environment.getProperty(fallbackKey));
            if (StringUtils.hasText(configuredValue) && StringUtils.hasText(fallbackValue)
                    && conflictingConfiguredValues(key, configuredValue, fallbackValue)) {
                throw invalid(key + " conflicts with " + fallbackKey);
            }
            return StringUtils.hasText(configuredValue) ? configuredValue : fallbackValue;
        } catch (PlaceholderResolutionException exception) {
            // An unresolved deployment placeholder is deliberately treated as an
            // invalid identity rather than leaking a resolver-specific failure.
            return "${unresolved}";
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private static boolean conflictingConfiguredValues(String key, String configuredValue,
                                                       String fallbackValue) {
        if (!ENVIRONMENT_PROFILE_PROPERTY.equals(key)) {
            return !configuredValue.equals(fallbackValue);
        }
        String configuredProfile = canonicalEnvironmentProfile(configuredValue);
        String fallbackProfile = canonicalEnvironmentProfile(fallbackValue);
        if (configuredProfile != null && fallbackProfile != null) {
            return !configuredProfile.equals(fallbackProfile);
        }
        return !configuredValue.equals(fallbackValue);
    }

    private static IllegalStateException invalid(String detail) {
        return new IllegalStateException("Invalid Navigator deployment identity: " + detail);
    }
}
