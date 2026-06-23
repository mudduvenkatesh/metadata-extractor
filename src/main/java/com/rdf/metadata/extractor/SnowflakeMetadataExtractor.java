package com.rdf.metadata.extractor;

import com.rdf.metadata.model.CheckConstraintMetadata;
import com.rdf.metadata.model.DatabaseType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Snowflake-specific schema metadata extractor.
 *
 * <p>Overrides check constraint extraction using Snowflake's
 * {@code INFORMATION_SCHEMA.TABLE_CONSTRAINTS} and {@code INFORMATION_SCHEMA.CHECK_CONSTRAINTS}.
 *
 * <p>Note: Snowflake JDBC {@link java.sql.DatabaseMetaData} methods work correctly for
 * tables/columns/PK/FK, so the abstract base handles those automatically.
 */
@Slf4j
@Component
public class SnowflakeMetadataExtractor extends AbstractJdbcMetadataExtractor {

    @Override
    protected DatabaseType supportedType() {
        return DatabaseType.SNOWFLAKE;
    }

    /**
     * Snowflake stores check constraints in INFORMATION_SCHEMA.
     * Note: Snowflake enforces NOT NULL via column metadata, so only explicit CHECK constraints appear here.
     */
    @Override
    protected List<CheckConstraintMetadata> extractCheckConstraints(Connection connection,
                                                                     String schema,
                                                                     String tableName) {
        List<CheckConstraintMetadata> constraints = new ArrayList<>();

        // Snowflake-specific query using INFORMATION_SCHEMA
        String sql = """
                SELECT tc.CONSTRAINT_NAME,
                       cc.CHECK_CLAUSE
                FROM   INFORMATION_SCHEMA.TABLE_CONSTRAINTS  tc
                JOIN   INFORMATION_SCHEMA.CHECK_CONSTRAINTS  cc
                       ON  tc.CONSTRAINT_SCHEMA = cc.CONSTRAINT_SCHEMA
                       AND tc.CONSTRAINT_NAME   = cc.CONSTRAINT_NAME
                WHERE  tc.CONSTRAINT_TYPE = 'CHECK'
                  AND  tc.TABLE_SCHEMA    = ?
                  AND  tc.TABLE_NAME      = ?
                """;

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, schema.toUpperCase());
            ps.setString(2, tableName.toUpperCase());

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    constraints.add(CheckConstraintMetadata.builder()
                            .constraintName(rs.getString("CONSTRAINT_NAME"))
                            .checkClause(rs.getString("CHECK_CLAUSE"))
                            .build());
                }
            }
        } catch (SQLException e) {
            // Snowflake may not expose CHECK constraints for all account types
            log.warn("Could not extract CHECK constraints for {}.{}: {}", schema, tableName, e.getMessage());
        }

        return constraints;
    }

    /**
     * Snowflake uses uppercase catalog/schema names by default.
     */
    @Override
    protected String normalizeCatalog(String databaseName, java.sql.DatabaseMetaData meta) {
        if (databaseName == null || databaseName.isBlank()) return null;
        return databaseName.toUpperCase();
    }

    @Override
    protected String normalizeSchema(String schemaName, java.sql.DatabaseMetaData meta) {
        if (schemaName == null || schemaName.isBlank()) return "PUBLIC";
        return schemaName.toUpperCase();
    }
}
