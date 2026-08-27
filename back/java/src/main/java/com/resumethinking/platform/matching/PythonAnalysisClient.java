package com.resumethinking.platform.matching;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class PythonAnalysisClient {
    private final URI baseUrl;
    private final HttpClient http;
    private final ObjectMapper mapper;
    public PythonAnalysisClient() { this(URI.create(System.getProperty("python.analysis.base-url", System.getenv().getOrDefault("PYTHON_ANALYSIS_BASE_URL", "http://127.0.0.1:8000")))); }
    public PythonAnalysisClient(URI baseUrl) { this.baseUrl = baseUrl; this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build(); this.mapper = new ObjectMapper().findAndRegisterModules(); }
    public void dispatch(InternalAnalysisJob job) {
        try {
            String json = mapper.writeValueAsString(job);
            HttpRequest request = HttpRequest.newBuilder(baseUrl.resolve("/internal/v1/analysis-jobs"))
                    .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json)).build();
            var response = http.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() / 100 != 2) throw new IllegalStateException("MODEL_UNAVAILABLE");
        } catch (Exception e) {
            throw new IllegalStateException("MODEL_UNAVAILABLE", e);
        }
    }
    public record InternalAnalysisJob(UUID taskId, int attempt, long resumeVersion, String sourceType,
                                      Document document, Set<AllowedEvidence> allowedEvidence, String jobDescriptionText,
                                      boolean redactionRequired, URI callbackUrl, String callbackToken,
                                      Provider provider, UUID correlationId) {}
    public record Document(String contentBase64, String originalFilename) {}
    public record AllowedEvidence(UUID evidenceId, String sourceLocation, int sourceStart, int sourceEnd) {}
    public record Provider(URI baseUrl, String model, String apiKey) {}
    public static final class Noop extends PythonAnalysisClient { public Noop() { super(URI.create("http://127.0.0.1:1")); } @Override public void dispatch(InternalAnalysisJob job) {} }
}
