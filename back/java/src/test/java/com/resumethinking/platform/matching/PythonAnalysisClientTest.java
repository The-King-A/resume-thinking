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
            var client = new PythonAnalysisClient(new URI("http://127.0.0.1:" + server.getAddress().getPort()), "test-internal-token");
            client.dispatch(job("test-internal-token"));
            assertThat(header).hasValue("test-internal-token");
            assertThat(body).hasValueSatisfying(value -> assertThat(value).doesNotContain("test-internal-token"));
        } finally {
            server.stop(0);
        }
    }

    private static PythonAnalysisClient.InternalAnalysisJob job(String ignored) {
        return new PythonAnalysisClient.InternalAnalysisJob(TestIds.task(), 1, 0, "TXT",
                new PythonAnalysisClient.Document(Base64.getEncoder().encodeToString("Java".getBytes(StandardCharsets.UTF_8)), "resume.txt"),
                Set.of(new PythonAnalysisClient.AllowedEvidence(TestIds.evidence(), "txt:0", 0, 4)),
                "Build reliable software with clear communication and practical testing.", true,
                URI.create("http://127.0.0.1:8080/internal/v1/analysis-results"), "callback-token-0000000000000000000000000000",
                new PythonAnalysisClient.Provider(URI.create("https://provider.example"), "model", "provider-key"), UUID.randomUUID());
    }
}
