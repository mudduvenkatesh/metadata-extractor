package com.rdf.metadata.sql;

import com.rdf.metadata.model.ExtractionRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * Request payload for dynamic SQL generation from the SHACL model.
 *
 * <p>The caller provides standard connection details (same as {@link ExtractionRequest})
 * plus the SQL generation options: which tables, which statement types, and which dialect.
 */
@Data
public class SqlGenerationRequest {

    /** Connection details — reuses the same extraction request structure. */
    @Valid
    @NotNull
    private ExtractionRequest extractionRequest;

    /**
     * Which SQL statement types to generate.
     * Defaults to all types when empty.
     */
    private List<SqlStatementType> statementTypes = List.of(SqlStatementType.values());

    /**
     * Target SQL dialect.
     * Defaults to ANSI for broadest compatibility.
     */
    private SqlDialect dialect = SqlDialect.ANSI;

    /**
     * Optional filter: only generate statements for these table names.
     * Empty = all tables.
     */
    private List<String> includeTables = List.of();
}
