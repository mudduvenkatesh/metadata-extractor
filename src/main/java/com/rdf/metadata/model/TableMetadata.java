package com.rdf.metadata.model;

import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a relational table with its columns, primary key, foreign keys, and check constraints.
 */
@Data
@Builder
public class TableMetadata {

    private String schemaName;
    private String tableName;
    private String tableType;    // TABLE | VIEW
    private String remarks;

    @Builder.Default
    private List<ColumnMetadata> columns = new ArrayList<>();

    @Builder.Default
    private List<String> primaryKeyColumns = new ArrayList<>();

    @Builder.Default
    private List<ForeignKeyMetadata> foreignKeys = new ArrayList<>();

    @Builder.Default
    private List<UniqueConstraintMetadata> uniqueConstraints = new ArrayList<>();

    @Builder.Default
    private List<CheckConstraintMetadata> checkConstraints = new ArrayList<>();

    /** Fully qualified label: schema.table */
    public String qualifiedName() {
        return schemaName + "." + tableName;
    }
}
