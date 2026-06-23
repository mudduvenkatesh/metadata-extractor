package com.rdf.metadata.sql;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * A single generated SQL statement and its associated metadata.
 */
@Data
@Builder
public class SqlStatement {

    /** The SQL statement type (CREATE_TABLE, SELECT, INSERT, etc.) */
    private SqlStatementType type;

    /** The target table name as it appears in the SQL statement. */
    private String tableName;

    /** The fully rendered SQL string. */
    private String sql;

    /** The SQL dialect this statement was generated for. */
    private SqlDialect dialect;

    /**
     * Named bind parameters in declaration order — relevant for INSERT, UPDATE, DELETE.
     * Each entry is a column name that maps to a {@code :columnName} or {@code ?} placeholder.
     */
    private List<String> bindParameters;
}
