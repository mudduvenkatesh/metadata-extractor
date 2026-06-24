package com.rdf.metadata.connector;

import com.rdf.metadata.model.ExtractionRequest;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

import javax.crypto.EncryptedPrivateKeyInfo;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Loads and decrypts RSA PKCS#8 private keys for Snowflake key pair authentication.
 *
 * <p>Accepts three input forms (checked in this order):
 * <ol>
 *   <li><b>Inline PEM string</b> ({@code privateKeyPem}) — the full PEM block or bare Base64 body</li>
 *   <li><b>File path</b> ({@code privateKeyPath}) — path to a {@code .p8} / {@code .pem} file</li>
 *   <li><b>Base64 DER bytes</b> ({@code privateKeyBase64}) — raw DER bytes encoded as Base64</li>
 * </ol>
 *
 * <p>Both unencrypted ({@code BEGIN PRIVATE KEY}) and encrypted
 * ({@code BEGIN ENCRYPTED PRIVATE KEY}) PKCS#8 keys are supported.
 * Supply {@code privateKeyPassphrase} for encrypted keys.
 *
 * <h3>Generating a compatible key pair</h3>
 * <pre>{@code
 * # Unencrypted
 * openssl genrsa 2048 | openssl pkcs8 -topk8 -inform PEM -out rsa_key.p8 -nocrypt
 *
 * # Encrypted (AES-256-CBC)
 * openssl genrsa 2048 | openssl pkcs8 -topk8 -inform PEM -out rsa_key.p8 -v2 aes-256-cbc
 *
 * # Extract public key and register with Snowflake:
 * openssl rsa -in rsa_key.p8 -pubout -out rsa_key.pub
 * -- In Snowflake:
 * ALTER USER my_user SET RSA_PUBLIC_KEY='<contents of rsa_key.pub without headers>';
 * }</pre>
 */
@Slf4j
public final class SnowflakeKeyPairLoader {

    private static final String PEM_UNENCRYPTED_HEADER = "-----BEGIN PRIVATE KEY-----";
    private static final String PEM_ENCRYPTED_HEADER   = "-----BEGIN ENCRYPTED PRIVATE KEY-----";
    private static final String RSA_ALGORITHM          = "RSA";
    private static final String PBE_ALGORITHM          = "PBKDF2WithHmacSHA256AndAES-256-CBC";

    private SnowflakeKeyPairLoader() {}

