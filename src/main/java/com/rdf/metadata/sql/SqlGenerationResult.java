package com.rdf.metadata.sql;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Result of dynamic SQL generation from a SHACL shapes model.
 */
@Data
@Builder
public class SqlGenerationResult {

    private String    schemaName;
    private SqlDialect dialect;
    private int       tablesProcessed;
    private int       statementsGenerated;
    private Instant   generatedAt;

    /**
     * Generated statements grouped by table name.
     * Each key is a table name; the value is the ordered list of SQL statements
     * for that table in the same order as {@code statementTypes} in the request.
     */
    private Map<String, List<SqlStatement>> statementsByTable;
}
