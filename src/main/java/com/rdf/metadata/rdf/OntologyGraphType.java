package com.rdf.metadata.rdf;

/**
 * Identifies the three separate RDF ontology graphs produced by the extraction pipeline.
 *
 * <h3>Graph responsibilities</h3>
 * <ul>
 *   <li>{@link #REPO_VOCABULARY}  — {@code dr:} vocabulary: class and property declarations only.
 *       No instance data. Reusable across any number of repositories.</li>
 *   <li>{@link #REPO_INSTANCE}    — Instance graph for one specific database connection.
 *       Contains {@code dr:DataRepository}, {@code dr:DatabaseSchema},
 *       {@code dr:DatabaseTable}, and {@code dr:TableColumn} individuals.
 *       Imports {@link #REPO_VOCABULARY}.</li>
 *   <li>{@link #SCHEMA_ONTOLOGY}  — OWL domain ontology derived from the relational schema.
 *       Contains {@code owl:Class}, {@code owl:DatatypeProperty},
 *       {@code owl:ObjectProperty}, cardinality restrictions, and {@code owl:hasKey}.
 *       Imports {@link #REPO_VOCABULARY} to use its cross-link properties.</li>
 * </ul>
 *
 * <h3>Connection</h3>
 * A fourth <em>linking ontology</em> ({@link #LINKING_ONTOLOGY}) imports both
 * {@link #REPO_INSTANCE} and {@link #SCHEMA_ONTOLOGY} and adds the alignment triples
 * ({@code schema:Orders dr:sourceTable schema:Orders_TableNode}) that bridge the two worlds.
 */
public enum OntologyGraphType {
    REPO_VOCABULARY,
    REPO_INSTANCE,
    SCHEMA_ONTOLOGY,
    LINKING_ONTOLOGY
}
