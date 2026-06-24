package com.rdf.metadata;

import com.rdf.metadata.connector.DatabaseConnectionException;
import com.rdf.metadata.vault.SnowflakeVaultSecret;
import com.rdf.metadata.vault.VaultProperties;
import com.rdf.metadata.vault.VaultSecretService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.vault.core.VaultTemplate;
import org.springframework.vault.support.VaultResponseSupport;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link VaultSecretService} — covers path resolution,
 * KV v1/v2 data unwrapping, field extraction, and error handling.
 * Uses Mockito to mock {@link VaultTemplate}; no live Vault required.
 */
class VaultSecretServiceTest {

    private VaultTemplate      mockVaultTemplate;
    private VaultProperties    vaultProperties;
    private VaultSecretService vaultSecretService;

    @BeforeEach
    void setUp() {
        mockVaultTemplate  = Mockito.mock(VaultTemplate.class);
        vaultProperties    = new VaultProperties();
        // Defaults: kvEngine=secret, kvVersion=2, secretPathPrefix=""
        vaultSecretService = new VaultSecretService(mockVaultTemplate, vaultProperties);
    }

    // ─── Path resolution ─────────────────────────────────────────────────────

    @Test
    @DisplayName("KV v2 path: engine/data/prefix/path")
    void shouldBuildKvV2PathWithPrefix() {
        vaultProperties.setKvVersion(2);
        vaultProperties.setKvEngine("secret");
        vaultProperties.setSecretPathPrefix("rdf-extractor");

        String resolved = vaultSecretService.resolvePath("prod/snowflake");
        assertThat(resolved).isEqualTo("secret/data/rdf-extractor/prod/snowflake");
    }

    @Test
    @DisplayName("KV v1 path: engine/prefix/path")
    void shouldBuildKvV1PathWithPrefix() {
        vaultProperties.setKvVersion(1);
        vaultProperties.setKvEngine("kv");
        vaultProperties.setSecretPathPrefix("myapp");

        String resolved = vaultSecretService.resolvePath("snowflake/prod");
        assertThat(resolved).isEqualTo("kv/myapp/snowflake/prod");
    }

    @Test
    @DisplayName("Path with no prefix uses raw path directly")
    void shouldBuildPathWithNoPrefix() {
        vaultProperties.setKvVersion(2);
        vaultProperties.setKvEngine("secret");
        vaultProperties.setSecretPathPrefix("");

        String resolved = vaultSecretService.resolvePath("snowflake/prod");
        assertThat(resolved).isEqualTo("secret/data/snowflake/prod");
    }

    @Test
    @DisplayName("Leading slash in rawSecretPath is stripped")
    void shouldStripLeadingSlashFromPath() {
        vaultProperties.setKvVersion(2);
        vaultProperties.setSecretPathPrefix("app");

        String resolved = vaultSecretService.resolvePath("/prod/snowflake");
        assertThat(resolved).isEqualTo("secret/data/app/prod/snowflake");
    }


    @Test
    @DisplayName("Trailing slash on kvEngine is stripped")
    void shouldStripTrailingSlashFromEngine() {
        vaultProperties.setKvVersion(2);
        vaultProperties.setKvEngine("secret/");   // trailing slash
        vaultProperties.setSecretPathPrefix("");

        String resolved = vaultSecretService.resolvePath("prod/snowflake");
        assertThat(resolved).isEqualTo("secret/data/prod/snowflake");
    }

    @Test
    @DisplayName("Multiple slashes on prefix are stripped to single segments")
    void shouldStripMultipleSlashesFromPrefix() {
        vaultProperties.setKvVersion(2);
        vaultProperties.setKvEngine("secret");
        vaultProperties.setSecretPathPrefix("//rdf-extractor//");  // extra slashes

        String resolved = vaultSecretService.resolvePath("prod/snowflake");
        assertThat(resolved).isEqualTo("secret/data/rdf-extractor/prod/snowflake");
    }

    @Test
    @DisplayName("stripSlashes utility: removes leading and trailing slashes only")
    void stripSlashesUtility() {
        assertThat(VaultSecretService.stripSlashes("//secret//")).isEqualTo("secret");
        assertThat(VaultSecretService.stripSlashes("secret"))    .isEqualTo("secret");
        assertThat(VaultSecretService.stripSlashes("/"))         .isEqualTo("");
        assertThat(VaultSecretService.stripSlashes(""))          .isEqualTo("");
        assertThat(VaultSecretService.stripSlashes(null))        .isEqualTo("");
        // Internal slashes must NOT be removed
        assertThat(VaultSecretService.stripSlashes("/a/b/c/"))   .isEqualTo("a/b/c");
    }

