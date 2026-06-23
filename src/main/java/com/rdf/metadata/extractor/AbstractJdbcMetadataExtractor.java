package com.rdf.metadata.extractor;

import com.rdf.metadata.model.*;
import lombok.extern.slf4j.Slf4j;

import java.sql.*;
import java.util.*;

/**
 * Base extractor that uses standard JDBC {@link DatabaseMetaData} to extract
 * tables, columns, primary keys, foreign keys, and (where possible) unique constraints.
 *
 * <p>Subclasses can override individual methods to handle database-specific SQL quirks,
 * e.g. fetching check constraints from {@code information_schema}.
 */
@Slf4j
public abstract class AbstractJdbcMetadataExtractor implements SchemaMetadataExtractor {

    @Override
    public SchemaMetadata extract(ExtractionRequest request, Connection connection) {
        try {
            DatabaseMetaData meta = connection.getMetaData();
            String catalog  = normalizeCatalog(request.getDatabaseName(), meta);
            String schema   = normalizeSchema(request.getSchemaName(), meta);

            log.info("Extracting metadata: catalog={}, schema={}", catalog, schema);

            List<TableMetadata> tables = extractTables(meta, catalog, schema, request.getIncludeTables());

            return SchemaMetadata.builder()
                    .databaseType(supportedType().name())
                    .databaseName(catalog)
                    .schemaName(schema)
                    .tables(tables)
                    .build();

        } catch (SQLException e) {
            throw new MetadataExtractionException("Failed to extract metadata: " + e.getMessage(), e);
        }
    }

    /** The database type this extractor supports. */
    protected abstract DatabaseType supportedType();

    // ─── Tables ──────────────────────────────────────────────────────────────

    protected List<TableMetadata> extractTables(DatabaseMetaData meta,
                                                String catalog,
                                                String schema,
                                                List<String> includeFilter) throws SQLException {
        List<TableMetadata> tables = new ArrayList<>();

        try (ResultSet rs = meta.getTables(catalog, schema, "%", new String[]{"TABLE", "VIEW"})) {
            while (rs.next()) {
                String tableName = rs.getString("TABLE_NAME");
                String tableType = rs.getString("TABLE_TYPE");
                String remarks   = rs.getString("REMARKS");

                if (!includeFilter.isEmpty() && !includeFilter.contains(tableName)) {
                    continue;
                }

                log.debug("Processing table: {}.{}", schema, tableName);

                List<ColumnMetadata>           columns     = extractColumns(meta, catalog, schema, tableName);
                List<String>                   pkColumns   = extractPrimaryKeys(meta, catalog, schema, tableName);
                List<ForeignKeyMetadata>       foreignKeys = extractForeignKeys(meta, catalog, schema, tableName);
                List<UniqueConstraintMetadata> uniqueCs    = extractUniqueConstraints(meta, catalog, schema, tableName);
                List<CheckConstraintMetadata>  checkCs     = extractCheckConstraints(meta.getConnection(), schema, tableName);

                // Mark PK columns
                Set<String> pkSet = new HashSet<>(pkColumns);
                columns.forEach(c -> c.setPrimaryKey(pkSet.contains(c.getColumnName())));

                tables.add(TableMetadata.builder()
                        .schemaName(schema)
                        .tableName(tableName)
                        .tableType(tableType)
                        .remarks(remarks)
                        .columns(columns)
                        .primaryKeyColumns(pkColumns)
                        .foreignKeys(foreignKeys)
                        .uniqueConstraints(uniqueCs)
                        .checkConstraints(checkCs)
                        .build());
            }
        }

        log.info("Extracted {} tables from schema '{}'", tables.size(), schema);
        return tables;
    }

    // ─── Columns ─────────────────────────────────────────────────────────────

    protected List<ColumnMetadata> extractColumns(DatabaseMetaData meta,
                                                  String catalog,
                                                  String schema,
                                                  String tableName) throws SQLException {
        List<ColumnMetadata> columns = new ArrayList<>();

        try (ResultSet rs = meta.getColumns(catalog, schema, tableName, "%")) {
            while (rs.next()) {
                columns.add(ColumnMetadata.builder()
                        .columnName(rs.getString("COLUMN_NAME"))
                        .dataType(rs.getString("TYPE_NAME"))
                        .ordinalPosition(rs.getInt("ORDINAL_POSITION"))
                        .nullable(rs.getInt("NULLABLE") == DatabaseMetaData.columnNullable)
                        .defaultValue(rs.getString("COLUMN_DEF"))
                        .characterMaxLength(nullableInt(rs, "COLUMN_SIZE"))
                        .numericPrecision(nullableInt(rs, "COLUMN_SIZE"))
                        .numericScale(nullableInt(rs, "DECIMAL_DIGITS"))
                        .remarks(rs.getString("REMARKS"))
                        .build());
            }
        }

        columns.sort(Comparator.comparingInt(ColumnMetadata::getOrdinalPosition));
        return columns;
    }

