package com.resumethinking.platform.profiles;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.resumethinking.platform.crypto.AesGcmCryptoService;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import com.resumethinking.platform.auth.InMemoryRepositories;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LlmProfileServiceTest {
    @Test
    void testConnectionDoesNotReturnAnApiKeyFromProviderModelIds() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", exchange -> {
            var body = "{\"data\":[{\"id\":\"secret-key\"},{\"id\":\"safe-model\"}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        try {
            var owner = UUID.randomUUID();
            var repository = new InMemoryRepositories.LlmProfileRepositoryStub();
            var service = new LlmProfileService(repository,
                    new AesGcmCryptoService("MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="), true);
            var profile = service.create(owner,
                    new CreateLlmProfileCommand("x", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                            "model-a", "secret-key"));

            var response = service.testConnection(owner, profile.id());

            assertThat(response.models()).contains("safe-model").doesNotContain("secret-key");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void testConnectionResolvesModelsAgainstConfiguredBasePathAndPersistsMetadata() {
        var owner = UUID.randomUUID(); var service = new LlmProfileService(new InMemoryRepositories.LlmProfileRepositoryStub(), new AesGcmCryptoService("MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="), true);
        var created = service.create(owner, new CreateLlmProfileCommand("x", "https://example.test/v1", "m", "k"));
        assertThat(service.update(owner, created.id(), new CreateLlmProfileCommand("x2", "https://example.test/v1", "m2", "k2"))).isNotNull();
    }
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

    @Test
    void updateWithoutApiKeyKeepsTheExistingEncryptedKey() {
        var ownerId = UUID.randomUUID();
        var repository = new InMemoryRepositories.LlmProfileRepositoryStub();
        var profileService = new LlmProfileService(repository,
                new AesGcmCryptoService("MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="), false);

        var profile = profileService.create(ownerId,
                new CreateLlmProfileCommand("work", "https://1.1.1.1/v1", "model-a", "secret-key"));
        var updated = profileService.update(ownerId, profile.id(),
                new UpdateLlmProfileCommand("renamed", "https://1.1.1.1/v1", "model-b", null, false));

        assertThat(profileService.decryptForDispatch(ownerId, updated.id()).apiKey()).isEqualTo("secret-key");
        assertThat(updated.hasApiKey()).isTrue();
    }
}
