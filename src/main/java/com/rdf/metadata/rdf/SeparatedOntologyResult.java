package com.rdf.metadata.rdf;

import lombok.Builder;
import lombok.Data;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Model;

/**
 * The result of building the separated ontology system — four independent
 * RDF4J {@link Model} instances, each representing a distinct named ontology graph.
 *
 * <h3>Deployment model</h3>
 * <pre>
 *   repo-vocabulary.ttl   ◄─── imported by ───────────────────────┐
 *                                                                   │
 *   repo-instance.ttl     (owl:imports repo-vocabulary.ttl)        │
 *         │                                                         │
 *         │ owl:imports                                             │
 *         ▼                                                         │
 *   schema-ontology.ttl   (owl:imports repo-vocabulary.ttl) ───────┘
 *         │
 *         │ owl:imports (both)
 *         ▼
 *   linking-ontology.ttl  (owl:imports repo-instance + schema-ontology)
 *                          adds dr:sourceTable bridges
 * </pre>
 *
 * <p>Each graph can be loaded and queried independently, or all four can be
 * merged into a single triplestore for cross-graph SPARQL queries.
 */
@Data
@Builder
public class SeparatedOntologyResult {

    /** IRI of the vocabulary ontology (the {@code dr:} namespace graph). */
    private IRI repoVocabularyIri;

    /** IRI of the repository instance ontology (one per database connection). */
    private IRI repoInstanceIri;

    /** IRI of the schema (domain) ontology (one per schema extraction). */
    private IRI schemaOntologyIri;

    /** IRI of the linking ontology that bridges instance and schema graphs. */
    private IRI linkingOntologyIri;

    /**
     * {@code dr:} vocabulary model.
     * Contains only class and property declarations — no instance data.
     */
    private Model repoVocabularyModel;

    /**
     * Repository instance model.
     * Contains {@code dr:DataRepository}, schema, table and column individuals.
     */
    private Model repoInstanceModel;

    /**
     * Schema (domain) ontology model.
     * Contains {@code owl:Class}, {@code owl:DatatypeProperty},
     * {@code owl:ObjectProperty}, restrictions, and {@code owl:hasKey}.
     */
    private Model schemaOntologyModel;

    /**
     * Linking ontology model.
     * Imports both {@link #repoInstanceModel} and {@link #schemaOntologyModel}.
     * Adds {@code dr:sourceTable} bridges from each {@code owl:Class}
     * to its corresponding {@code dr:DatabaseTable} individual.
     */
    private Model linkingOntologyModel;

    /** Total triple count across all four models. */
    public int totalTriples() {
        return repoVocabularyModel.size()
             + repoInstanceModel.size()
             + schemaOntologyModel.size()
             + linkingOntologyModel.size();
    }
}
