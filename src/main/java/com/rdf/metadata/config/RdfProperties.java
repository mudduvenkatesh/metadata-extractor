package com.rdf.metadata.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Namespace and output directory configuration for RDF generation.
 */
@Data
@ConfigurationProperties(prefix = "rdf")
public class RdfProperties {

    /** Base namespace for OWL classes and properties derived from table/column names. */
    private String baseNamespace = "http://example.org/schema#";

    /**
     * Namespace for the DataRepository vocabulary ({@code dr:} prefix).
     * Overrides the default in {@link com.rdf.metadata.rdf.DataRepositoryVocabulary#NS}.
     * Leave blank to use the vocabulary's built-in default.
     */
    private String dataRepositoryNamespace = "http://example.org/datarepository#";

    private String owlNamespace    = "http://www.w3.org/2002/07/owl#";
    private String shaclNamespace  = "http://www.w3.org/ns/shacl#";
    private String xsdNamespace    = "http://www.w3.org/2001/XMLSchema#";
    private String outputDirectory = "./output";
}
