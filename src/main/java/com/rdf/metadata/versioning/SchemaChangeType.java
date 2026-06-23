package com.rdf.metadata.versioning;

/**
 * Types of structural changes detectable between two consecutive schema versions.
 */
public enum SchemaChangeType {

    // ── Table-level ───────────────────────────────────────────────────────────

    /** A table that did not exist in the previous version is present in the new version. */
    TABLE_ADDED,

    /** A table that existed in the previous version is absent from the new version. */
    TABLE_DROPPED,

    // ── Column-level ──────────────────────────────────────────────────────────

    /** A column was added to an existing table. */
    COLUMN_ADDED,

    /** A column was removed from an existing table. */
    COLUMN_DROPPED,

    /** The SQL data type of a column changed (e.g. VARCHAR → TEXT). */
    COLUMN_TYPE_CHANGED,

    /** A column changed from nullable to NOT NULL or vice versa. */
    COLUMN_NULLABILITY_CHANGED,

    /** The {@code maxLength} constraint of a string column changed. */
    COLUMN_LENGTH_CHANGED,

    // ── Constraint-level ─────────────────────────────────────────────────────

    /** The set of primary key columns for a table changed. */
    PRIMARY_KEY_CHANGED,

    /** A foreign key constraint was added. */
    FOREIGN_KEY_ADDED,

    /** A foreign key constraint was dropped. */
    FOREIGN_KEY_DROPPED
}
