package com.resumethinking.platform.profiles;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.resumethinking.platform.crypto.AesGcmCryptoService;
import com.resumethinking.platform.ids.ReadableIdGenerator;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import com.resumethinking.platform.auth.InMemoryRepositories;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class LlmProfileServiceTest {
    @Test
    void profileSecretsAreRedactedFromCommandAndDispatchToStringValues() {
        var secret = "profile-secret-value";

        assertThat(new CreateLlmProfileCommand("x", "https://provider.example/v1", "model", secret).toString())
                .doesNotContain(secret);
        assertThat(new DispatchLlmProfile(java.net.URI.create("https://provider.example/v1"), "model", secret).toString())
                .doesNotContain(secret);
        assertThat(new com.resumethinking.platform.matching.PythonAnalysisClient.Provider(
                java.net.URI.create("https://provider.example/v1"), "model", secret).toString())
                .doesNotContain(secret);
    }

    @Test
    void scanModelsFiltersSubmittedKeyAndLeavesRepositoryEmpty() throws Exception {
        var apiKey = "scan-secret";
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", exchange -> {
            var body = ("{\"data\":[{\"id\":\"provider-" + apiKey + "-suffix\"},{\"id\":\"safe-model\"}]}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        try {
            var repository = mock(LlmProfileRepository.class);
            var crypto = mock(AesGcmCryptoService.class);
            var ids = mock(ReadableIdGenerator.class);
            var service = new LlmProfileService(repository, crypto, true, ids);

            var response = service.scanModels(new ScanLlmProfileCommand(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", apiKey));

            assertThat(response.available()).isTrue();
            assertThat(response.models()).containsExactly("safe-model");
            assertThat(response.diagnosticCode()).isNull();
            assertThat(new ObjectMapper().findAndRegisterModules().writeValueAsString(response)).doesNotContain(apiKey);
            verifyNoInteractions(repository, crypto, ids);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void scanModelsFiltersTheTrimmedSubmittedKeyAndLeavesRepositoryEmpty() throws Exception {
        var apiKey = " scan-secret ";
        var normalizedKey = apiKey.trim();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", exchange -> {
            var body = ("{\"data\":[{\"id\":\"provider-" + normalizedKey + "-suffix\"},{\"id\":\"safe-model\"}]}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        try {
            var repository = mock(LlmProfileRepository.class);
            var crypto = mock(AesGcmCryptoService.class);
            var ids = mock(ReadableIdGenerator.class);
            var service = new LlmProfileService(repository, crypto, true, ids);

            var response = service.scanModels(new ScanLlmProfileCommand(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", apiKey));

            assertThat(response.available()).isTrue();
            assertThat(response.models()).containsExactly("safe-model");
            assertThat(new ObjectMapper().findAndRegisterModules().writeValueAsString(response)).doesNotContain(normalizedKey);
            verifyNoInteractions(repository, crypto, ids);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void scanModelsKeepsRepositoryEmptyWhenProviderIsUnavailable() throws Exception {
        var apiKey = "scan-secret";
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", exchange -> {
            var body = ("provider failure body containing " + apiKey).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(503, body.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        try {
            var repository = new RecordingLlmProfileRepository();
            var service = new LlmProfileService(repository,
                    new AesGcmCryptoService("MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="), true);

            var response = service.scanModels(new ScanLlmProfileCommand(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", apiKey));

            assertThat(response.available()).isFalse();
            assertThat(response.models()).isEmpty();
            assertThat(response.diagnostic()).contains("503").doesNotContain(apiKey).doesNotContain("provider failure body");
            assertThat(response.diagnosticCode()).isEqualTo(LlmProfileDiagnosticCode.PROVIDER_UNAVAILABLE);
            assertThat(repository.rows()).isEmpty();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void scanModelsMapsProviderUnauthorizedToInvalidApiKeyWithoutPersistence() throws Exception {
        var apiKey = "scan-secret";
        var providerBody = "unauthorized provider body " + apiKey;
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", exchange -> {
            var body = providerBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(401, body.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        try {
            var repository = new RecordingLlmProfileRepository();
            var service = new LlmProfileService(repository,
                    new AesGcmCryptoService("MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="), true);

            var response = service.scanModels(new ScanLlmProfileCommand(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", apiKey));

            assertThat(response.available()).isFalse();
            assertThat(response.diagnosticCode()).isEqualTo(LlmProfileDiagnosticCode.INVALID_API_KEY);
            assertThat(new ObjectMapper().findAndRegisterModules().writeValueAsString(response))
                    .doesNotContain(apiKey).doesNotContain(providerBody);
            assertThat(repository.rows()).isEmpty();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void scanModelsTreatsMalformedProviderBodyAsUnavailableWithoutPersistence() throws Exception {
        var apiKey = "scan-secret";
        var providerBody = "not-json malformed-body-sentinel " + apiKey;
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", exchange -> {
            var body = providerBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        try {
            var repository = new RecordingLlmProfileRepository();
            var service = new LlmProfileService(repository,
                    new AesGcmCryptoService("MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="), true);

            var response = service.scanModels(new ScanLlmProfileCommand(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", apiKey));

            assertThat(response.available()).isFalse();
            assertThat(response.models()).isEmpty();
            assertThat(response.diagnostic()).contains("invalid response");
            assertThat(response.diagnosticCode()).isEqualTo(LlmProfileDiagnosticCode.PROVIDER_RESPONSE_INVALID);
            assertThat(new ObjectMapper().findAndRegisterModules().writeValueAsString(response))
                    .doesNotContain(apiKey).doesNotContain(providerBody);
            assertThat(repository.rows()).isEmpty();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void scanModelsHandlesProviderNetworkExceptionWithoutPersistenceOrLeakage() throws Exception {
        var apiKey = "scan-secret";
        var providerBody = "network-body-sentinel " + apiKey;
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", exchange -> {
            var partialBody = providerBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, partialBody.length + 1L);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(partialBody);
            }
        });
        server.start();
        try {
            var repository = new RecordingLlmProfileRepository();
            var service = new LlmProfileService(repository,
                    new AesGcmCryptoService("MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="), true);

            var response = service.scanModels(new ScanLlmProfileCommand(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", apiKey));

            assertThat(response.available()).isFalse();
            assertThat(response.models()).isEmpty();
            assertThat(response.diagnostic()).isEqualTo("Provider unavailable");
            assertThat(response.diagnosticCode()).isEqualTo(LlmProfileDiagnosticCode.PROVIDER_UNAVAILABLE);
            assertThat(new ObjectMapper().findAndRegisterModules().writeValueAsString(response))
                    .doesNotContain(apiKey).doesNotContain(providerBody);
            assertThat(repository.rows()).isEmpty();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void scanModelsRejectsUnsafeEndpointWithoutPersistence() {
        var repository = new RecordingLlmProfileRepository();
        var service = new LlmProfileService(repository,
                new AesGcmCryptoService("MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="), false);

        assertThatThrownBy(() -> service.scanModels(new ScanLlmProfileCommand("https://100.64.0.1", "scan-secret")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("MODEL_ENDPOINT_REJECTED");
        assertThat(repository.rows()).isEmpty();
    }

    @Test
    void testConnectionDoesNotReturnAWhitespacePaddedApiKeyFromProviderModelIds() throws Exception {
        var apiKey = " secret-key ";
        var normalizedKey = apiKey.trim();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", exchange -> {
            var body = ("{\"data\":[{\"id\":\"" + normalizedKey + "\"},{\"id\":\"safe-model\"}]}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        addSuccessfulProbe(server);
        server.start();
        try {
            var owner = "user001";
            var repository = new InMemoryRepositories.LlmProfileRepositoryStub();
            var service = new LlmProfileService(repository,
                    new AesGcmCryptoService("MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="), true);
            var profile = service.create(owner,
                    new CreateLlmProfileCommand("x", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                            "model-a", apiKey));

            var response = service.testConnection(owner, profile.id());

            assertThat(response.models()).contains("safe-model").doesNotContain(normalizedKey);
            assertThat(response.diagnosticCode()).isNull();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void testConnectionAcceptsAnyNonBlankChatCompletionFromTheSavedModel() throws Exception {
        var apiKey = "saved-probe-secret";
        var receivedAuthorization = new AtomicReference<String>();
        var receivedProbe = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", exchange -> {
            var body = "{\"data\":[{\"id\":\"selected-model\"}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.createContext("/v1/chat/completions", exchange -> {
            receivedAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            receivedProbe.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            var body = "{\"choices\":[{\"message\":{\"content\":\"Connection ready\"}}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        try {
            var owner = "user-probe";
            var repository = new InMemoryRepositories.LlmProfileRepositoryStub();
            var service = new LlmProfileService(repository,
                    new AesGcmCryptoService("MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="), true);
            var profile = service.create(owner, new CreateLlmProfileCommand("x",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "selected-model", apiKey));

            var response = service.testConnection(owner, profile.id());

            assertThat(response.available()).isTrue();
            assertThat(receivedAuthorization.get()).isEqualTo("Bearer " + apiKey);
            var probe = new ObjectMapper().readTree(receivedProbe.get());
            assertThat(probe.path("model").asText()).isEqualTo("selected-model");
            assertThat(probe.path("messages").isArray()).isTrue();
            assertThat(probe.path("messages").path(0).path("content").asText()).isEqualTo("Reply with exactly OK.");
            assertThat(probe.path("response_format").isMissingNode()).isTrue();
            assertThat(probe.path("max_tokens").asInt()).isEqualTo(32);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void testConnectionMarksBlankChatCompletionContentAsFailed() throws Exception {
        for (var content : List.of("", " \t ")) {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/models", exchange -> writeProviderResponse(exchange,
                    "{\"data\":[{\"id\":\"selected-model\"}]}", false));
            server.createContext("/v1/chat/completions", exchange -> {
                var body = new ObjectMapper().writeValueAsString(Map.of(
                        "choices", List.of(Map.of("message", Map.of("content", content)))));
                writeProviderResponse(exchange, body, false);
            });
            server.start();
            try {
                var owner = "user-blank-probe-" + content.length();
                var repository = new RecordingLlmProfileRepository();
                var service = new LlmProfileService(repository,
                        new AesGcmCryptoService("MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="), true);
                var profile = service.create(owner, new CreateLlmProfileCommand("x",
                        "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "selected-model", "test-key"));

                var response = service.testConnection(owner, profile.id());

                assertThat(response.available()).isFalse();
                assertThat(response.diagnosticCode()).isEqualTo(LlmProfileDiagnosticCode.PROVIDER_RESPONSE_INVALID);
                assertThat(service.get(owner, profile.id()).getLastTestStatus()).isEqualTo("FAILED");
            } finally {
                server.stop(0);
            }
        }
    }

    @Test
    void testConnectionUsesChatProbeWhenTheProviderDoesNotExposeModels() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        addSuccessfulProbe(server);
        server.start();
        try {
            var owner = "user-chat-only-provider";
            var repository = new InMemoryRepositories.LlmProfileRepositoryStub();
            var service = new LlmProfileService(repository,
                    new AesGcmCryptoService("MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="), true);
            var profile = service.create(owner, new CreateLlmProfileCommand("chat-only",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "selected-model", "test-key"));

            var response = service.testConnection(owner, profile.id());

            assertThat(response.available()).isTrue();
            assertThat(response.models()).isEmpty();
            assertThat(response.diagnosticCode()).isNull();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void scanModelsRejectsOversizedModelListResponsesWithAndWithoutContentLength() throws Exception {
        for (boolean chunked : List.of(false, true)) {
            var apiKey = "oversized-models-secret";
            var providerBody = "{\"data\":[{\"id\":\"safe-model\"}],\"padding\":\""
                    + ("models-body-" + apiKey).repeat(4_000) + "\"}";
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/models", exchange -> writeProviderResponse(exchange, providerBody, chunked));
            server.start();
            try {
                var service = new LlmProfileService(new RecordingLlmProfileRepository(),
                        new AesGcmCryptoService("MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="), true);

                var response = service.scanModels(new ScanLlmProfileCommand(
                        "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", apiKey));

                assertThat(response.available()).isFalse();
                assertThat(response.diagnosticCode()).isEqualTo(LlmProfileDiagnosticCode.PROVIDER_RESPONSE_INVALID);
                assertThat(new ObjectMapper().findAndRegisterModules().writeValueAsString(response))
                        .doesNotContain(apiKey).doesNotContain("models-body-");
            } finally {
                server.stop(0);
            }
        }
    }

    @Test
    void testConnectionRejectsOversizedProbeResponsesWithAndWithoutContentLength() throws Exception {
        for (boolean chunked : List.of(false, true)) {
            var apiKey = "oversized-probe-secret";
            var providerBody = "{\"choices\":[{\"message\":{\"content\":\"{\\\"ok\\\":true}\"}}],\"padding\":\""
                    + ("probe-body-" + apiKey).repeat(4_000) + "\"}";
            var receivedProbe = new AtomicReference<String>();
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/models", exchange -> writeProviderResponse(exchange,
                    "{\"data\":[{\"id\":\"selected-model\"}]}", false));
            server.createContext("/v1/chat/completions", exchange -> {
                receivedProbe.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                writeProviderResponse(exchange, providerBody, chunked);
            });
            server.start();
            try {
                var owner = "user-oversized-probe-" + chunked;
                var repository = new RecordingLlmProfileRepository();
                var service = new LlmProfileService(repository,
                        new AesGcmCryptoService("MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="), true);
                var profile = service.create(owner, new CreateLlmProfileCommand("x",
                        "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "selected-model", apiKey));

                var response = service.testConnection(owner, profile.id());

                assertThat(response.available()).isFalse();
                assertThat(response.diagnosticCode()).isEqualTo(LlmProfileDiagnosticCode.PROVIDER_RESPONSE_INVALID);
                assertThat(new ObjectMapper().findAndRegisterModules().writeValueAsString(response))
                        .doesNotContain(apiKey).doesNotContain("probe-body-");
                assertThat(new ObjectMapper().readTree(receivedProbe.get()).path("max_tokens").asInt()).isEqualTo(32);
            } finally {
                server.stop(0);
            }
        }
    }

    @Test
    void testConnectionMarksMalformedProviderJsonAsFailed() throws Exception {
        var apiKey = "saved-secret";
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", exchange -> {
            var body = "not-json".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        try {
            var owner = "user008";
            var repository = new RecordingLlmProfileRepository();
            var service = new LlmProfileService(repository,
                    new AesGcmCryptoService("MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="), true);
            var profile = service.create(owner, new CreateLlmProfileCommand("x",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "model-a", apiKey));

            var response = service.testConnection(owner, profile.id());
            var persisted = service.get(owner, profile.id());

            assertThat(response.available()).isFalse();
            assertThat(response.models()).isEmpty();
            assertThat(response.diagnostic()).isEqualTo("Provider returned invalid response");
            assertThat(response.diagnosticCode()).isEqualTo(LlmProfileDiagnosticCode.PROVIDER_RESPONSE_INVALID);
            assertThat(persisted.getLastTestStatus()).isEqualTo("FAILED");
            assertThat(persisted.getLastTestedAt()).isEqualTo(response.testedAt());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void testConnectionResolvesModelsAgainstConfiguredBasePathAndPersistsMetadata() throws Exception {
        var apiKey = "saved-secret";
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", exchange -> {
            var body = ("{\"data\":[{\"id\":\"" + apiKey + "\"},{\"id\":\"safe-model\"}]}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        addSuccessfulProbe(server);
        server.start();
        try {
            var owner = "user002";
            var repository = new RecordingLlmProfileRepository();
            var service = new LlmProfileService(repository,
                    new AesGcmCryptoService("MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="), true);
            var created = service.create(owner, new CreateLlmProfileCommand("x",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "m", apiKey));

            var response = service.testConnection(owner, created.id());
            var persisted = service.get(owner, created.id());

            assertThat(response.available()).isTrue();
            assertThat(response.models()).containsExactly("safe-model");
            assertThat(response.diagnosticCode()).isNull();
            assertThat(persisted.getLastTestStatus()).isEqualTo("SUCCEEDED");
            assertThat(persisted.getLastTestedAt()).isEqualTo(response.testedAt());
            assertThat(repository.saveCount()).isEqualTo(2);
        } finally {
            server.stop(0);
        }
    }
    @Test
    void profileReadNeverReturnsApiKeyAndCrossOwnerDecryptFails() throws Exception {
        var ownerId = "user003";
        var otherUserId = "user004";
        var repository = new InMemoryRepositories.LlmProfileRepositoryStub();
        var profileService = new LlmProfileService(repository, new AesGcmCryptoService("MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="), false);

        var profile = profileService.create(ownerId, new CreateLlmProfileCommand("work", "https://api.example/v1", "model-a", "secret-key"));

        assertThat(new ObjectMapper().findAndRegisterModules().writeValueAsString(profile)).doesNotContain("secret-key");
        assertThatThrownBy(() -> profileService.decryptForDispatch(otherUserId, profile.id())).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> profileService.testConnection(otherUserId, profile.id())).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void updateWithoutApiKeyKeepsTheExistingEncryptedKey() {
        var ownerId = "user005";
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

    @Test
    void updateWithBlankApiKeyKeepsTheExistingEncryptedKey() {
        var ownerId = "user006";
        var repository = new InMemoryRepositories.LlmProfileRepositoryStub();
        var profileService = new LlmProfileService(repository,
                new AesGcmCryptoService("MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="), false);

        var profile = profileService.create(ownerId,
                new CreateLlmProfileCommand("work", "https://1.1.1.1/v1", "model-a", "secret-key"));
        var updated = profileService.update(ownerId, profile.id(),
                new UpdateLlmProfileCommand("renamed", "https://1.1.1.1/v1", "model-b", "", false));

        assertThat(profileService.decryptForDispatch(ownerId, updated.id()).apiKey()).isEqualTo("secret-key");
        assertThat(updated.hasApiKey()).isTrue();
    }

    @Test
    void scanModelsAndTestConnectionMapProviderHttpFailures() throws Exception {
        var failures = List.of(
                new ProviderHttpFailure(401, LlmProfileDiagnosticCode.INVALID_API_KEY),
                new ProviderHttpFailure(403, LlmProfileDiagnosticCode.PROVIDER_FORBIDDEN),
                new ProviderHttpFailure(404, LlmProfileDiagnosticCode.PROVIDER_ENDPOINT_NOT_FOUND),
                new ProviderHttpFailure(429, LlmProfileDiagnosticCode.PROVIDER_RATE_LIMITED),
                new ProviderHttpFailure(503, LlmProfileDiagnosticCode.PROVIDER_UNAVAILABLE));

        for (var failure : failures) {
            var apiKey = "status-secret";
            var providerBody = "provider-body-" + failure.statusCode() + "-" + apiKey;
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/models", exchange -> {
                var body = providerBody.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(failure.statusCode(), body.length);
                try (OutputStream output = exchange.getResponseBody()) {
                    output.write(body);
                }
            });
            server.start();
            try {
                var repository = new RecordingLlmProfileRepository();
                var service = new LlmProfileService(repository,
                        new AesGcmCryptoService("MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="), true);
                var endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";

                var scanResponse = service.scanModels(new ScanLlmProfileCommand(endpoint, apiKey));
                var profile = service.create("user-status-" + failure.statusCode(),
                        new CreateLlmProfileCommand("x", endpoint, "model-a", apiKey));
                var savedResponse = service.testConnection("user-status-" + failure.statusCode(), profile.id());

                assertThat(scanResponse.diagnosticCode()).isEqualTo(failure.diagnosticCode());
                assertThat(savedResponse.diagnosticCode()).isEqualTo(failure.diagnosticCode());
                assertThat(new ObjectMapper().findAndRegisterModules().writeValueAsString(scanResponse))
                        .doesNotContain(apiKey).doesNotContain(providerBody);
                assertThat(new ObjectMapper().findAndRegisterModules().writeValueAsString(savedResponse))
                        .doesNotContain(apiKey).doesNotContain(providerBody);
            } finally {
                server.stop(0);
            }
        }
    }

    @Test
    void scanModelsAndTestConnectionMapTransportFailureToProviderUnavailable() throws Exception {
        var apiKey = "transport-secret";
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", exchange -> {
            var body = "partial".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length + 1L);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        try {
            var repository = new RecordingLlmProfileRepository();
            var service = new LlmProfileService(repository,
                    new AesGcmCryptoService("MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="), true);
            var endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";

            var scanResponse = service.scanModels(new ScanLlmProfileCommand(endpoint, apiKey));
            var profile = service.create("user-transport", new CreateLlmProfileCommand("x", endpoint, "model-a", apiKey));
            var savedResponse = service.testConnection("user-transport", profile.id());

            assertThat(scanResponse.diagnosticCode()).isEqualTo(LlmProfileDiagnosticCode.PROVIDER_UNAVAILABLE);
            assertThat(savedResponse.diagnosticCode()).isEqualTo(LlmProfileDiagnosticCode.PROVIDER_UNAVAILABLE);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void scanModelsAndTestConnectionRejectUnusableModelPayloads() throws Exception {
        for (var payload : List.of("{}", "[]")) {
            var server = modelServer(payload);
            server.start();
            try {
                var repository = new RecordingLlmProfileRepository();
                var service = new LlmProfileService(repository,
                        new AesGcmCryptoService("MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="), true);
                var endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";

                var scanResponse = service.scanModels(new ScanLlmProfileCommand(endpoint, "payload-secret"));
                var profile = service.create("user-payload-" + payload.length(),
                        new CreateLlmProfileCommand("x", endpoint, "model-a", "payload-secret"));
                var savedResponse = service.testConnection("user-payload-" + payload.length(), profile.id());

                assertThat(scanResponse.available()).isFalse();
                assertThat(scanResponse.diagnosticCode()).isEqualTo(LlmProfileDiagnosticCode.PROVIDER_RESPONSE_INVALID);
                assertThat(savedResponse.available()).isFalse();
                assertThat(savedResponse.diagnosticCode()).isEqualTo(LlmProfileDiagnosticCode.PROVIDER_RESPONSE_INVALID);
            } finally {
                server.stop(0);
            }
        }
    }

    @Test
    void scanModelsAndTestConnectionAcceptEmptyProviderDataArray() throws Exception {
        var server = modelServer("{\"data\":[]}");
        server.start();
        try {
            var repository = new RecordingLlmProfileRepository();
            var service = new LlmProfileService(repository,
                    new AesGcmCryptoService("MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="), true);
            var endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";

            var scanResponse = service.scanModels(new ScanLlmProfileCommand(endpoint, "empty-data-secret"));
            var profile = service.create("user-empty-data",
                    new CreateLlmProfileCommand("x", endpoint, "model-a", "empty-data-secret"));
            var savedResponse = service.testConnection("user-empty-data", profile.id());

            assertThat(scanResponse.available()).isTrue();
            assertThat(scanResponse.models()).isEmpty();
            assertThat(scanResponse.diagnosticCode()).isNull();
            assertThat(savedResponse.available()).isTrue();
            assertThat(savedResponse.models()).isEmpty();
            assertThat(savedResponse.diagnosticCode()).isNull();
        } finally {
            server.stop(0);
        }
    }

    private static HttpServer modelServer(String payload) throws java.io.IOException {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", exchange -> {
            var body = payload.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        addSuccessfulProbe(server);
        return server;
    }

    @Test
    void testConnectionSendsAPlainTextProbeWithoutJsonMode() throws Exception {
        var capturedPayload = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", exchange -> writeProviderResponse(exchange,
                "{\"data\":[{\"id\":\"safe-model\"}]}", false));
        server.createContext("/v1/chat/completions", exchange -> {
            capturedPayload.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            writeProviderResponse(exchange,
                    "{\"choices\":[{\"message\":{\"content\":\"OK\"}}]}", false);
        });
        server.start();
        try {
            var repository = new InMemoryRepositories.LlmProfileRepositoryStub();
            var service = new LlmProfileService(repository,
                    new AesGcmCryptoService("MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="), true);
            var profile = service.create("user-probe-payload", new CreateLlmProfileCommand(
                    "probe", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "safe-model", "test-key"));

            var response = service.testConnection("user-probe-payload", profile.id());

            assertThat(response.available()).isTrue();
            var payload = new ObjectMapper().readTree(capturedPayload.get());
            assertThat(payload.path("messages").path(0).path("content").textValue())
                    .isEqualTo("Reply with exactly OK.");
            assertThat(payload.path("response_format").isMissingNode()).isTrue();
            assertThat(payload.path("max_tokens").intValue()).isEqualTo(32);
        } finally {
            server.stop(0);
        }
    }

    private static void writeProviderResponse(HttpExchange exchange, String payload, boolean chunked) throws java.io.IOException {
        var body = payload.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, chunked ? 0 : body.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    private static void addSuccessfulProbe(HttpServer server) {
        server.createContext("/v1/chat/completions", exchange -> {
            var body = "{\"choices\":[{\"message\":{\"content\":\"{\\\"ok\\\":true}\"}}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
    }

    private record ProviderHttpFailure(int statusCode, LlmProfileDiagnosticCode diagnosticCode) {
    }

    private static final class RecordingLlmProfileRepository implements LlmProfileRepository {
        private final List<LlmProfile> rows = new ArrayList<>();

        @Override
        public LlmProfile save(LlmProfile profile) {
            rows.add(profile);
            return profile;
        }

        @Override
        public Optional<LlmProfile> findByIdAndOwnerId(String id, String ownerId) {
            return rows.stream().filter(profile -> profile.id().equals(id) && profile.getOwnerId().equals(ownerId)).findFirst();
        }

        @Override
        public List<LlmProfile> findAllByOwnerId(String ownerId) {
            return rows.stream().filter(profile -> profile.getOwnerId().equals(ownerId)).toList();
        }

        @Override
        public void delete(LlmProfile profile) {
            rows.remove(profile);
        }

        List<LlmProfile> rows() {
            return List.copyOf(rows);
        }

        int saveCount() {
            return rows.size();
        }
    }
}
