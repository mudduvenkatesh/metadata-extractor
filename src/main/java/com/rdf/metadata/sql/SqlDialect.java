package com.rdf.metadata.sql;

/**
 * Supported SQL dialects for dynamic statement generation.
 *
 * <p>Each dialect controls type mappings (e.g. {@code BIGINT} vs {@code NUMBER(38,0)}),
 * quoting style, and DDL syntax variations.
 */
public enum SqlDialect {

    /** ANSI SQL — broadest compatibility; used as a safe default. */
    ANSI,

    /** Snowflake SQL — uses NUMBER, TIMESTAMP_NTZ, VARIANT, etc. */
    SNOWFLAKE,

    /** PostgreSQL — uses SERIAL, BYTEA, BOOLEAN, etc. */
    POSTGRESQL
}
