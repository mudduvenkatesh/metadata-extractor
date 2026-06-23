package com.rdf.metadata.vault;

/**
 * Field name constants expected inside a HashiCorp Vault KV secret
 * used for Snowflake authentication.
 *
 * <h3>Expected Vault secret structure (KV v2 example)</h3>
 *
 * <p>For <b>key pair auth</b>, write the secret with at minimum {@code privateKey}:
 * <pre>{@code
 * vault kv put secret/rdf-extractor/snowflake/my-connection \
 *   privateKey=@/path/to/rsa_key.p8 \
 *   privateKeyPassphrase="my_passphrase"
 * }</pre>
 *
 * <p>For <b>password auth</b>, write the secret with {@code password}:
 * <pre>{@code
 * vault kv put secret/rdf-extractor/snowflake/my-connection \
 *   password="my_snowflake_password"
 * }</pre>
 *
 * <p>All fields are optional; only the ones relevant to the active
 * {@link com.rdf.metadata.model.SnowflakeAuthMode} are read.
 */
public final class VaultSecretFields {

    private VaultSecretFields() {}

    // ── Key pair auth fields ──────────────────────────────────────────────────

    /**
     * The PKCS#8 private key — stored as a PEM string or Base64-encoded DER bytes.
     * Maps to Vault field {@code "privateKey"}.
     */
    public static final String PRIVATE_KEY            = "privateKey";

    /**
     * Optional passphrase for an encrypted private key.
     * Maps to Vault field {@code "privateKeyPassphrase"}.
     */
    public static final String PRIVATE_KEY_PASSPHRASE = "privateKeyPassphrase";

    // ── Password auth field ───────────────────────────────────────────────────

    /**
     * Snowflake user password for password-based auth.
     * Maps to Vault field {@code "password"}.
     */
    public static final String PASSWORD               = "password";
}
