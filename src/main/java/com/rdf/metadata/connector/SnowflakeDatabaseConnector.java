package com.rdf.metadata.connector;

import com.rdf.metadata.model.DatabaseType;
import com.rdf.metadata.model.ExtractionRequest;
import com.rdf.metadata.model.SnowflakeAuthMode;
import com.rdf.metadata.vault.SnowflakeVaultSecret;
import com.rdf.metadata.vault.VaultSecretService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.security.PrivateKey;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

/**
 * Establishes a JDBC connection to Snowflake.
 *
 * <h3>Authentication modes</h3>
 *
 * <p><b>PASSWORD</b> — standard username + password.
 * Password is resolved from Vault if {@code vaultSecretPath} is set,
 * otherwise taken from the inline {@code password} field.
 *
 * <p><b>KEY_PAIR</b> — RSA private key (PKCS#8).
 * Key material is resolved from Vault if {@code vaultSecretPath} is set;
 * otherwise the inline fields are checked in priority order:
 * {@code privateKeyPem} → {@code privateKeyPath} → {@code privateKeyBase64}.
 *
 * <h3>Vault secret structure</h3>
 * <pre>
 *   Key pair:  { "privateKey": "-----BEGIN PRIVATE KEY-----\n...",
 *                "privateKeyPassphrase": "optional" }
 *   Password:  { "password": "snowflake_password" }
 * </pre>
 *
 * Write with:
 * <pre>{@code
 * vault kv put secret/rdf-extractor/snowflake/prod \
 *     privateKey=@rsa_key.p8 privateKeyPassphrase="passphrase"
 * }</pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class   SnowflakeDatabaseConnector implements DatabaseConnector {

    private static final String DRIVER_CLASS = "net.snowflake.client.jdbc.SnowflakeDriver";

    static {
        try {
            Class.forName(DRIVER_CLASS);
        } catch (ClassNotFoundException e) {
            throw new ExceptionInInitializerError("Snowflake JDBC driver not found: " + e.getMessage());
        }
    }

    /** Injected by Spring — used only when request.usesVault() == true. */
    private final VaultSecretService vaultSecretService;

    @Override
    public DatabaseType supportedType() {
        return DatabaseType.SNOWFLAKE;
    }

    @Override
    public Connection connect(ExtractionRequest request) {
        SnowflakeAuthMode authMode = request.getAuthMode() != null
                ? request.getAuthMode()
                : SnowflakeAuthMode.PASSWORD;

        String url = buildJdbcUrl(request);

        log.info("Connecting to Snowflake: url={}, db={}, schema={}, warehouse={}, authMode={}, vault={}",
                url, request.getDatabaseName(), request.getSchemaName(),
                request.getWarehouse(), authMode, request.usesVault());

        // Resolve credentials — from Vault or inline
        Properties props = switch (authMode) {
            case PASSWORD -> buildPasswordProperties(request);
            case KEY_PAIR -> buildKeyPairProperties(request);
        };

        try {
            Connection conn = DriverManager.getConnection(url, props);
            log.info("Snowflake connection established (authMode={}, vault={})",
                    authMode, request.usesVault());
            return conn;
        } catch (SQLException e) {
            throw new DatabaseConnectionException(
                    "Failed to connect to Snowflake [url=" + url + ", authMode=" + authMode
                    + "]: " + e.getMessage(), e);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // URL construction
    // ─────────────────────────────────────────────────────────────────────────

    private String buildJdbcUrl(ExtractionRequest request) {
        if (isSet(request.getJdbcUrl())) return request.getJdbcUrl();
        if (!isSet(request.getAccount())) {
            throw new DatabaseConnectionException(
                    "Either jdbcUrl or account must be provided for Snowflake connections");
        }
        String account = request.getAccount().replace(".snowflakecomputing.com", "");
        return "jdbc:snowflake://" + account + ".snowflakecomputing.com/";
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PASSWORD auth
    // ─────────────────────────────────────────────────────────────────────────

    private Properties buildPasswordProperties(ExtractionRequest request) {
        String password = resolvePassword(request);

        if (!isSet(password)) {
            throw new DatabaseConnectionException(
                    "password is required for PASSWORD auth — supply inline or via vaultSecretPath");
        }

        Properties props = buildCommonProperties(request);
        props.put("password", password);
        return props;
    }

    /**
     * Resolve the Snowflake password.
     * Vault takes priority over the inline {@code password} field.
     */
    private String resolvePassword(ExtractionRequest request) {
        if (request.usesVault()) {
            SnowflakeVaultSecret secret = vaultSecretService.readSecret(request.getVaultSecretPath());
            if (!secret.hasPassword()) {
                throw new DatabaseConnectionException(
                        "Vault secret at [" + request.getVaultSecretPath()
                        + "] does not contain a 'password' field");
            }
            log.debug("Password resolved from Vault path: {}", request.getVaultSecretPath());
            return secret.password();
        }
        return request.getPassword();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // KEY_PAIR auth
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Build connection properties for RSA key pair authentication.
     *
     * <p>The Snowflake JDBC driver accepts a {@link PrivateKey} object directly via
     * the {@code privateKey} property and sets {@code authenticator=snowflake_jwt}
     * internally when it is present.
     */
    private Properties buildKeyPairProperties(ExtractionRequest request) {
        PrivateKey privateKey = resolvePrivateKey(request);
        log.debug("Private key loaded for KEY_PAIR auth (algorithm={})", privateKey.getAlgorithm());

        Properties props = buildCommonProperties(request);
        props.put("privateKey", privateKey);
        props.put("authenticator", "snowflake_jwt");
        return props;
    }

    /**
     * Resolve the RSA private key.
     *
     * <p><b>Resolution order:</b>
     * <ol>
     *   <li>Vault — when {@code vaultSecretPath} is set; reads {@code privateKey}
     *       and {@code privateKeyPassphrase} fields from the Vault secret.</li>
     *   <li>Inline — delegates to {@link SnowflakeKeyPairLoader} with the
     *       request's inline PEM / path / Base64 fields.</li>
     * </ol>
     */
    private PrivateKey resolvePrivateKey(ExtractionRequest request) {
        if (request.usesVault()) {
            return resolvePrivateKeyFromVault(request);
        }
        return SnowflakeKeyPairLoader.load(request);
    }

    /**
     * Fetch the private key from Vault and delegate decoding to
     * {@link SnowflakeKeyPairLoader}.
     *
     * <p>A synthetic {@link ExtractionRequest} is not used — instead, the PEM
     * string from Vault is injected back onto the original request's
     * {@code privateKeyPem} field so {@link SnowflakeKeyPairLoader#load} can
     * handle both unencrypted and encrypted key variants transparently.
     */
    private PrivateKey resolvePrivateKeyFromVault(ExtractionRequest request) {
        SnowflakeVaultSecret secret = vaultSecretService.readSecret(request.getVaultSecretPath());

        if (!secret.hasPrivateKey()) {
            throw new DatabaseConnectionException(
                    "Vault secret at [" + request.getVaultSecretPath()
                    + "] does not contain a 'privateKey' field");
        }

        log.debug("Private key resolved from Vault path: {}", request.getVaultSecretPath());

        // Build a transient, non-mutating copy to avoid side effects on the caller's request
        ExtractionRequest vaultBacked = buildVaultBackedRequest(request, secret);
        return SnowflakeKeyPairLoader.load(vaultBacked);
    }

    /**
     * Create a lightweight copy of {@code request} with key material overlaid from Vault.
     * Only the key-resolution fields are populated; all other fields are copied by reference.
     */
    private ExtractionRequest buildVaultBackedRequest(ExtractionRequest request,
                                                       SnowflakeVaultSecret secret) {
        ExtractionRequest copy = new ExtractionRequest();
        // Copy identity fields
        copy.setDatabaseType(request.getDatabaseType());
        copy.setAuthMode(request.getAuthMode());
        copy.setUsername(request.getUsername());
        copy.setSchemaName(request.getSchemaName());

        // Overlay key material from Vault
        copy.setPrivateKeyPem(secret.privateKey());
        copy.setPrivateKeyPassphrase(secret.privateKeyPassphrase());

        // Explicitly clear Vault path so SnowflakeKeyPairLoader uses the inline PEM
        copy.setVaultSecretPath(null);

        return copy;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Shared JDBC properties (both auth modes)
    // ─────────────────────────────────────────────────────────────────────────

    private Properties buildCommonProperties(ExtractionRequest request) {
        Properties props = new Properties();
        props.put("user", request.getUsername());

        if (isSet(request.getDatabaseName()))  props.put("db",        request.getDatabaseName());
        if (isSet(request.getSchemaName()))    props.put("schema",    request.getSchemaName());
        if (isSet(request.getWarehouse()))     props.put("warehouse", request.getWarehouse());
        if (isSet(request.getRole()))          props.put("role",      request.getRole());

        props.put("loginTimeout",   "30");
        props.put("networkTimeout", "60");
        props.put("application",    "RdfMetadataExtractor");
        return props;
    }

    private boolean isSet(String value) {
        return value != null && !value.isBlank();
    }
}
