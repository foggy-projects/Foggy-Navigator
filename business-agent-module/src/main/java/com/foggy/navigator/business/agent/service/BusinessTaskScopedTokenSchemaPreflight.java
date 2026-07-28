package com.foggy.navigator.business.agent.service;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Stops task dispatch from starting against a pre-token-v2 table shape.
 */
@Component
public class BusinessTaskScopedTokenSchemaPreflight implements ApplicationRunner {

    private static final String TABLE_NAME = "business_task_scoped_token";
    private static final String MIGRATION_PATH =
            "docs/migration/2026-07-14-business-task-token-v2.sql followed by "
                    + "docs/migration/2026-07-28-task-scoped-caller-provenance.sql";
    private static final int H2_LONGTEXT_ALIAS_COLUMN_SIZE = 1_000_000_000;
    private static final Set<String> REQUIRED_COLUMNS = Set.of(
            "id",
            "row_version",
            "token_id",
            "token_hash",
            "task_id",
            "worker_task_id",
            "worker_session_id",
            "session_id",
            "tenant_id",
            "client_app_id",
            "upstream_user_id",
            "navigator_effective_user_id",
            "navigator_instance_id",
            "caller_authority_type",
            "caller_credential_id",
            "caller_access_token_id",
            "skill_id",
            "worker_pool_id",
            "model_config_id",
            "status",
            "token_version",
            "generation",
            "audience",
            "identity_assurance",
            "function_scope_json",
            "worker_id",
            "worker_lease_id",
            "issued_at",
            "revoked_at",
            "revoked_by",
            "revoke_reason",
            "expires_at",
            "created_at",
            "updated_at"
    );
    private static final Map<String, List<String>> REQUIRED_INDEXES = Map.of(
            "idx_biz_token_task", List.of("task_id"),
            "idx_biz_token_tenant_worker_task", List.of("tenant_id", "worker_task_id")
    );

    private final DataSource dataSource;

