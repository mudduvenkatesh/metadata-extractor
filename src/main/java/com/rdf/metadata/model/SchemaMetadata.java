package com.rdf.metadata.model;

import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents the full metadata snapshot of a relational schema.
 */
@Data
@Builder
public class SchemaMetadata {

    private String databaseType;   // SNOWFLAKE | POSTGRESQL
    private String databaseName;
    private String schemaName;
    @Builder.Default
    private List<TableMetadata> tables = new ArrayList<>();
}
