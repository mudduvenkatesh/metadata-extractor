package com.rdf.metadata.sql.filter;

import java.util.List;

/**
 * Thrown when a {@link SelectFilterRequest} fails shape-aware validation —
 * e.g. a column does not exist in the SHACL shape, or an operator is
 * incompatible with the column's XSD datatype.
 */
public class FilterValidationException extends RuntimeException {

    private final List<String> errors;

    public FilterValidationException(List<String> errors) {
        super("Filter validation failed: " + String.join("; ", errors));
        this.errors = errors;
    }

    public FilterValidationException(String message) {
        super(message);
        this.errors = List.of(message);
    }

    public List<String> getErrors() {
        return errors;
    }
}