    public BusinessTaskScopedTokenSchemaPreflight(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void run(ApplicationArguments args) {
        validate();
    }

    public void validate() {
        try (Connection connection = dataSource.getConnection()) {
            TableReference table = resolveTable(connection);
            DatabaseMetaData metadata = connection.getMetaData();
            Map<String, ColumnDefinition> actualColumns = readColumns(metadata, table);
            List<String> missingColumns = missing(REQUIRED_COLUMNS, actualColumns.keySet());
            List<String> invalidColumnDefinitions = invalidColumnDefinitions(metadata, actualColumns);
            Map<String, IndexDefinition> actualIndexes = readIndexes(metadata, table);
            List<String> invalidIndexes = invalidIndexes(actualIndexes);
            if (!missingColumns.isEmpty() || !invalidColumnDefinitions.isEmpty() || !invalidIndexes.isEmpty()) {
                throw incompatibleSchema(missingColumns, invalidColumnDefinitions, invalidIndexes);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException(
                    "Cannot validate business task scoped token v2 schema. Apply " + MIGRATION_PATH
                            + " and restart Navigator.",
                    exception
            );
        }
    }

    private TableReference resolveTable(Connection connection) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        for (String schema : schemaCandidates(connection)) {
            for (String name : tableNameCandidates()) {
                try (ResultSet tables = metadata.getTables(connection.getCatalog(), schema, name, new String[]{"TABLE"})) {
                    if (tables.next()) {
                        return new TableReference(
                                tables.getString("TABLE_CAT"),
                                tables.getString("TABLE_SCHEM"),
                                tables.getString("TABLE_NAME")
                        );
                    }
                }
            }
        }
        throw incompatibleSchema(List.of("table " + TABLE_NAME), List.of(), List.of());
    }

    private Collection<String> schemaCandidates(Connection connection) throws SQLException {
        Set<String> candidates = new LinkedHashSet<>();
        if (connection.getSchema() != null) {
            candidates.add(connection.getSchema());
        }
        candidates.add(null);
        return candidates;
    }

    private Collection<String> tableNameCandidates() {
        return List.of(TABLE_NAME, TABLE_NAME.toUpperCase(Locale.ROOT));
    }

    private Map<String, ColumnDefinition> readColumns(DatabaseMetaData metadata, TableReference table) throws SQLException {
        Map<String, ColumnDefinition> columns = new LinkedHashMap<>();
        try (ResultSet resultSet = metadata.getColumns(table.catalog(), table.schema(), table.name(), null)) {
            while (resultSet.next()) {
                columns.put(
                        normalize(resultSet.getString("COLUMN_NAME")),
                        new ColumnDefinition(
                                resultSet.getString("TYPE_NAME"),
                                resultSet.getInt("DATA_TYPE"),
                                resultSet.getInt("COLUMN_SIZE"),
                                resultSet.getInt("NULLABLE")
                        )
                );
            }
        }
        return columns;
    }

    private List<String> invalidColumnDefinitions(DatabaseMetaData metadata,
                                                   Map<String, ColumnDefinition> actualColumns) throws SQLException {
        ColumnDefinition functionScope = actualColumns.get("function_scope_json");
        if (functionScope == null || isFunctionScopeCompatible(metadata, functionScope)) {
            return List.of();
        }
        return List.of("function_scope_json " + functionScope.describe());
    }

    private boolean isFunctionScopeCompatible(DatabaseMetaData metadata, ColumnDefinition column) throws SQLException {
        if (column.nullable() != DatabaseMetaData.columnNoNulls) {
            return false;
        }
        if (isMySqlFamily(metadata)) {
            return "longtext".equals(normalize(column.typeName()));
        }
        if (isH2(metadata)) {
            return column.dataType() == Types.CLOB
                    || (column.dataType() == Types.VARCHAR
                    && column.columnSize() >= H2_LONGTEXT_ALIAS_COLUMN_SIZE);
        }
        return column.dataType() == Types.CLOB;
    }

    private boolean isMySqlFamily(DatabaseMetaData metadata) throws SQLException {
        return hasDatabaseProduct(metadata, "mysql") || hasDatabaseProduct(metadata, "mariadb");
    }

    private boolean isH2(DatabaseMetaData metadata) throws SQLException {
        return hasDatabaseProduct(metadata, "h2");
    }

    private boolean hasDatabaseProduct(DatabaseMetaData metadata, String product) throws SQLException {
        return normalize(metadata.getDatabaseProductName()).contains(product);
    }

    private Map<String, IndexDefinition> readIndexes(DatabaseMetaData metadata, TableReference table) throws SQLException {
        Map<String, List<IndexedColumn>> indexedColumns = new LinkedHashMap<>();
        Map<String, Boolean> nonUniqueByIndex = new LinkedHashMap<>();
        try (ResultSet resultSet = metadata.getIndexInfo(table.catalog(), table.schema(), table.name(), false, false)) {
            while (resultSet.next()) {
                String indexName = resultSet.getString("INDEX_NAME");
                String columnName = resultSet.getString("COLUMN_NAME");
                if (indexName != null && columnName != null) {
                    indexedColumns.computeIfAbsent(normalize(indexName), ignored -> new ArrayList<>())
                            .add(new IndexedColumn(resultSet.getShort("ORDINAL_POSITION"), normalize(columnName)));
                    nonUniqueByIndex.put(normalize(indexName), resultSet.getBoolean("NON_UNIQUE"));
                }
            }
        }

        Map<String, IndexDefinition> indexes = new LinkedHashMap<>();
        indexedColumns.forEach((name, columns) -> indexes.put(
                name,
                new IndexDefinition(
                        nonUniqueByIndex.get(name),
                        columns.stream()
                                .sorted(Comparator.comparingInt(IndexedColumn::position))
                                .map(IndexedColumn::name)
                                .toList()
                )
        ));
        return indexes;
    }

    private List<String> missing(Set<String> required, Set<String> actual) {
        return required.stream()
                .filter(column -> !actual.contains(column))
                .sorted()
                .toList();
    }

    private List<String> invalidIndexes(Map<String, IndexDefinition> actualIndexes) {
        List<String> invalid = REQUIRED_INDEXES.entrySet().stream()
                .filter(entry -> !entry.getValue().equals(columnsOf(actualIndexes.get(entry.getKey()))))
                .map(Map.Entry::getKey)
                .sorted()
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        boolean hasUniqueTokenId = actualIndexes.values().stream()
                .anyMatch(index -> !index.nonUnique() && List.of("token_id").equals(index.columns()));
        if (!hasUniqueTokenId) {
            invalid.add("unique token_id");
        }
        return invalid;
    }

    private List<String> columnsOf(IndexDefinition index) {
        return index == null ? List.of() : index.columns();
    }

    private IllegalStateException incompatibleSchema(List<String> missingColumns,
                                                      List<String> invalidColumnDefinitions,
                                                      List<String> invalidIndexes) {
        List<String> details = new ArrayList<>();
        if (!missingColumns.isEmpty()) {
            details.add("missing columns " + missingColumns);
        }
        if (!invalidColumnDefinitions.isEmpty()) {
            details.add("invalid column definitions " + invalidColumnDefinitions);
        }
        if (!invalidIndexes.isEmpty()) {
            details.add("missing or invalid indexes " + invalidIndexes);
        }
        return new IllegalStateException(
                "Business task scoped token v2 schema is incompatible: " + String.join("; ", details)
                        + ". Apply " + MIGRATION_PATH + " and restart Navigator."
        );
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private record TableReference(String catalog, String schema, String name) {
    }

    private record IndexedColumn(int position, String name) {
    }

    private record IndexDefinition(boolean nonUnique, List<String> columns) {
    }

    private record ColumnDefinition(String typeName, int dataType, int columnSize, int nullable) {

        private String describe() {
            return "[type=" + typeName + ", size=" + columnSize + ", nullable=" + nullable + "]";
        }
    }
}
