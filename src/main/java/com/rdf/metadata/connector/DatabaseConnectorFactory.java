package com.rdf.metadata.connector;

import com.rdf.metadata.model.DatabaseType;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Resolves the correct {@link DatabaseConnector} implementation for a given {@link DatabaseType}.
 *
 * <p>All {@code DatabaseConnector} beans are injected and indexed by their supported type,
 * making it trivial to add new database connectors without modifying this class.
 */
@Component
public class DatabaseConnectorFactory {

    private final Map<DatabaseType, DatabaseConnector> connectors;

    public DatabaseConnectorFactory(List<DatabaseConnector> connectorList) {
        this.connectors = connectorList.stream()
                .collect(Collectors.toMap(
                        DatabaseConnector::supportedType,
                        Function.identity()
                ));
    }

    /**
     * Return the connector for the given type.
     *
     * @throws DatabaseConnectionException if no connector is registered for that type
     */
    public DatabaseConnector getConnector(DatabaseType type) {
        DatabaseConnector connector = connectors.get(type);
        if (connector == null) {
            throw new DatabaseConnectionException(
                    "No connector registered for database type: " + type
                            + ". Available types: " + connectors.keySet());
        }
        return connector;
    }
}
