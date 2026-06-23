package com.rdf.metadata;

import com.rdf.metadata.connector.DatabaseConnectionException;
import com.rdf.metadata.connector.SnowflakeKeyPairLoader;
import com.rdf.metadata.model.ExtractionRequest;
import com.rdf.metadata.model.DatabaseType;
import com.rdf.metadata.model.SnowflakeAuthMode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link SnowflakeKeyPairLoader}.
 *
 * <p>Generates a real RSA 2048-bit key pair at test-class level so all tests
 * share the same key material without relying on external resources.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SnowflakeKeyPairLoaderTest {

    private KeyPair keyPair;
    private byte[]  derBytes;      // unencrypted PKCS#8 DER
    private String  pemString;     // unencrypted PEM
    private String  base64Der;     // Base64-encoded DER

    @TempDir
    Path tempDir;

    @BeforeAll
    void generateKeyPair() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        keyPair = kpg.generateKeyPair();

        // Encode as PKCS#8 DER (same format OpenSSL produces with -topk8 -nocrypt)
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(keyPair.getPrivate().getEncoded());
        KeyFactory kf = KeyFactory.getInstance("RSA");
        derBytes  = kf.generatePrivate(spec).getEncoded();
        base64Der = Base64.getEncoder().encodeToString(derBytes);

        // Build PEM string
        String b64Body = Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(derBytes);
        pemString = "-----BEGIN PRIVATE KEY-----\n" + b64Body + "\n-----END PRIVATE KEY-----";
    }

    // ─── pemToDer ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("pemToDer strips headers and decodes Base64 body")
    void pemToDerStripsHeadersAndDecodes() {
        byte[] result = SnowflakeKeyPairLoader.pemToDer(pemString);
        assertThat(result).isEqualTo(derBytes);
    }

    @Test
    @DisplayName("pemToDer accepts PEM without headers (bare Base64 body)")
    void pemToDerAcceptsBareBase64() {
        String bare = Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(derBytes);
        byte[] result = SnowflakeKeyPairLoader.pemToDer(bare);
        assertThat(result).isEqualTo(derBytes);
    }

    @Test
    @DisplayName("pemToDer strips ENCRYPTED PRIVATE KEY headers correctly")
    void pemToDerStripsEncryptedHeaders() {
        // Build a fake encrypted PEM block using same DER content (just header test)
        String encPem = "-----BEGIN ENCRYPTED PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(derBytes)
                + "\n-----END ENCRYPTED PRIVATE KEY-----";
        byte[] result = SnowflakeKeyPairLoader.pemToDer(encPem);
        assertThat(result).isEqualTo(derBytes);
    }

    @Test
    @DisplayName("pemToDer throws on invalid Base64")
    void pemToDerThrowsOnInvalidBase64() {
        assertThatThrownBy(() -> SnowflakeKeyPairLoader.pemToDer("NOT_VALID_BASE64!!!"))
                .isInstanceOf(DatabaseConnectionException.class)
                .hasMessageContaining("not valid Base64");
    }

    // ─── load() via privateKeyPem ─────────────────────────────────────────────

    @Test
    @DisplayName("load() from inline PEM returns correct RSA private key")
    void loadFromInlinePem() {
        ExtractionRequest request = buildKeyPairRequest();
        request.setPrivateKeyPem(pemString);

        PrivateKey loaded = SnowflakeKeyPairLoader.load(request);

        assertThat(loaded.getAlgorithm()).isEqualTo("RSA");
        assertThat(loaded.getEncoded()).isEqualTo(keyPair.getPrivate().getEncoded());
    }

    @Test
    @DisplayName("load() from inline PEM without headers works")
    void loadFromInlinePemNoHeaders() {
        ExtractionRequest request = buildKeyPairRequest();
        // Strip headers manually
        request.setPrivateKeyPem(pemString
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "").trim());

        PrivateKey loaded = SnowflakeKeyPairLoader.load(request);
        assertThat(loaded.getEncoded()).isEqualTo(keyPair.getPrivate().getEncoded());
    }

    // ─── load() via privateKeyPath ────────────────────────────────────────────

    @Test
    @DisplayName("load() from file path reads PEM file correctly")
    void loadFromFilePath() throws IOException {
        Path keyFile = tempDir.resolve("test_key.p8");
        Files.writeString(keyFile, pemString, StandardCharsets.UTF_8);

        ExtractionRequest request = buildKeyPairRequest();
        request.setPrivateKeyPath(keyFile.toString());

        PrivateKey loaded = SnowflakeKeyPairLoader.load(request);
        assertThat(loaded.getEncoded()).isEqualTo(keyPair.getPrivate().getEncoded());
    }

    @Test
    @DisplayName("load() throws when file path does not exist")
    void loadFromFilePathThrowsWhenMissing() {
        ExtractionRequest request = buildKeyPairRequest();
        request.setPrivateKeyPath("/nonexistent/path/key.p8");

        assertThatThrownBy(() -> SnowflakeKeyPairLoader.load(request))
                .isInstanceOf(DatabaseConnectionException.class)
                .hasMessageContaining("Cannot read private key file");
    }

    // ─── load() via privateKeyBase64 ─────────────────────────────────────────

    @Test
    @DisplayName("load() from Base64 DER bytes returns correct key")
    void loadFromBase64Der() {
        ExtractionRequest request = buildKeyPairRequest();
        request.setPrivateKeyBase64(base64Der);

        PrivateKey loaded = SnowflakeKeyPairLoader.load(request);
        assertThat(loaded.getEncoded()).isEqualTo(keyPair.getPrivate().getEncoded());
    }

    @Test
    @DisplayName("load() throws on invalid Base64 DER input")
    void loadFromBase64DerThrowsOnInvalidInput() {
        ExtractionRequest request = buildKeyPairRequest();
        request.setPrivateKeyBase64("INVALID_BASE64!!!");

        assertThatThrownBy(() -> SnowflakeKeyPairLoader.load(request))
                .isInstanceOf(DatabaseConnectionException.class)
                .hasMessageContaining("not valid Base64");
    }

    // ─── Priority order ───────────────────────────────────────────────────────

    @Test
    @DisplayName("privateKeyPem takes priority over privateKeyPath and privateKeyBase64")
    void pemTakesPriorityOverPathAndBase64() throws IOException {
        Path keyFile = tempDir.resolve("other_key.p8");
        // Write a file that would fail if read (empty = bad DER)
        Files.writeString(keyFile, "GARBAGE", StandardCharsets.UTF_8);

        ExtractionRequest request = buildKeyPairRequest();
        request.setPrivateKeyPem(pemString);           // valid
        request.setPrivateKeyPath(keyFile.toString()); // would fail
        request.setPrivateKeyBase64("GARBAGE");        // would fail

        // Should succeed because PEM is checked first
        PrivateKey loaded = SnowflakeKeyPairLoader.load(request);
        assertThat(loaded).isNotNull();
    }

    // ─── Missing key material ─────────────────────────────────────────────────

    @Test
    @DisplayName("load() throws when no key material is provided")
    void loadThrowsWhenNoKeyMaterial() {
        ExtractionRequest request = buildKeyPairRequest();
        // All key fields are null

        assertThatThrownBy(() -> SnowflakeKeyPairLoader.load(request))
                .isInstanceOf(DatabaseConnectionException.class)
                .hasMessageContaining("KEY_PAIR auth mode requires one of");
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private ExtractionRequest buildKeyPairRequest() {
        ExtractionRequest req = new ExtractionRequest();
        req.setDatabaseType(DatabaseType.SNOWFLAKE);
        req.setAuthMode(SnowflakeAuthMode.KEY_PAIR);
        req.setUsername("test_user");
        req.setSchemaName("PUBLIC");
        return req;
    }
}
