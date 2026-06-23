package com.rdf.metadata.versioning;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * GraphDB triplestore connection configuration.
 *
 * <h3>application.yml</h3>
 * <pre>{@code
 * graphdb:
 *   server-url:                  http://localhost:7200
 *   repository-id:               schema-versions
 *   username:                    ${GRAPHDB_USER:}
 *   password:                    ${GRAPHDB_PASSWORD:}
 *   connection-timeout-ms:       5000
 *   read-timeout-ms:             30000
 *   create-repository-if-absent: true
 *   enabled:                     true
 * }</pre>
 */
@Data
@ConfigurationProperties(prefix = "graphdb")
public class GraphDbProperties {

    /** GraphDB server base URL. */
    private String serverUrl = "http://localhost:7200";

    /** Target repository ID in GraphDB. */
    private String repositoryId = "schema-versions";

    /** Optional Basic-auth username. */
    private String username;

    /** Optional Basic-auth password. */
    private String password;

    /** HTTP connection timeout in milliseconds. */
    private int connectionTimeoutMs = 5000;

    /** HTTP read/socket timeout in milliseconds. */
    private int readTimeoutMs = 30000;

    /**
     * When true, the app calls the GraphDB management API on startup
     * to create the repository if it does not already exist.
     */
    private boolean createRepositoryIfAbsent = true;

    /**
     * Master switch. When false, extraction runs normally but nothing
     * is persisted to GraphDB.
     */
    private boolean enabled = true;
}
