package com.rdf.metadata.model;

import org.eclipse.rdf4j.rio.RDFFormat;

/**
 * Supported RDF serialization formats, mapped to RDF4J {@link RDFFormat} constants.
 */
public enum RdfFormat {

    TURTLE   ("text/turtle",              ".ttl",     RDFFormat.TURTLE),
    JSON_LD  ("application/ld+json",      ".jsonld",  RDFFormat.JSONLD),
    RDF_XML  ("application/rdf+xml",      ".rdf",     RDFFormat.RDFXML),
    N_TRIPLES("application/n-triples",    ".nt",      RDFFormat.NTRIPLES),
    TRIG     ("application/trig",         ".trig",    RDFFormat.TRIG);

    private final String mimeType;
    private final String extension;
    private final RDFFormat rdf4jFormat;

    RdfFormat(String mimeType, String extension, RDFFormat rdf4jFormat) {
        this.mimeType    = mimeType;
        this.extension   = extension;
        this.rdf4jFormat = rdf4jFormat;
    }

    public String    getMimeType()    { return mimeType; }
    public String    getExtension()   { return extension; }
    public RDFFormat getRdf4jFormat() { return rdf4jFormat; }
}