    // ─── Successful secret reads ──────────────────────────────────────────────

    @Test
    @DisplayName("Reads key pair secret from KV v2 nested data map")
    void shouldReadKeyPairSecretFromKvV2() {
        // KV v2 wraps actual fields under a "data" key
        Map<String, Object> innerData = Map.of(
                "privateKey", "-----BEGIN PRIVATE KEY-----\nMIIE...\n-----END PRIVATE KEY-----",
                "privateKeyPassphrase", "my_passphrase"
        );
        Map<String, Object> outerData = new HashMap<>();
        outerData.put("data", innerData);

        stubVaultRead("secret/data/rdf-extractor/prod/snowflake", outerData);

        vaultProperties.setSecretPathPrefix("rdf-extractor");
        SnowflakeVaultSecret secret = vaultSecretService.readSecret("prod/snowflake");

        assertThat(secret.hasPrivateKey()).isTrue();
        assertThat(secret.privateKey()).startsWith("-----BEGIN PRIVATE KEY-----");
        assertThat(secret.privateKeyPassphrase()).isEqualTo("my_passphrase");
        assertThat(secret.hasPassword()).isFalse();
    }

    @Test
    @DisplayName("Reads password secret from KV v2 nested data map")
    void shouldReadPasswordSecretFromKvV2() {
        Map<String, Object> innerData = Map.of("password", "super_secret_pw");
        Map<String, Object> outerData = new HashMap<>();
        outerData.put("data", innerData);

        stubVaultRead("secret/data/rdf-extractor/dev/snowflake", outerData);

        vaultProperties.setSecretPathPrefix("rdf-extractor");
        SnowflakeVaultSecret secret = vaultSecretService.readSecret("dev/snowflake");

        assertThat(secret.hasPassword()).isTrue();
        assertThat(secret.password()).isEqualTo("super_secret_pw");
        assertThat(secret.hasPrivateKey()).isFalse();
    }

    @Test
    @DisplayName("Reads secret from KV v1 (no nested data wrapper)")
    void shouldReadSecretFromKvV1() {
        vaultProperties.setKvVersion(1);
        vaultProperties.setKvEngine("kv");
        vaultProperties.setSecretPathPrefix("app");

        Map<String, Object> flatData = Map.of(
                "privateKey", "BASE64DERKEY==",
                "privateKeyPassphrase", ""   // blank passphrase — should be null in result
        );

        stubVaultRead("kv/app/snowflake/prod", flatData);

        SnowflakeVaultSecret secret = vaultSecretService.readSecret("snowflake/prod");

        assertThat(secret.hasPrivateKey()).isTrue();
        assertThat(secret.privateKey()).isEqualTo("BASE64DERKEY==");
        // Blank passphrase should be normalised to null
        assertThat(secret.privateKeyPassphrase()).isNull();
    }

    // ─── Error handling ───────────────────────────────────────────────────────

    @Test
    @DisplayName("Throws when Vault returns null response")
    void shouldThrowWhenSecretNotFound() {
        when(mockVaultTemplate.read(anyString(), eq(Map.class))).thenReturn(null);

        assertThatThrownBy(() -> vaultSecretService.readSecret("missing/path"))
                .isInstanceOf(DatabaseConnectionException.class)
                .hasMessageContaining("No secret found at Vault path");
    }

    @Test
    @DisplayName("Throws when VaultTemplate raises an exception")
    void shouldThrowWhenVaultIsUnreachable() {
        when(mockVaultTemplate.read(anyString(), eq(Map.class)))
                .thenThrow(new RuntimeException("connection refused"));

        assertThatThrownBy(() -> vaultSecretService.readSecret("prod/snowflake"))
                .isInstanceOf(DatabaseConnectionException.class)
                .hasMessageContaining("Vault read failed")
                .hasMessageContaining("connection refused");
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void stubVaultRead(String path, Map<String, Object> data) {
        VaultResponseSupport<Map> response = mock(VaultResponseSupport.class);
        when(response.getData()).thenReturn(data);
        when(mockVaultTemplate.read(eq(path), eq(Map.class))).thenReturn(response);
    }
}
