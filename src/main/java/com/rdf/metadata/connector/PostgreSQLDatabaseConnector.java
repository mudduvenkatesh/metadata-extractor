package com.rdf.metadata.connector;

import com.rdf.metadata.model.DatabaseType;
import com.rdf.metadata.model.ExtractionRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

/**
 * Establishes a JDBC connection to PostgreSQL.
 *
 * <p>Supports both an explicit JDBC URL and auto-construction from host/port/database.
 *
 * <p>Driver class: {@code org.postgresql.Driver}
 */
@Slf4j
@Component
public class PostgreSQLDatabaseConnector implements DatabaseConnector {

    private static final String DRIVER_CLASS = "org.postgresql.Driver";
    private static final int DEFAULT_PORT = 5432;

    static {
        try {
            Class.forName(DRIVER_CLASS);
        } catch (ClassNotFoundException e) {
            throw new ExceptionInInitializerError("PostgreSQL JDBC driver not found: " + e.getMessage());
        }
    }

    @Override
    public DatabaseType supportedType() {
        return DatabaseType.POSTGRESQL;
    }

    @Override
    public Connection connect(ExtractionRequest request) {
        String url = buildJdbcUrl(request);
        Properties props = buildProperties(request);

        log.info("Connecting to PostgreSQL: url={}, schema={}", url, request.getSchemaName());

        try {
            Connection conn = DriverManager.getConnection(url, props);
            log.info("PostgreSQL connection established successfully");
            return conn;
        } catch (SQLException e) {
            throw new DatabaseConnectionException(
                    "Failed to connect to PostgreSQL [url=" + url + "]: " + e.getMessage(), e);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────

    private String buildJdbcUrl(ExtractionRequest request) {
        if (request.getJdbcUrl() != null && !request.getJdbcUrl().isBlank()) {
            return request.getJdbcUrl();
        }

        String host = defaultIfBlank(request.getHost(), "localhost");
        int    port = (request.getPort() != null) ? request.getPort() : DEFAULT_PORT;
        String db   = defaultIfBlank(request.getDatabaseName(), "postgres");

        return "jdbc:postgresql://" + host + ":" + port + "/" + db;
    }

    private Properties buildProperties(ExtractionRequest request) {
        Properties props = new Properties();
        props.put("user", request.getUsername());
        props.put("password", request.getPassword());

        // Set default schema search path
        if (request.getSchemaName() != null && !request.getSchemaName().isBlank()) {
            props.put("currentSchema", request.getSchemaName());
        }

        // SSL defaults
        props.put("sslmode", "prefer");
        props.put("ApplicationName", "RdfMetadataExtractor");
        props.put("connectTimeout", "30");
        props.put("socketTimeout", "60");

        return props;
    }

    private String defaultIfBlank(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value;
    }
}
