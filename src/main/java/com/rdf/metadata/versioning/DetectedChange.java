package com.rdf.metadata.versioning;

/**
 * A single structural difference detected between two consecutive schema versions.
 *
 * @param changeType    the category of change
 * @param tableName     the affected table name (always set)
 * @param columnName    the affected column name (null for table-level changes)
 * @param previousValue the old value of the changed attribute (null for additions)
 * @param newValue      the new value of the changed attribute (null for deletions)
 * @param description   human-readable summary
 */
public record DetectedChange(
        SchemaChangeType changeType,
        String tableName,
        String columnName,
        String previousValue,
        String newValue,
        String description
) {
    /** Convenience for table-level changes with no column involved. */
    public static DetectedChange tableChange(SchemaChangeType type,
                                              String tableName,
                                              String description) {
        return new DetectedChange(type, tableName, null, null, null, description);
    }

    /** Convenience for column-level changes. */
    public static DetectedChange columnChange(SchemaChangeType type,
                                               String tableName, String columnName,
                                               String previousValue, String newValue,
                                               String description) {
        return new DetectedChange(type, tableName, columnName,
                previousValue, newValue, description);
    }
}
