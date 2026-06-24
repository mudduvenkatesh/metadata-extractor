package com.rdf.metadata.vault;

import com.rdf.metadata.connector.DatabaseConnectionException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.vault.core.VaultTemplate;
import org.springframework.vault.support.VaultResponseSupport;

import java.util.Map;

/**
 * Resolves Snowflake credentials from HashiCorp Vault using Spring Vault's
 * {@link VaultTemplate}.
 *
 * <h3>Vault KV secret structure</h3>
 *
 * <p>Secrets are read from the path:
 * <pre>
 *   KV v2:  &lt;kvEngine&gt;/data/&lt;prefix&gt;/&lt;vaultSecretPath&gt;
 *   KV v1:  &lt;kvEngine&gt;/&lt;prefix&gt;/&lt;vaultSecretPath&gt;
 * </pre>
 *
 * <p>Expected fields inside the secret (all optional):
 * <pre>
 *   privateKey             — PKCS#8 PEM string or Base64 DER for key pair auth
 *   privateKeyPassphrase   — passphrase for encrypted keys
 *   password               — Snowflake password for password auth
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VaultSecretService {

    private final VaultTemplate   vaultTemplate;
    private final VaultProperties vaultProperties;

    /**
     * Read Snowflake credentials from Vault at the given secret path.
     *
     * @param rawSecretPath the {@code vaultSecretPath} from the request (relative path)
     * @return resolved {@link SnowflakeVaultSecret}
     * @throws DatabaseConnectionException if the secret is not found or Vault is unreachable
     */
    public SnowflakeVaultSecret readSecret(String rawSecretPath) {
        String fullPath = resolvePath(rawSecretPath);
        log.info("Reading Snowflake credentials from Vault path: {}", fullPath);

        Map<String, Object> data = fetchSecretData(fullPath);

        String privateKey           = stringValue(data, VaultSecretFields.PRIVATE_KEY);
        String privateKeyPassphrase = stringValue(data, VaultSecretFields.PRIVATE_KEY_PASSPHRASE);
        String password             = stringValue(data, VaultSecretFields.PASSWORD);

        log.debug("Vault secret resolved: hasPrivateKey={}, hasPassphrase={}, hasPassword={}",
                privateKey != null, privateKeyPassphrase != null, password != null);

        return new SnowflakeVaultSecret(privateKey, privateKeyPassphrase, password);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Path construction
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Build the full Vault path that {@link VaultTemplate#read} will use.
     *
     * <ul>
     *   <li>KV v2: {@code <kvEngine>/data/<prefix>/<path>}</li>
     *   <li>KV v1: {@code <kvEngine>/<prefix>/<path>}</li>
     * </ul>
     */
    public String resolvePath(String rawSecretPath) {
        // Remove all leading/trailing '/' from each segment before joining
        String engine  = stripSlashes(vaultProperties.getKvEngine());
        String prefix  = vaultProperties.getSecretPathPrefix();
        String relPath = rawSecretPath == null ? "" : rawSecretPath.strip();

        // Normalise prefix: empty string when blank, otherwise strip slashes and add trailing /
        String normPrefix = (prefix == null || prefix.isBlank())
                ? ""
                : stripSlashes(prefix) + "/";

        // Strip any leading slash from the raw secret path segment
        if (relPath.startsWith("/")) {
            relPath = relPath.substring(1);
        }

        if (vaultProperties.getKvVersion() == 2) {
            return engine + "/data/" + normPrefix + relPath;
        }
        // KV v1
        return engine + "/" + normPrefix + relPath;
    }

    /**
     * Remove all leading and trailing {@code /} characters from a path segment.
     * e.g. {@code "//secret/"} → {@code "secret"}
     * <p>
     * Uses index scanning rather than regex to avoid allocations on the hot path.
     */
    public static String stripSlashes(String value) {
        if (value == null || value.isEmpty()) return "";
        int start = 0;
        int end   = value.length();
        while (start < end && value.charAt(start) == '/') start++;
        while (end > start && value.charAt(end - 1) == '/') end--;
        return value.substring(start, end);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Vault read
    // ─────────────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Map<String, Object> fetchSecretData(String fullPath) {
        VaultResponseSupport<Map> response;
        try {
            response = vaultTemplate.read(fullPath, Map.class);
        } catch (Exception e) {
            throw new DatabaseConnectionException(
                    "Vault read failed at path [" + fullPath + "]: " + e.getMessage(), e);
        }

        if (response == null || response.getData() == null) {
            throw new DatabaseConnectionException(
                    "No secret found at Vault path [" + fullPath + "]. "
                    + "Verify the path exists and the token has read permission.");
        }

        Map<String, Object> raw = response.getData();

        // KV v2 wraps the actual data under a nested "data" key
        if (vaultProperties.getKvVersion() == 2 && raw.containsKey("data")) {
            Object nested = raw.get("data");
            if (nested instanceof Map<?, ?> nestedMap) {
                return (Map<String, Object>) nestedMap;
            }
        }

        return raw;
    }

    private String stringValue(Map<String, Object> data, String key) {
        Object value = data.get(key);
        if (value == null) return null;
        String str = value.toString().strip();
        return str.isBlank() ? null : str;
    }
}
