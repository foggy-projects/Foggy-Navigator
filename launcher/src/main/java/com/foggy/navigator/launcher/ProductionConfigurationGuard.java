package com.foggy.navigator.launcher;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Fails fast when a production profile is started with development-grade settings.
 */
public class ProductionConfigurationGuard implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    private static final Logger log = LoggerFactory.getLogger(ProductionConfigurationGuard.class);
    private static final Set<String> PRODUCTION_PROFILES = Set.of("prod", "production");
    private static final Set<String> ALLOWED_PRODUCTION_DDL_AUTO = Set.of("validate", "none");
    private static final Set<String> HIGH_RISK_ACTUATOR_ENDPOINTS = Set.of(
            "*", "beans", "env", "configprops", "heapdump", "threaddump",
            "shutdown", "logfile", "mappings", "conditions", "scheduledtasks"
    );
    private static final Set<String> DEFAULT_SECRET_VALUES = Set.of(
            "foggy-navigator-jwt-secret-key-change-in-production",
            "foggy-navigator-secret-key-change-in-production-please",
            "default-dev-key-change-in-prod",
            "abcdef0123456789",
            "root123"
    );

    @Override
    public void initialize(ConfigurableApplicationContext applicationContext) {
        validate(applicationContext.getEnvironment());
    }

    static void validate(Environment environment) {
        if (!hasProductionProfile(environment)) {
            return;
        }

        List<String> violations = new ArrayList<>();
        validateDdlAuto(environment, violations);
        validateBeanOverriding(environment, violations);
        validateRequiredProperty(environment, "spring.datasource.url", violations);
        validateRequiredProperty(environment, "spring.datasource.username", violations);
        validateRequiredProperty(environment, "spring.datasource.password", violations);
        validateSecret(environment, "jwt.secret", 32, violations);
        validateSecret(environment, "system.root.password", 12, violations);
        validateSecret(environment, "navigator.security.credential-key", 16, violations);
        validateCredentialSalt(environment, violations);
        validateExternalUrl(environment, violations);
        validateActuatorExposure(environment, violations);
        validateHealthDetails(environment, violations);
        validateSessionMessagePayloadStore(environment, violations);

        if (!violations.isEmpty()) {
            throw new IllegalStateException("Unsafe production configuration:\n - "
                    + String.join("\n - ", violations));
        }
        log.info("Production configuration guard passed.");
    }

    static boolean hasProductionProfile(Environment environment) {
        return Arrays.stream(environment.getActiveProfiles())
                .map(profile -> profile.toLowerCase(Locale.ROOT))
                .anyMatch(PRODUCTION_PROFILES::contains);
    }

    private static void validateDdlAuto(Environment environment, List<String> violations) {
        String value = property(environment, "spring.jpa.hibernate.ddl-auto").toLowerCase(Locale.ROOT);
        if (!ALLOWED_PRODUCTION_DDL_AUTO.contains(value)) {
            violations.add("spring.jpa.hibernate.ddl-auto must be validate or none in production");
        }
    }

    private static void validateBeanOverriding(Environment environment, List<String> violations) {
        if (Boolean.parseBoolean(property(environment, "spring.main.allow-bean-definition-overriding"))) {
            violations.add("spring.main.allow-bean-definition-overriding must be false in production");
        }
    }

    private static void validateRequiredProperty(Environment environment, String key, List<String> violations) {
        if (!StringUtils.hasText(property(environment, key))) {
            violations.add(key + " must be explicitly configured in production");
        }
    }

    private static void validateSecret(Environment environment, String key, int minLength, List<String> violations) {
        String value = property(environment, key);
        if (!StringUtils.hasText(value)) {
            violations.add(key + " must be explicitly configured in production");
            return;
        }
        if (value.length() < minLength || isDefaultSecret(value)) {
            violations.add(key + " must use a non-default value with at least " + minLength + " characters");
        }
    }

    private static void validateCredentialSalt(Environment environment, List<String> violations) {
        String value = property(environment, "navigator.security.credential-salt");
        if (!StringUtils.hasText(value)) {
            violations.add("navigator.security.credential-salt must be explicitly configured in production");
            return;
        }
        if (isDefaultSecret(value) || value.length() < 16 || !value.matches("[0-9a-fA-F]+") || value.length() % 2 != 0) {
            violations.add("navigator.security.credential-salt must be a non-default even-length hex value with at least 16 characters");
        }
    }

    private static void validateExternalUrl(Environment environment, List<String> violations) {
        String value = property(environment, "navigator.api.external-url").toLowerCase(Locale.ROOT);
        if (!StringUtils.hasText(value)) {
            violations.add("navigator.api.external-url must be explicitly configured in production");
            return;
        }
        if (value.contains("localhost") || value.contains("127.0.0.1")) {
            violations.add("navigator.api.external-url must be externally reachable in production");
        }
    }

    private static void validateActuatorExposure(Environment environment, List<String> violations) {
        String exposure = property(environment, "management.endpoints.web.exposure.include");
        if (!StringUtils.hasText(exposure)) {
            return;
        }
        Arrays.stream(exposure.split(","))
                .map(String::trim)
                .map(endpoint -> endpoint.toLowerCase(Locale.ROOT))
                .filter(HIGH_RISK_ACTUATOR_ENDPOINTS::contains)
                .findFirst()
                .ifPresent(endpoint -> violations.add("management.endpoints.web.exposure.include must not expose " + endpoint + " in production"));
    }

    private static void validateHealthDetails(Environment environment, List<String> violations) {
        String value = property(environment, "management.endpoint.health.show-details").toLowerCase(Locale.ROOT);
        if ("always".equals(value)) {
            violations.add("management.endpoint.health.show-details must not be always in production");
        }
    }

    private static void validateSessionMessagePayloadStore(Environment environment, List<String> violations) {
        if (Boolean.parseBoolean(property(environment, "foggy.session.message-payload.enabled"))) {
            validateRequiredProperty(environment,
                    "foggy.session.message-payload.filesystem.directory", violations);
        }
    }

    private static boolean isDefaultSecret(String value) {
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return DEFAULT_SECRET_VALUES.contains(value.trim())
                || normalized.contains("change-in-production")
                || normalized.contains("change-in-prod")
                || normalized.startsWith("default-");
    }

    private static String property(Environment environment, String key) {
        String value = environment.getProperty(key, "");
        return value != null ? value.trim() : "";
    }
}
