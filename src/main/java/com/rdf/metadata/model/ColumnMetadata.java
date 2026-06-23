package com.rdf.metadata.model;

import lombok.Builder;
import lombok.Data;

/**
 * Represents a single column in a relational table.
 */
@Data
@Builder
public class ColumnMetadata {

    private String columnName;
    private String dataType;       // VARCHAR, INTEGER, TIMESTAMP, etc.
    private int ordinalPosition;
    private boolean nullable;
    private boolean primaryKey;
    private String defaultValue;
    private Integer characterMaxLength;
    private Integer numericPrecision;
    private Integer numericScale;
    private String remarks;
}