    // ─── Primary Keys ─────────────────────────────────────────────────────────

    protected List<String> extractPrimaryKeys(DatabaseMetaData meta,
                                              String catalog,
                                              String schema,
                                              String tableName) throws SQLException {
        Map<Integer, String> pkMap = new TreeMap<>();

        try (ResultSet rs = meta.getPrimaryKeys(catalog, schema, tableName)) {
            while (rs.next()) {
                pkMap.put(rs.getInt("KEY_SEQ"), rs.getString("COLUMN_NAME"));
            }
        }

        return new ArrayList<>(pkMap.values());
    }

    // ─── Foreign Keys ─────────────────────────────────────────────────────────

    protected List<ForeignKeyMetadata> extractForeignKeys(DatabaseMetaData meta,
                                                          String catalog,
                                                          String schema,
                                                          String tableName) throws SQLException {
        List<ForeignKeyMetadata> fks = new ArrayList<>();

        try (ResultSet rs = meta.getImportedKeys(catalog, schema, tableName)) {
            while (rs.next()) {
                fks.add(ForeignKeyMetadata.builder()
                        .constraintName(rs.getString("FK_NAME"))
                        .fkTableSchema(rs.getString("FKTABLE_SCHEM"))
                        .fkTableName(rs.getString("FKTABLE_NAME"))
                        .fkColumnName(rs.getString("FKCOLUMN_NAME"))
                        .pkTableSchema(rs.getString("PKTABLE_SCHEM"))
                        .pkTableName(rs.getString("PKTABLE_NAME"))
                        .pkColumnName(rs.getString("PKCOLUMN_NAME"))
                        .updateRule(ruleToString(rs.getShort("UPDATE_RULE")))
                        .deleteRule(ruleToString(rs.getShort("DELETE_RULE")))
                        .build());
            }
        }

        return fks;
    }

    // ─── Unique Constraints ───────────────────────────────────────────────────

    protected List<UniqueConstraintMetadata> extractUniqueConstraints(DatabaseMetaData meta,
                                                                       String catalog,
                                                                       String schema,
                                                                       String tableName) throws SQLException {
        // Group index columns by index name, keeping only non-PK unique indexes
        Map<String, List<String>> indexColumns = new LinkedHashMap<>();

        try (ResultSet rs = meta.getIndexInfo(catalog, schema, tableName, true /* unique */, false)) {
            while (rs.next()) {
                if (rs.getBoolean("NON_UNIQUE")) continue;   // skip non-unique
                String indexName = rs.getString("INDEX_NAME");
                String colName   = rs.getString("COLUMN_NAME");
                if (indexName == null || colName == null) continue;

                indexColumns.computeIfAbsent(indexName, k -> new ArrayList<>()).add(colName);
            }
        }

        List<UniqueConstraintMetadata> result = new ArrayList<>();
        indexColumns.forEach((name, cols) ->
                result.add(UniqueConstraintMetadata.builder()
                        .constraintName(name)
                        .columns(cols)
                        .build()));
        return result;
    }

    // ─── Check Constraints — default: no-op (override in subclasses) ─────────

    /**
     * Extract CHECK constraints. Default implementation returns an empty list;
     * override in database-specific subclasses that can query {@code information_schema}.
     */
    protected List<CheckConstraintMetadata> extractCheckConstraints(Connection connection,
                                                                     String schema,
                                                                     String tableName) {
        return Collections.emptyList();
    }

    // ─── Utilities ────────────────────────────────────────────────────────────

    protected String normalizeCatalog(String databaseName, DatabaseMetaData meta) {
        return (databaseName == null || databaseName.isBlank()) ? null : databaseName;
    }

    protected String normalizeSchema(String schemaName, DatabaseMetaData meta) {
        return (schemaName == null || schemaName.isBlank()) ? "public" : schemaName;
    }

    private Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int val = rs.getInt(column);
        return rs.wasNull() ? null : val;
    }

    private String ruleToString(short rule) {
        return switch (rule) {
            case DatabaseMetaData.importedKeyCascade    -> "CASCADE";
            case DatabaseMetaData.importedKeySetNull    -> "SET_NULL";
            case DatabaseMetaData.importedKeySetDefault -> "SET_DEFAULT";
            case DatabaseMetaData.importedKeyRestrict   -> "RESTRICT";
            default                                     -> "NO_ACTION";
        };
    }
}
