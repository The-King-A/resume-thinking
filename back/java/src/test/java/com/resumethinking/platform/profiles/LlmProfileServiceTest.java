package com.resumethinking.platform.profiles;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.resumethinking.platform.crypto.AesGcmCryptoService;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import com.resumethinking.platform.auth.InMemoryRepositories;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LlmProfileServiceTest {
    @Test
    void profileReadNeverReturnsApiKeyAndCrossOwnerDecryptFails() throws Exception {
        var ownerId = UUID.randomUUID();
        var otherUserId = UUID.randomUUID();
        var repository = new InMemoryRepositories.LlmProfileRepositoryStub();
        var profileService = new LlmProfileService(repository, new AesGcmCryptoService("MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="), false);

        var profile = profileService.create(ownerId, new CreateLlmProfileCommand("work", "https://api.example/v1", "model-a", "secret-key"));

        assertThat(new ObjectMapper().findAndRegisterModules().writeValueAsString(profile)).doesNotContain("secret-key");
        assertThatThrownBy(() -> profileService.decryptForDispatch(otherUserId, profile.id())).isInstanceOf(ResourceNotFoundException.class);
    }
}
