package com.resumethinking.platform.matching;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;
import com.resumethinking.platform.TestIds;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PythonAnalysisClientTest {
    @Test
    void dispatchFailsClosedWhenInternalTokenIsMissing() {
        var client = new PythonAnalysisClient(URI.create("http://127.0.0.1:1"));
        assertThatThrownBy(() -> client.dispatch(job("unused")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("PYTHON_INTERNAL_SERVICE_TOKEN_MISSING");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "replace-with-shared-service-token", "change-me", "placeholder-token"
    })
    void dispatchFailsClosedForExampleInternalTokens(String token) {
        var client = new PythonAnalysisClient(URI.create("http://127.0.0.1:1"), token);
        assertThatThrownBy(() -> client.dispatch(job(token)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("PYTHON_INTERNAL_SERVICE_TOKEN_MISSING");
    }

    @org.junit.jupiter.api.Test
    void dispatchFailsClosedForShortOrWhitespaceInternalTokens() {
        for (String token : java.util.List.of("t".repeat(31), "t".repeat(31) + " ", "t".repeat(16) + "\t" + "t".repeat(16))) {
            var client = new PythonAnalysisClient(URI.create("http://127.0.0.1:1"), token);
            assertThatThrownBy(() -> client.dispatch(job(token)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("PYTHON_INTERNAL_SERVICE_TOKEN_MISSING");
        }
    }

    @Test
    void dispatchSendsInternalTokenOnlyAsHeader() throws Exception {
        AtomicReference<String> header = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v2/analysis-jobs", exchange -> {
            header.set(exchange.getRequestHeaders().getFirst("X-Internal-Service-Token"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(202, 0);
            exchange.close();
        });
        server.start();
        try {
            String token = "test-internal-token-123456789012";
            var client = new PythonAnalysisClient(new URI("http://127.0.0.1:" + server.getAddress().getPort()), token);
            client.dispatch(job(token));
            assertThat(header).hasValue(token);
            assertThat(body).hasValueSatisfying(value -> {
                assertThat(value).doesNotContain(token);
                assertThat(value).doesNotContain("\"revisionId\"");
            });
        } finally {
            server.stop(0);
        }
    }

    @Test
    void dispatchesV3JobWithRevisionIdentityWithoutLeakingSecretsInDiagnostics() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v3/analysis-jobs", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(202, 0);
            exchange.close();
        });
        server.start();
        try {
            String internalToken = "test-internal-token-123456789012";
            String providerKey = "provider-key-that-must-not-appear-in-diagnostics";
            var client = new PythonAnalysisClient(new URI("http://127.0.0.1:" + server.getAddress().getPort()), internalToken);
            client.dispatch(v3Job(providerKey));

            assertThat(body).hasValueSatisfying(value -> {
                assertThat(value).contains("\"taskId\":\"task001\"");
                assertThat(value).contains("\"revisionId\":\"revision001\"");
                assertThat(value).contains("\"callbackId\":\"callback001\"");
                assertThat(value).doesNotContain(internalToken);
            });
            assertThat(v3Job(providerKey).provider().toString()).doesNotContain(providerKey);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void v3JobDiagnosticsRedactTheEntireTransportPayload() {
        String callbackToken = "CALLBACK_DIAGNOSTIC_SENTINEL";
        String documentPayload = "DOCUMENT_PAYLOAD_DIAGNOSTIC_SENTINEL";
        String filename = "FILENAME_DIAGNOSTIC_SENTINEL";
        String providerKey = "PROVIDER_KEY_DIAGNOSTIC_SENTINEL";
        var job = new PythonAnalysisClient.InternalAnalysisJob("task001", "revision001", "callback001", 1, 0, "TXT",
                JobFamily.JAVA_BACKEND,
                new PythonAnalysisClient.Document(documentPayload, filename),
                Set.of(new PythonAnalysisClient.AllowedEvidence("evidence001", "txt:0", 0, 4)),
                "JOB_DESCRIPTION_DIAGNOSTIC_SENTINEL", true,
                URI.create("http://127.0.0.1:8080/internal/v3/analysis-results"), callbackToken,
                new PythonAnalysisClient.Provider(URI.create("https://provider.example"), "model", providerKey), UUID.randomUUID());

        String diagnostic = job.toString();
        String documentDiagnostic = job.document().toString();

        assertThat(diagnostic.contains(callbackToken)).isFalse();
        assertThat(diagnostic.contains(documentPayload)).isFalse();
        assertThat(diagnostic.contains(filename)).isFalse();
        assertThat(diagnostic.contains(providerKey)).isFalse();
        assertThat(diagnostic.contains("JOB_DESCRIPTION_DIAGNOSTIC_SENTINEL")).isFalse();
        assertThat(documentDiagnostic.contains(documentPayload)).isFalse();
        assertThat(documentDiagnostic.contains(filename)).isFalse();
    }

    @Test
    void dispatchUsesHttp11ForCleartextWorker() throws Exception {
        AtomicReference<String> upgrade = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v2/analysis-jobs", exchange -> {
            upgrade.set(exchange.getRequestHeaders().getFirst("Upgrade"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(202, 0);
            exchange.close();
        });
        server.start();
        try {
            var client = new PythonAnalysisClient(new URI("http://127.0.0.1:" + server.getAddress().getPort()),
                    "test-internal-token-123456789012");
            client.dispatch(job("http11"));
            assertThat(upgrade.get()).isNull();
            assertThat(body.get()).startsWith("{").isNotEmpty();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void dispatchMapsWorkerHttpFailureToSafeServiceUnavailableCode() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v2/analysis-jobs", exchange -> {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.start();
        try {
            var client = new PythonAnalysisClient(new URI("http://127.0.0.1:" + server.getAddress().getPort()),
                    "test-internal-token-123456789012");

            assertThatThrownBy(() -> client.dispatch(job("worker-http-failure")))
                    .isInstanceOf(PythonAnalysisClient.ServiceUnavailableException.class)
                    .hasMessage("PYTHON_SERVICE_UNAVAILABLE")
                    .hasNoCause();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void dispatchClassifiesUnauthorizedWorkerAsInternalAuthenticationFailure() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v2/analysis-jobs", exchange -> {
            exchange.sendResponseHeaders(401, -1);
            exchange.close();
        });
        server.start();
        try {
            var client = new PythonAnalysisClient(new URI("http://127.0.0.1:" + server.getAddress().getPort()),
                    "test-internal-token-123456789012");

            assertThatThrownBy(() -> client.dispatch(job("worker-auth-failure")))
                    .isInstanceOf(PythonAnalysisClient.InternalAuthenticationException.class)
                    .hasMessage("PYTHON_SERVICE_AUTHENTICATION_FAILED")
                    .hasNoCause();
        } finally {
            server.stop(0);
        }
    }

    private static PythonAnalysisClient.InternalAnalysisJob job(String ignored) {
        return new PythonAnalysisClient.InternalAnalysisJob(TestIds.task(), 1, 0, "TXT",
                new PythonAnalysisClient.Document(Base64.getEncoder().encodeToString("Java".getBytes(StandardCharsets.UTF_8)), "resume.txt"),
                Set.of(new PythonAnalysisClient.AllowedEvidence(TestIds.evidence(), "txt:0", 0, 4)),
                "Build reliable software with clear communication and practical testing.", true,
                URI.create("http://127.0.0.1:8080/internal/v2/analysis-results"), "callback-token-0000000000000000000000000000",
                new PythonAnalysisClient.Provider(URI.create("https://provider.example"), "model", "provider-key"), UUID.randomUUID());
    }

    private static PythonAnalysisClient.InternalAnalysisJob v3Job(String providerKey) {
        return new PythonAnalysisClient.InternalAnalysisJob("task001", "revision001", "callback001", 1, 0, "TXT",
                JobFamily.JAVA_BACKEND,
                new PythonAnalysisClient.Document(Base64.getEncoder().encodeToString("Java".getBytes(StandardCharsets.UTF_8)), "resume.txt"),
                Set.of(new PythonAnalysisClient.AllowedEvidence("evidence001", "txt:0", 0, 4)),
                "Build reliable software with clear communication and practical testing.", true,
                URI.create("http://127.0.0.1:8080/internal/v3/analysis-results"), "callback-token-0000000000000000000000000000",
                new PythonAnalysisClient.Provider(URI.create("https://provider.example"), "model", providerKey), UUID.randomUUID());
    }
}
