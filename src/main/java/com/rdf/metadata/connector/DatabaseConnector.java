package com.rdf.metadata.connector;

import com.rdf.metadata.model.ExtractionRequest;

import java.sql.Connection;

/**
 * Strategy interface for establishing a JDBC connection to a specific database type.
 * Each implementation is responsible for building the correct driver URL and properties.
 */
public interface DatabaseConnector {

    /**
     * Open and return a live JDBC connection using the parameters in the request.
     *
     * @param request the extraction request containing connection parameters
     * @return an open {@link Connection} – caller is responsible for closing it
     * @throws DatabaseConnectionException if the connection cannot be established
     */
    Connection connect(ExtractionRequest request);

    /**
     * Indicate which {@link com.rdf.metadata.model.DatabaseType} this connector handles.
     */
    com.rdf.metadata.model.DatabaseType supportedType();
}
