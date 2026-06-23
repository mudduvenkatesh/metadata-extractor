package com.rdf.metadata.vault;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Application-level Vault configuration, bound from {@code vault.*} in {@code application.yml}.
 *
 * <p>Spring Cloud Vault's own bootstrap configuration uses {@code spring.cloud.vault.*}.
 * This bean captures additional application-specific settings such as the default
 * KV engine mount point and the default secret path prefix.
 *
 * <h3>Example {@code application.yml}</h3>
 * <pre>{@code
 * vault:
 *   kv-engine: secret          # KV mount point (default: "secret")
 *   kv-version: 2              # 1 = KV v1, 2 = KV v2 (default: 2)
 *   secret-path-prefix: rdf-extractor/snowflake
 *   enabled: true
 * }</pre>
 */
@Data
@ConfigurationProperties(prefix = "vault")
public class VaultProperties {

    /** Whether Vault integration is enabled. Defaults to {@code true}. */
    private boolean enabled = true;

    /**
     * KV secrets engine mount point.
     * For KV v2 this is the path before {@code /data/} in the full API path.
     * Default: {@code "secret"}.
     */
    private String kvEngine = "secret";

    /**
     * KV version to use when constructing the read path.
     * {@code 1} = KV v1 (path: {@code <engine>/<secret-path>}),
     * {@code 2} = KV v2 (path: {@code <engine>/data/<secret-path>}).
     * Default: {@code 2}.
     */
    private int kvVersion = 2;

    /**
     * Optional prefix prepended to every {@code vaultSecretPath} supplied in a request.
     * Useful for namespacing all secrets under a common path.
     * Example: {@code rdf-extractor/snowflake} → full path becomes
     * {@code rdf-extractor/snowflake/<vaultSecretPath>}.
     * Leave blank to use the raw {@code vaultSecretPath} from the request.
     */
    private String secretPathPrefix = "";
}
