package com.rdf.metadata.model;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;

/**
 * Represents the result of a schema extraction + RDF transformation.
 */
@Data
@Builder
public class ExtractionResult {

    private String databaseType;
    private String databaseName;
    private String schemaName;
    private int tablesProcessed;
    private int columnsProcessed;
    private int foreignKeysProcessed;
    private int rdfTriplesGenerated;
    private int shaclShapesGenerated;
    private Instant extractedAt;

    /** Serialized RDF ontology (OWL classes + data/object properties) */
    private String ontologyRdf;

    /** Serialized RDF SHACL shapes graph */
    private String shaclRdf;

    /** Format used for serialization */
    private RdfFormat format;

    /** Any non-fatal warnings collected during extraction */
    private List<String> warnings;
}
