package com.rdf.metadata.extractor;

public class MetadataExtractionException extends RuntimeException {

    public MetadataExtractionException(String message, Throwable cause) {
        super(message, cause);
    }

    public MetadataExtractionException(String message) {
        super(message);
    }
}
