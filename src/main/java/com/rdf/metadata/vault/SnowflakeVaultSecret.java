package com.rdf.metadata.vault;

/**
 * Immutable holder for Snowflake credentials resolved from HashiCorp Vault.
 *
 * <p>Fields are nullable — only the ones relevant to the active auth mode
 * will be populated. Callers should check for null before use.
 *
 * @param privateKey            PKCS#8 PEM string or Base64 DER (for key pair auth)
 * @param privateKeyPassphrase  Passphrase for encrypted keys (may be null)
 * @param password              Snowflake password (for password auth)
 */
public record SnowflakeVaultSecret(
        String privateKey,
        String privateKeyPassphrase,
        String password
) {
    /** True if this secret contains key pair material. */
    public boolean hasPrivateKey() {
        return privateKey != null && !privateKey.isBlank();
    }

    /** True if this secret contains a password. */
    public boolean hasPassword() {
        return password != null && !password.isBlank();
    }
}
