package com.foggy.navigator.common.entity;

import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opt-in proof that the P1A schema is safe to apply to an isolated MySQL 8
 * database. It intentionally uses a synthetic sentinel instead of any local
 * Navigator database or credential profile.
 */
@EnabledIfSystemProperty(named = "gov001.mysql.integration", matches = "true")
class AuthorizationPersistenceMySqlIntegrationTest {

    private static final DockerImageName MYSQL_8 = DockerImageName.parse("mysql:8.0");
    private static final String FORWARD_MIGRATION =
            "docs/migration/2026-07-19-gov-001-authorization-foundation.sql";
    private static final String ROLLBACK_MIGRATION =
            "docs/migration/2026-07-19-gov-001-authorization-foundation-rollback.sql";
    private static final List<String> AUTHORIZATION_TABLES = List.of(
            "authorization_principal",
            "authorization_credential",
            "authorization_management_token",
            "authorization_platform_grant",
            "authorization_tenant_authority",
            "authorization_decision"
    );

    @Test
    void forwardMigrationAppliesToFreshEmptySchemaAndHibernateValidates() throws Exception {
        MySQLContainer<?> mysql = startMySql();
        try {
            try (Connection connection = openConnection(mysql)) {
                executeMigration(connection, FORWARD_MIGRATION);
                assertAuthorizationTablesExistAndAreEmpty(connection);
            }

            validatePreAppliedSchemaWithHibernate(mysql);
        } finally {
            mysql.stop();
        }
    }

    @Test
    void forwardMigrationIsIdempotentAndPreservesLegacySentinel() throws Exception {
        MySQLContainer<?> mysql = startMySql();
        try {
            try (Connection connection = openConnection(mysql)) {
                createLegacySentinel(connection);
                executeMigration(connection, FORWARD_MIGRATION);

                assertAuthorizationTablesExistAndAreEmpty(connection);
                assertLegacySentinelPreserved(connection);

                executeMigration(connection, FORWARD_MIGRATION);
                assertAuthorizationTablesExistAndAreEmpty(connection);
                assertLegacySentinelPreserved(connection);
            }
        } finally {
            mysql.stop();
        }
    }

    @Test
    void destructiveRollbackIsIdempotentAndLeavesLegacySentinelUntouched() throws Exception {
        MySQLContainer<?> mysql = startMySql();
        try (Connection connection = openConnection(mysql)) {
            createLegacySentinel(connection);
            executeMigration(connection, FORWARD_MIGRATION);
            assertAuthorizationTablesExistAndAreEmpty(connection);

            executeMigration(connection, ROLLBACK_MIGRATION);
            assertAuthorizationTablesAbsent(connection);
            assertLegacySentinelPreserved(connection);

            executeMigration(connection, ROLLBACK_MIGRATION);
            assertAuthorizationTablesAbsent(connection);
            assertLegacySentinelPreserved(connection);
        } finally {
            mysql.stop();
        }
    }

    private static MySQLContainer<?> startMySql() {
        MySQLContainer<?> mysql = new MySQLContainer<>(MYSQL_8);
        mysql.start();
        return mysql;
    }

    private static Connection openConnection(MySQLContainer<?> mysql) throws SQLException {
        return DriverManager.getConnection(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
    }

    private static void createLegacySentinel(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE gov001_legacy_sentinel (id BIGINT NOT NULL PRIMARY KEY, marker VARCHAR(64) NOT NULL)");
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO gov001_legacy_sentinel (id, marker) VALUES (?, ?)")) {
            statement.setLong(1, 1L);
            statement.setString(2, "legacy-untouched");
            statement.executeUpdate();
        }
    }

    private static void executeMigration(Connection connection, String relativePath) throws IOException, SQLException {
        String executableSql = readRepositoryFile(relativePath).lines()
                .filter(line -> !line.trim().startsWith("--"))
                .collect(Collectors.joining("\n"));
        try (Statement statement = connection.createStatement()) {
            for (String sql : executableSql.split(";")) {
                String trimmed = sql.trim();
                if (!trimmed.isEmpty()) {
                    statement.execute(trimmed);
                }
            }
        }
    }

    private static void assertAuthorizationTablesExistAndAreEmpty(Connection connection) throws SQLException {
        for (String table : AUTHORIZATION_TABLES) {
            assertTrue(tableExists(connection, table), () -> "migration did not create " + table);
            assertEquals(0L, rowCount(connection, table), () -> table + " must not receive P1A seed data");
        }
    }

    private static void assertAuthorizationTablesAbsent(Connection connection) throws SQLException {
        for (String table : AUTHORIZATION_TABLES) {
            assertFalse(tableExists(connection, table), () -> "rollback did not drop " + table);
        }
    }

    private static boolean tableExists(Connection connection, String table) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = ?")) {
            statement.setString(1, table);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next());
                return resultSet.getLong(1) == 1L;
            }
        }
    }

    private static long rowCount(Connection connection, String table) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            assertTrue(resultSet.next());
            return resultSet.getLong(1);
        }
    }

    private static void assertLegacySentinelPreserved(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT marker FROM gov001_legacy_sentinel WHERE id = ?")) {
            statement.setLong(1, 1L);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next(), "P1A migration must not remove legacy sentinel data");
                assertEquals("legacy-untouched", resultSet.getString(1));
            }
        }
    }

    private static void validatePreAppliedSchemaWithHibernate(MySQLContainer<?> mysql) {
        StandardServiceRegistry registry = new StandardServiceRegistryBuilder()
                .applySetting("hibernate.connection.driver_class", "com.mysql.cj.jdbc.Driver")
                .applySetting("hibernate.connection.url", mysql.getJdbcUrl())
                .applySetting("hibernate.connection.username", mysql.getUsername())
                .applySetting("hibernate.connection.password", mysql.getPassword())
                .applySetting("hibernate.dialect", "org.hibernate.dialect.MySQLDialect")
                .applySetting("hibernate.hbm2ddl.auto", "validate")
                .applySetting("hibernate.show_sql", "false")
                .build();
        try {
            SessionFactory sessionFactory = new MetadataSources(registry)
                    .addAnnotatedClass(AuthorizationPrincipalEntity.class)
                    .addAnnotatedClass(AuthorizationCredentialEntity.class)
                    .addAnnotatedClass(AuthorizationManagementTokenEntity.class)
                    .addAnnotatedClass(AuthorizationPlatformGrantEntity.class)
                    .addAnnotatedClass(AuthorizationTenantAuthorityEntity.class)
                    .addAnnotatedClass(AuthorizationDecisionEntity.class)
                    .buildMetadata()
                    .buildSessionFactory();
            sessionFactory.close();
        } finally {
            StandardServiceRegistryBuilder.destroy(registry);
        }
    }

    private static String readRepositoryFile(String relativePath) throws IOException {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate);
            }
            current = current.getParent();
        }
        throw new IOException("Cannot locate repository file " + relativePath);
    }
}
