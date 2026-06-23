package com.rdf.metadata.versioning;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

/**
 * Describes a single stored schema version in GraphDB.
 */
@Data
@Builder
public class StoredSchemaVersion {

    private String  databaseName;
    private String  schemaName;
    private int     versionNumber;
    private String  versionLabel;       // "v1", "v2", …
    private Instant extractedAt;
    private int     tableCount;
    private int     columnCount;
    private int     changeCount;        // 0 for the first version
    private boolean isCurrent;

    /** IRI of the named graph holding the full snapshot (tables + columns). */
    private String snapshotGraphIri;

    /** IRI of the named graph holding the SchemaChange individuals (null for v1). */
    private String changesGraphIri;

    /** IRI of the registry named graph for this (db, schema) pair. */
    private String registryGraphIri;

    /** IRI of the dr:SchemaVersion individual in the registry graph. */
    private String versionNodeIri;
}
