package com.rdf.metadata;

import com.rdf.metadata.vault.VaultProperties;
import com.rdf.metadata.vault.VaultSecretService;
import org.mockito.Mockito;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.vault.core.VaultTemplate;

/**
 * Test configuration that replaces the real {@link VaultTemplate} and
 * {@link VaultSecretService} beans with Mockito mocks so tests can run
 * without a live Vault server.
 *
 * <p>Annotate test classes with
 * {@code @Import(TestVaultConfig.class)} to activate these overrides,
 * or rely on the integration test base class which already includes it.
 */
@TestConfiguration
public class TestVaultConfig {

    @Bean
    @Primary
    public VaultTemplate vaultTemplate() {
        return Mockito.mock(VaultTemplate.class);
    }

    @Bean
    @Primary
    public VaultSecretService vaultSecretService(VaultTemplate vaultTemplate,
                                                  VaultProperties vaultProperties) {
        return Mockito.mock(VaultSecretService.class);
    }
}
