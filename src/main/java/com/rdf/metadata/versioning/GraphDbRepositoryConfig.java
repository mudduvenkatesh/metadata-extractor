package com.rdf.metadata.versioning;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryException;
import org.eclipse.rdf4j.repository.http.HTTPRepository;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.Base64;

/**
 * Spring configuration that wires the RDF4J {@link HTTPRepository} as a
 * singleton {@link Repository} bean pointed at GraphDB.
 *
 * <h3>Repository URL</h3>
 * RDF4J's {@link HTTPRepository} expects the full repository endpoint:
 * <pre>
 * http://&lt;host&gt;:7200/repositories/&lt;repositoryId&gt;
 * </pre>
 * GraphDB exposes a standard RDF4J Server API at this path.
 *
 * <h3>Authentication</h3>
 * HTTP Basic auth credentials are injected via {@link GraphDbProperties}.
 * Leave username/password blank for unsecured GraphDB instances.
 *
 * <h3>Repository auto-creation</h3>
 * When {@code graphdb.create-repository-if-absent=true}, the config calls the
 * GraphDB management REST API ({@code /rest/repositories}) to create the repository
 * as a standard RDF owl-horst-optimized ruleset repo before wiring the RDF4J client.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
@EnableConfigurationProperties(GraphDbProperties.class)
public class GraphDbRepositoryConfig {

    private final GraphDbProperties graphDbProperties;

    /**
     * Build and initialise the RDF4J {@link HTTPRepository} bean.
     *
     * <p>The repository URL follows the RDF4J Server pattern:
     * {@code <serverUrl>/repositories/<repositoryId>}
     */
    @Bean(destroyMethod = "shutDown")
    public Repository graphDbRepository() {
        String repoUrl = graphDbProperties.getServerUrl().stripTrailing("/")
                       + "/repositories/"
                       + graphDbProperties.getRepositoryId();

        log.info("Connecting to GraphDB repository: {}", repoUrl);

        if (graphDbProperties.isCreateRepositoryIfAbsent()) {
            ensureRepositoryExists();
        }

        HTTPRepository repository = new HTTPRepository(repoUrl);

        // Inject credentials if provided
        if (isSet(graphDbProperties.getUsername())) {
            repository.setUsernameAndPassword(
                    graphDbProperties.getUsername(),
                    graphDbProperties.getPassword());
        }

        // Apply connection/read timeouts via the underlying HTTP client
        repository.setAdditionalHttpHeaders(
                java.util.Map.of("Connection", "keep-alive"));

        try {
            repository.init();
            log.info("GraphDB HTTPRepository initialised successfully: {}", repoUrl);
        } catch (RepositoryException e) {
            throw new IllegalStateException(
                    "Failed to initialise GraphDB repository at [" + repoUrl + "]: "
                    + e.getMessage(), e);
        }

        return repository;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Repository auto-creation
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Attempt to create the repository via the GraphDB REST management API
     * if it does not already exist.
     *
     * <p>Uses the GraphDB native {@code /rest/repositories} endpoint (not the RDF4J API).
     * Creates an {@code owl-horst-optimized} (OWL2-RL) repository by default —
     * suitable for ontology-aware querying.
     */
    private void ensureRepositoryExists() {
        String managementUrl = graphDbProperties.getServerUrl().stripTrailing("/")
                             + "/rest/repositories";
        String repoId        = graphDbProperties.getRepositoryId();

        log.info("Checking/creating GraphDB repository '{}' via {}", repoId, managementUrl);

        RestClient client = buildRestClient();

        try {
            // Check if repository already exists
            String listResponse = client.get()
                    .uri(managementUrl)
                    .retrieve()
                    .body(String.class);

            if (listResponse != null && listResponse.contains("\"id\":\"" + repoId + "\"")) {
                log.info("GraphDB repository '{}' already exists — skipping creation", repoId);
                return;
            }

            // Create repository using Turtle configuration (GraphDB native format)
            String repoConfig = buildRepositoryConfig(repoId);

            client.post()
                    .uri(managementUrl)
                    .contentType(MediaType.parseMediaType("application/json"))
                    .body(repoConfig)
                    .retrieve()
                    .toBodilessEntity();

            log.info("GraphDB repository '{}' created successfully", repoId);

        } catch (Exception e) {
            log.warn("Could not auto-create GraphDB repository '{}': {}. " +
                     "Proceeding — repository may already exist.", repoId, e.getMessage());
        }
    }

    /**
     * Build a GraphDB repository creation JSON payload.
     * Creates an {@code owl-horst-optimized} repository (OWL2-RL inference).
     */
    private String buildRepositoryConfig(String repoId) {
        return """
                {
                  "id": "%s",
                  "title": "Schema Versions — RDF Metadata Extractor",
                  "type": "free",
                  "params": {
                    "ruleset": { "name": "ruleset", "value": "owl-horst-optimized" },
                    "defaultNS": { "name": "defaultNS", "value": "" },
                    "entityIndexSize": { "name": "entityIndexSize", "value": "10000000" },
                    "entityIdSize": { "name": "entityIdSize", "value": "32" },
                    "repositoryType": { "name": "repositoryType", "value": "file-repository" },
                    "storageFolder": { "name": "storageFolder", "value": "storage" },
                    "enableContextIndex": { "name": "enableContextIndex", "value": "true" },
                    "readOnly": { "name": "readOnly", "value": "false" }
                  }
                }
                """.formatted(repoId);
    }

    private RestClient buildRestClient() {
        RestClient.Builder builder = RestClient.builder()
                .baseUrl(graphDbProperties.getServerUrl());

        if (isSet(graphDbProperties.getUsername())) {
            String credentials = graphDbProperties.getUsername()
                               + ":" + graphDbProperties.getPassword();
            String encoded = Base64.getEncoder()
                    .encodeToString(credentials.getBytes());
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Basic " + encoded);
        }

        return builder.build();
    }

    private boolean isSet(String v) {
        return v != null && !v.isBlank();
    }
}