    /**
     * Load a {@link PrivateKey} from the fields in the given {@link ExtractionRequest}.
     *
     * @param request the request containing key material and optional passphrase
     * @return a decoded, ready-to-use {@link PrivateKey}
     * @throws DatabaseConnectionException if no key material is found or decoding fails
     */
    public static PrivateKey load(ExtractionRequest request) {
        byte[] derBytes = resolveDerBytes(request);
        boolean isEncrypted = isEncryptedPem(request);
        return decode(derBytes, isEncrypted, request.getPrivateKeyPassphrase());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Key material resolution (PEM → DER bytes)
    // ─────────────────────────────────────────────────────────────────────────

    private static byte[] resolveDerBytes(ExtractionRequest request) {
        // 1. Inline PEM string
        if (isSet(request.getPrivateKeyPem())) {
            log.debug("Loading Snowflake private key from inline PEM string");
            return pemToDer(request.getPrivateKeyPem());
        }

        // 2. File path
        if (isSet(request.getPrivateKeyPath())) {
            log.debug("Loading Snowflake private key from file: {}", request.getPrivateKeyPath());
            return readPemFile(request.getPrivateKeyPath());
        }

        // 3. Base64-encoded DER
        if (isSet(request.getPrivateKeyBase64())) {
            log.debug("Loading Snowflake private key from Base64 DER bytes");
            try {
                return Base64.getDecoder().decode(request.getPrivateKeyBase64().trim());
            } catch (IllegalArgumentException e) {
                throw new DatabaseConnectionException(
                        "privateKeyBase64 is not valid Base64: " + e.getMessage(), e);
            }
        }

        throw new DatabaseConnectionException(
                "KEY_PAIR auth mode requires one of: privateKeyPem, privateKeyPath, or privateKeyBase64");
    }

    private static byte[] readPemFile(String filePath) {
        try {
            String pem = Files.readString(Path.of(filePath), StandardCharsets.UTF_8);
            return pemToDer(pem);
        } catch (IOException e) {
            throw new DatabaseConnectionException(
                    "Cannot read private key file [" + filePath + "]: " + e.getMessage(), e);
        }
    }

    /**
     * Strip PEM armour headers/footers and decode the Base64 body to raw DER bytes.
     * Accepts both {@code BEGIN PRIVATE KEY} and {@code BEGIN ENCRYPTED PRIVATE KEY} blocks.
     */
    public static byte[] pemToDer(String pem) {
        String stripped = pem.trim()
                // Remove both unencrypted and encrypted PEM headers/footers
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("-----BEGIN ENCRYPTED PRIVATE KEY-----", "")
                .replace("-----END ENCRYPTED PRIVATE KEY-----", "")
                // Strip RSA variant as well in case user provides PKCS#1 mistakenly
                .replace("-----BEGIN RSA PRIVATE KEY-----", "")
                .replace("-----END RSA PRIVATE KEY-----", "")
                // Remove all whitespace (newlines, spaces)
                .replaceAll("\\s+", "");

        try {
            return Base64.getDecoder().decode(stripped);
        } catch (IllegalArgumentException e) {
            throw new DatabaseConnectionException(
                    "Private key PEM body is not valid Base64: " + e.getMessage(), e);
        }
    }

    /**
     * Determine from the raw PEM string whether it represents an encrypted key.
     * Defaults to {@code false} when the key source is Base64 DER (can't tell without parsing).
     */
    private static boolean isEncryptedPem(ExtractionRequest request) {
        String pem = isSet(request.getPrivateKeyPem()) ? request.getPrivateKeyPem() : null;

        if (pem == null && isSet(request.getPrivateKeyPath())) {
            try {
                pem = Files.readString(Path.of(request.getPrivateKeyPath()), StandardCharsets.UTF_8);
            } catch (IOException e) {
                // Will fail properly in resolveDerBytes; just return false here
                return false;
            }
        }

        if (pem != null) {
            return pem.contains(PEM_ENCRYPTED_HEADER);
        }

        // For Base64 DER input, rely on passphrase presence as a hint
        return isSet(request.getPrivateKeyPassphrase());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PKCS#8 decoding (unencrypted and encrypted)
    // ─────────────────────────────────────────────────────────────────────────

    private static PrivateKey decode(byte[] derBytes, boolean encrypted, String passphrase) {
        try {
            if (encrypted) {
                return decodeEncrypted(derBytes, passphrase);
            }
            return decodeUnencrypted(derBytes);
        } catch (DatabaseConnectionException e) {
            throw e;
        } catch (Exception e) {
            throw new DatabaseConnectionException(
                    "Failed to decode private key: " + e.getMessage(), e);
        }
    }

    /**
     * Decode an unencrypted PKCS#8 DER-encoded private key.
     */
    private static PrivateKey decodeUnencrypted(byte[] derBytes) {
        try {
            PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(derBytes);
            return KeyFactory.getInstance(RSA_ALGORITHM).generatePrivate(keySpec);
        } catch (InvalidKeySpecException e) {
            throw new DatabaseConnectionException(
                    "Invalid PKCS#8 key spec — ensure the key is unencrypted PKCS#8 RSA format. "
                    + "Use: openssl pkcs8 -topk8 -nocrypt -in key.pem -out key.p8  "
                    + "Details: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new DatabaseConnectionException(
                    "Failed to load unencrypted private key: " + e.getMessage(), e);
        }
    }

    /**
     * Decode an encrypted PKCS#8 DER-encoded private key using the provided passphrase.
     *
     * <p>Uses the JCE {@link EncryptedPrivateKeyInfo} + {@link SecretKeyFactory} approach
     * which is compatible with keys generated by OpenSSL's {@code -v2 aes-256-cbc} option.
     */
    private static PrivateKey decodeEncrypted(byte[] derBytes, String passphrase) {
        if (!isSet(passphrase)) {
            throw new DatabaseConnectionException(
                    "Private key is encrypted but no privateKeyPassphrase was provided");
        }

        try {
            EncryptedPrivateKeyInfo encryptedKeyInfo = new EncryptedPrivateKeyInfo(derBytes);
            String algorithm = encryptedKeyInfo.getAlgName();
            log.debug("Decrypting private key with algorithm: {}", algorithm);

            PBEKeySpec pbeKeySpec = new PBEKeySpec(passphrase.toCharArray());
            SecretKeyFactory secretKeyFactory = SecretKeyFactory.getInstance(algorithm);
            PKCS8EncodedKeySpec keySpec = encryptedKeyInfo.getKeySpec(
                    secretKeyFactory.generateSecret(pbeKeySpec));

            return KeyFactory.getInstance(RSA_ALGORITHM).generatePrivate(keySpec);
        } catch (DatabaseConnectionException e) {
            throw e;
        } catch (Exception e) {
            throw new DatabaseConnectionException(
                    "Failed to decrypt private key — check passphrase and key format. "
                    + "Details: " + e.getMessage(), e);
        }
    }

    private static boolean isSet(String v) {
        return v != null && !v.isBlank();
    }
}
