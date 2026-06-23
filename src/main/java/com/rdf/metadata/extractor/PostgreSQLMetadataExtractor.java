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
 * PostgreSQL-specific schema metadata extractor.
 *
 * <p>Overrides check constraint extraction using PostgreSQL's
 * {@code information_schema.check_constraints} joined with
 * {@code information_schema.table_constraints}.
 *
 * <p>PostgreSQL's JDBC DatabaseMetaData implementation handles
 * tables/columns/PK/FK well, so those are handled by the abstract base class.
 */
@Slf4j
@Component
public class PostgreSQLMetadataExtractor extends AbstractJdbcMetadataExtractor {

    @Override
    protected DatabaseType supportedType() {
        return DatabaseType.POSTGRESQL;
    }

    /**
     * PostgreSQL stores check constraints (including NOT NULL rewrites) in information_schema.
     * We filter out the auto-generated NOT NULL checks ({@code IS NOT NULL}) to avoid noise.
     */
    @Override
    protected List<CheckConstraintMetadata> extractCheckConstraints(Connection connection,
                                                                     String schema,
                                                                     String tableName) {
        List<CheckConstraintMetadata> constraints = new ArrayList<>();

        // Exclude PostgreSQL's auto-generated NOT NULL checks (they end with IS NOT NULL)
        String sql = """
                SELECT tc.constraint_name,
                       cc.check_clause
                FROM   information_schema.table_constraints  tc
                JOIN   information_schema.check_constraints  cc
                       ON  tc.constraint_schema = cc.constraint_schema
                       AND tc.constraint_name   = cc.constraint_name
                WHERE  tc.constraint_type = 'CHECK'
                  AND  tc.table_schema    = ?
                  AND  tc.table_name      = ?
                  AND  cc.check_clause NOT LIKE '%IS NOT NULL'
                ORDER  BY tc.constraint_name
                """;

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, schema);
            ps.setString(2, tableName);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    constraints.add(CheckConstraintMetadata.builder()
                            .constraintName(rs.getString("constraint_name"))
                            .checkClause(rs.getString("check_clause"))
                            .build());
                }
            }
        } catch (SQLException e) {
            log.warn("Could not extract CHECK constraints for {}.{}: {}", schema, tableName, e.getMessage());
        }

        return constraints;
    }
}
