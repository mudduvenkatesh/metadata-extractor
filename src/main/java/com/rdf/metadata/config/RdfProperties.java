package com.rdf.metadata.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Namespace and output directory configuration for RDF generation.
 */
@Data
@ConfigurationProperties(prefix = "rdf")
public class RdfProperties {

    private String baseNamespace = "http://example.org/schema#";
    private String owlNamespace  = "http://www.w3.org/2002/07/owl#";
    private String shaclNamespace = "http://www.w3.org/ns/shacl#";
    private String xsdNamespace  = "http://www.w3.org/2001/XMLSchema#";
    private String outputDirectory = "./output";
}
