package com.resumethinking.platform.matching;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

@Component
public class PythonAnalysisClient {
    private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Service-Token";
    private final URI baseUrl;
    private final HttpClient http;
    private final ObjectMapper mapper;
    private final String internalServiceToken;
    public PythonAnalysisClient() {
        this(URI.create(System.getProperty("python.analysis.base-url", System.getenv().getOrDefault("PYTHON_ANALYSIS_BASE_URL", "http://127.0.0.1:8000"))), configuredInternalToken());
    }
    @Autowired
    public PythonAnalysisClient(
            @Value("${app.python-analysis-base-url:${PYTHON_ANALYSIS_BASE_URL:http://127.0.0.1:8000}}") String configuredBaseUrl,
            @Value("${app.python-internal-service-token:${PYTHON_INTERNAL_SERVICE_TOKEN:}}") String configuredToken) {
        this(URI.create(configuredBaseUrl), configuredToken);
    }
    public PythonAnalysisClient(URI baseUrl) { this(baseUrl, null); }
    /** Test/embedding constructor; production wiring should use the configured constructor above. */
    public PythonAnalysisClient(URI baseUrl, String internalServiceToken) {
        this.baseUrl = baseUrl; this.internalServiceToken = internalServiceToken;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build(); this.mapper = new ObjectMapper().findAndRegisterModules();
    }
    public void dispatch(InternalAnalysisJob job) {
        // Never serialize or send the job when the shared credential is an
        // example value.  The payload contains the decrypted provider key and
        // resume bytes, so fail closed before any network side effect.
        if (!usableToken(internalServiceToken)) {
            throw new IllegalStateException("PYTHON_INTERNAL_SERVICE_TOKEN_MISSING");
        }
        try {
            String json = mapper.writeValueAsString(job);
            HttpRequest request = HttpRequest.newBuilder(baseUrl.resolve("/internal/v1/analysis-jobs"))
                    .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                    .header(INTERNAL_TOKEN_HEADER, internalServiceToken)
                    .POST(HttpRequest.BodyPublishers.ofString(json)).build();
            var response = http.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() / 100 != 2) throw new IllegalStateException("MODEL_UNAVAILABLE");
        } catch (Exception e) {
            throw new IllegalStateException("MODEL_UNAVAILABLE", e);
        }
    }
    public record InternalAnalysisJob(String taskId, int attempt, long resumeVersion, String sourceType, JobFamily jobFamily,
                                      Document document, Set<AllowedEvidence> allowedEvidence, String jobDescriptionText,
                                      boolean redactionRequired, URI callbackUrl, String callbackToken,
                                      Provider provider, UUID correlationId) {
        /** Compatibility constructor for callers created before job families were explicit. */
        public InternalAnalysisJob(String taskId, int attempt, long resumeVersion, String sourceType,
                                   Document document, Set<AllowedEvidence> allowedEvidence, String jobDescriptionText,
                                   boolean redactionRequired, URI callbackUrl, String callbackToken,
                                   Provider provider, UUID correlationId) {
            this(taskId, attempt, resumeVersion, sourceType, JobFamily.JAVA_BACKEND, document,
                    allowedEvidence, jobDescriptionText, redactionRequired, callbackUrl, callbackToken,
                    provider, correlationId);
        }
    }
    public record Document(String contentBase64, String originalFilename) {}
    public record AllowedEvidence(String evidenceId, String sourceLocation, int sourceStart, int sourceEnd) {}
    public record Provider(URI baseUrl, String model, String apiKey) {}
    private static String configuredInternalToken() {
        String token = System.getProperty("app.python-internal-service-token");
        if (token == null || token.isBlank()) token = System.getProperty("python.internal.service-token");
        if (token == null || token.isBlank()) token = System.getenv("PYTHON_INTERNAL_SERVICE_TOKEN");
        return token;
    }
    private static boolean usableToken(String token) {
        if (token == null || token.isBlank()) return false;
        String normalized = token.toLowerCase(Locale.ROOT);
        return !normalized.contains("replace-with")
                && !normalized.contains("change-me")
                && !normalized.contains("placeholder");
    }
    public static final class Noop extends PythonAnalysisClient { public Noop() { super(URI.create("http://127.0.0.1:1")); } @Override public void dispatch(InternalAnalysisJob job) {} }
}
