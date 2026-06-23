package com.rdf.metadata.model;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Full metadata snapshot of a relational schema, enriched with the connection
 * properties needed to describe the source {@code dr:DataRepository} in RDF.
 */
@Data
@Builder
public class SchemaMetadata {

    // ── Identity ──────────────────────────────────────────────────────────────
    private String databaseType;   // SNOWFLAKE | POSTGRESQL
    private String databaseName;
    private String schemaName;

    // ── Connection properties (stored as dr: RDF statements) ─────────────────

    /** Hostname or IP of the database server (may be null for URL-only configs). */
    private String host;

    /** TCP port (may be null). */
    private Integer port;

    /** Full JDBC URL if provided directly. */
    private String jdbcUrl;

    /** Snowflake warehouse name (null for non-Snowflake). */
    private String warehouse;

    /** Snowflake role (null for non-Snowflake). */
    private String role;

    /** Authentication mode: PASSWORD | KEY_PAIR (null means not specified). */
    private String authMode;

    /** Timestamp of metadata extraction. */
    @Builder.Default
    private Instant extractedAt = Instant.now();

    // ── Schema content ────────────────────────────────────────────────────────
    @Builder.Default
    private List<TableMetadata> tables = new ArrayList<>();
}
