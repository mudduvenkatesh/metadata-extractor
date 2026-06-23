package com.rdf.metadata.extractor;

import com.rdf.metadata.model.ExtractionRequest;
import com.rdf.metadata.model.SchemaMetadata;

/**
 * Extracts relational schema metadata (tables, columns, PK, FK, constraints)
 * from an open JDBC {@link java.sql.Connection}.
 *
 * <p>Implementations use JDBC {@link java.sql.DatabaseMetaData} and/or
 * database-specific information_schema queries to populate a {@link SchemaMetadata} object.
 */
public interface SchemaMetadataExtractor {

    /**
     * Extract full schema metadata for the database/schema specified in the request.
     *
     * @param request the extraction parameters (schema name, table filter, etc.)
     * @param connection an open, ready-to-use JDBC connection
     * @return fully populated {@link SchemaMetadata}
     */
    SchemaMetadata extract(ExtractionRequest request, java.sql.Connection connection);
}
