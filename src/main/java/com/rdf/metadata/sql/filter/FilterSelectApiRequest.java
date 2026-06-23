package com.rdf.metadata.sql.filter;

import com.rdf.metadata.model.ExtractionRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * REST request payload combining database connection details with the filter specification.
 */
@Data
public class FilterSelectApiRequest {

    /** Database connection + schema to extract from. */
    @Valid
    @NotNull
    private ExtractionRequest extractionRequest;

    /** The filter-based SELECT specification. */
    @Valid
    @NotNull
    private SelectFilterRequest filterRequest;
}
