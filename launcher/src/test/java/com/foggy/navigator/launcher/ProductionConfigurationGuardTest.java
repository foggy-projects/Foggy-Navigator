package com.foggy.navigator.launcher;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductionConfigurationGuardTest {

    @Test
    void validate_nonProductionProfile_skipsDevelopmentDefaults() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.jpa.hibernate.ddl-auto", "update")
                .withProperty("spring.main.allow-bean-definition-overriding", "true")
                .withProperty("jwt.secret", "foggy-navigator-jwt-secret-key-change-in-production")
                .withProperty("system.root.password", "root123")
                .withProperty("navigator.security.credential-key", "default-dev-key-change-in-prod")
                .withProperty("navigator.security.credential-salt", "abcdef0123456789")
                .withProperty("navigator.api.external-url", "http://localhost:8112")
                .withProperty("management.endpoints.web.exposure.include", "health,beans,metrics")
                .withProperty("management.endpoint.health.show-details", "always");
        environment.setActiveProfiles("dev");

        assertDoesNotThrow(() -> ProductionConfigurationGuard.validate(environment));
    }

    @Test
    void validate_prodRejectsDevelopmentDefaults() {
        MockEnvironment environment = hardenedEnvironment();
        environment.setActiveProfiles("prod");
        environment
                .withProperty("spring.jpa.hibernate.ddl-auto", "update")
                .withProperty("spring.main.allow-bean-definition-overriding", "true")
                .withProperty("jwt.secret", "foggy-navigator-jwt-secret-key-change-in-production")
                .withProperty("system.root.password", "root123")
                .withProperty("navigator.security.credential-key", "default-dev-key-change-in-prod")
                .withProperty("navigator.security.credential-salt", "abcdef0123456789")
                .withProperty("navigator.api.external-url", "http://localhost:8112")
                .withProperty("management.endpoints.web.exposure.include", "health,beans,metrics")
                .withProperty("management.endpoint.health.show-details", "always");

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> ProductionConfigurationGuard.validate(environment));

        String message = exception.getMessage();
        assertTrue(message.contains("spring.jpa.hibernate.ddl-auto"));
        assertTrue(message.contains("spring.main.allow-bean-definition-overriding"));
        assertTrue(message.contains("jwt.secret"));
        assertTrue(message.contains("system.root.password"));
        assertTrue(message.contains("navigator.security.credential-key"));
        assertTrue(message.contains("navigator.security.credential-salt"));
        assertTrue(message.contains("navigator.api.external-url"));
        assertTrue(message.contains("management.endpoints.web.exposure.include"));
        assertTrue(message.contains("management.endpoint.health.show-details"));
    }

    @Test
    void validate_prodAcceptsHardenedConfig() {
        MockEnvironment environment = hardenedEnvironment();
        environment.setActiveProfiles("prod");

        assertDoesNotThrow(() -> ProductionConfigurationGuard.validate(environment));
    }

    @Test
    void hasProductionProfile_acceptsProductionAlias() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("production");

        assertTrue(ProductionConfigurationGuard.hasProductionProfile(environment));
    }

    @Test
    void validate_prodRejectsEnabledPayloadStoreWithoutPersistentDirectory() {
        MockEnvironment environment = hardenedEnvironment()
                .withProperty("foggy.session.message-payload.enabled", "true");
        environment.setActiveProfiles("prod");

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> ProductionConfigurationGuard.validate(environment));

        assertTrue(exception.getMessage().contains(
                "foggy.session.message-payload.filesystem.directory"));
    }

    private static MockEnvironment hardenedEnvironment() {
        return new MockEnvironment()
                .withProperty("spring.jpa.hibernate.ddl-auto", "validate")
                .withProperty("spring.main.allow-bean-definition-overriding", "false")
                .withProperty("spring.datasource.url", "jdbc:mysql://db.example.com:3306/navigator")
                .withProperty("spring.datasource.username", "navigator")
                .withProperty("spring.datasource.password", "ProdDatabasePassword123!")
                .withProperty("jwt.secret", "prod-jwt-secret-value-with-more-than-32-chars-123456")
                .withProperty("system.root.password", "ProdRootPassword123!")
                .withProperty("navigator.security.credential-key", "prod-credential-key-value")
                .withProperty("navigator.security.credential-salt", "0123456789abcdef")
                .withProperty("navigator.api.external-url", "https://navigator.example.com")
                .withProperty("management.endpoints.web.exposure.include", "health,metrics")
                .withProperty("management.endpoint.health.show-details", "never");
    }
}
