package com.rdf.metadata.model;

import lombok.Builder;
import lombok.Data;

/**
 * Represents a foreign key relationship between two tables.
 */
@Data
@Builder
public class ForeignKeyMetadata {

    private String constraintName;

    // Owning side (FK column)
    private String fkTableSchema;
    private String fkTableName;
    private String fkColumnName;

    // Referenced side (PK column)
    private String pkTableSchema;
    private String pkTableName;
    private String pkColumnName;

    /** Update/delete rule: NO_ACTION | CASCADE | SET_NULL | SET_DEFAULT | RESTRICT */
    private String updateRule;
    private String deleteRule;
}
