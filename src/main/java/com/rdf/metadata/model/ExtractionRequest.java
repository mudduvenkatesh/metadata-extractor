package com.rdf.metadata.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Request payload for extracting schema metadata and converting to RDF.
 *
 * <h3>Snowflake Authentication Modes</h3>
 *
 * <p><b>Password auth — inline:</b>
 * <pre>{@code
 * { "authMode": "PASSWORD", "password": "secret", ... }
 * }</pre>
 *
 * <p><b>Password auth — from Vault:</b>
 * <pre>{@code
 * { "authMode": "PASSWORD", "vaultSecretPath": "prod/snowflake-creds", ... }
 * }</pre>
 *
 * <p><b>Key pair auth — inline PEM:</b>
 * <pre>{@code
 * { "authMode": "KEY_PAIR", "privateKeyPem": "-----BEGIN PRIVATE KEY-----\n...", ... }
 * }</pre>
 *
 * <p><b>Key pair auth — from Vault (recommended for production):</b>
 * <pre>{@code
 * { "authMode": "KEY_PAIR", "vaultSecretPath": "prod/snowflake-keypair", ... }
 * }</pre>
 * Vault secret must contain a {@code privateKey} field (PEM or Base64 DER),
 * and optionally {@code privateKeyPassphrase}.
 *
 * <h3>Vault secret resolution priority (key pair)</h3>
 * <ol>
 *   <li>Vault ({@code vaultSecretPath} present)</li>
 *   <li>Inline {@code privateKeyPem}</li>
 *   <li>File path ({@code privateKeyPath})</li>
 *   <li>Base64 DER ({@code privateKeyBase64})</li>
 * </ol>
 *
 * <h3>Vault secret resolution priority (password)</h3>
 * <ol>
 *   <li>Vault ({@code vaultSecretPath} present)</li>
 *   <li>Inline {@code password}</li>
 * </ol>
 */
@Data
public class ExtractionRequest {

    @NotNull(message = "databaseType is required")
    private DatabaseType databaseType;

    /** Snowflake auth mode. Defaults to PASSWORD for backwards compatibility. */
    private SnowflakeAuthMode authMode = SnowflakeAuthMode.PASSWORD;

    // ── JDBC Connection ──────────────────────────────────────────────────────

    /** Full JDBC URL — optional; auto-built from {@code account} if absent. */
    private String jdbcUrl;

    @NotBlank(message = "username is required")
    private String username;

    /**
     * Snowflake password for PASSWORD auth — inline.
     * Ignored when {@code vaultSecretPath} is set.
     */
    private String password;

    /** Target database/catalog name. */
    private String databaseName;

    @NotBlank(message = "schemaName is required")
    private String schemaName;

    // ── Snowflake-specific ───────────────────────────────────────────────────

    /** Snowflake account identifier, e.g. {@code xy12345.us-east-1} */
    private String account;
    private String warehouse;
    private String role;

    // ── Vault Secret Reference ────────────────────────────────────────────────

    /**
     * Relative path of the Vault KV secret that holds this connection's credentials.
     *
     * <p>When set, credentials are fetched exclusively from Vault — inline credential
     * fields ({@code password}, {@code privateKeyPem}, etc.) are ignored.
     *
     * <p>The full Vault path is constructed as:
     * <pre>
     *   KV v2: &lt;vault.kv-engine&gt;/data/&lt;vault.secret-path-prefix&gt;/&lt;vaultSecretPath&gt;
     *   KV v1: &lt;vault.kv-engine&gt;/&lt;vault.secret-path-prefix&gt;/&lt;vaultSecretPath&gt;
     * </pre>
     *
     * <p>Example: {@code "prod/snowflake-keypair"} with prefix {@code "rdf-extractor"}
     * → reads {@code secret/data/rdf-extractor/prod/snowflake-keypair}
     */
    private String vaultSecretPath;

    // ── Inline Key Pair Authentication (fallback when vaultSecretPath is absent) ──

    /**
     * Inline PKCS#8 PEM private key string.
     * Accepts full PEM block or bare Base64 body.
     * Ignored when {@code vaultSecretPath} is set.
     */
    private String privateKeyPem;

    /**
     * Absolute filesystem path to a PKCS#8 {@code .p8} / {@code .pem} key file.
     * Used when neither {@code vaultSecretPath} nor {@code privateKeyPem} is set.
     */
    private String privateKeyPath;

    /**
     * Base64-encoded DER bytes of a PKCS#8 private key.
     * Used when neither {@code vaultSecretPath}, {@code privateKeyPem},
     * nor {@code privateKeyPath} is set.
     */
    private String privateKeyBase64;

    /**
     * Passphrase for an encrypted private key — inline.
     * Ignored when {@code vaultSecretPath} is set (passphrase comes from Vault instead).
     */
    private String privateKeyPassphrase;

    // ── PostgreSQL-specific ──────────────────────────────────────────────────

    private String host;
    private Integer port;

    // ── Output Options ───────────────────────────────────────────────────────

    private List<String> includeTables = new ArrayList<>();
    private RdfFormat rdfFormat = RdfFormat.TURTLE;
    private boolean includeShaclShapes = true;
    private boolean includeOwlAxioms = true;

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Returns true if credentials should be resolved from Vault. */
    public boolean usesVault() {
        return vaultSecretPath != null && !vaultSecretPath.isBlank();
    }
}
