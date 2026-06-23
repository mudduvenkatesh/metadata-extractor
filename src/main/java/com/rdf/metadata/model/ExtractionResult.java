package com.rdf.metadata.model;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;

/**
 * Result of a schema extraction + RDF transformation.
 *
 * <p>When {@code includeOwlAxioms} is true, the response contains four separated
 * ontology graphs rather than a single merged ontology:
 * <ul>
 *   <li>{@link #repoVocabularyRdf}  — {@code dr:} vocabulary declarations</li>
 *   <li>{@link #repoInstanceRdf}    — {@code dr:DataRepository} instance data</li>
 *   <li>{@link #schemaOntologyRdf}  — OWL domain ontology (classes + properties)</li>
 *   <li>{@link #linkingOntologyRdf} — cross-graph {@code dr:sourceTable} bridges</li>
 * </ul>
 *
 * <p>{@link #ontologyRdf} is populated with the merged union of all four graphs
 * for callers that want a single document.
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

    // ── Separated ontology graphs ─────────────────────────────────────────────

    /** {@code dr:} vocabulary graph — class and property declarations only. */
    private String repoVocabularyRdf;

    /** Repository instance graph — {@code dr:DataRepository} and structural individuals. */
    private String repoInstanceRdf;

    /** Schema (domain) ontology graph — {@code owl:Class}, properties, restrictions. */
    private String schemaOntologyRdf;

    /** Linking ontology graph — {@code owl:imports} both + {@code dr:sourceTable} bridges. */
    private String linkingOntologyRdf;

    /**
     * Merged union of all four ontology graphs in a single serialized document.
     * Populated for backward-compatible callers; prefer the separated fields for
     * fine-grained loading.
     */
    private String ontologyRdf;

    /** Serialized RDF SHACL shapes graph. */
    private String shaclRdf;

    /** Format used for serialization. */
    private RdfFormat format;

    /** Any non-fatal warnings collected during extraction. */
    private List<String> warnings;
}
