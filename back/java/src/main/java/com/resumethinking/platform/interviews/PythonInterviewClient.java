package com.resumethinking.platform.interviews;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class PythonInterviewClient {
    private static final String TOKEN_HEADER = "X-Internal-Service-Token";
    private final URI baseUrl;
    private final String internalToken;
    private final HttpClient http;
    private final ObjectMapper mapper;

    public PythonInterviewClient() {
        this(URI.create(System.getenv().getOrDefault("PYTHON_ANALYSIS_BASE_URL", "http://127.0.0.1:8000")),
                System.getenv().getOrDefault("PYTHON_INTERNAL_SERVICE_TOKEN", ""));
    }

    @Autowired
    public PythonInterviewClient(
            @Value("${app.python-analysis-base-url:${PYTHON_ANALYSIS_BASE_URL:http://127.0.0.1:8000}}") String configuredBaseUrl,
            @Value("${app.python-internal-service-token:${PYTHON_INTERNAL_SERVICE_TOKEN:}}") String configuredToken) {
        this(URI.create(configuredBaseUrl), configuredToken);
    }

    public PythonInterviewClient(URI baseUrl, String internalToken) {
        this.baseUrl = baseUrl;
        this.internalToken = internalToken;
        this.http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(3)).build();
        this.mapper = new ObjectMapper().findAndRegisterModules();
    }

    public void dispatch(InternalInterviewJob job) {
        if (!usableToken(internalToken)) throw new IllegalStateException("PYTHON_INTERNAL_SERVICE_TOKEN_MISSING");
        try {
            HttpRequest request = HttpRequest.newBuilder(baseUrl.resolve(interviewJobPath()))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .header(TOKEN_HEADER, internalToken)
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(job)))
                    .build();
            var response = http.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() == 401 || response.statusCode() == 403) {
                throw new IllegalStateException("PYTHON_SERVICE_AUTHENTICATION_FAILED");
            }
            if (response.statusCode() / 100 != 2) throw new IllegalStateException("PYTHON_SERVICE_UNAVAILABLE");
        } catch (IllegalStateException failure) {
            throw failure;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("PYTHON_SERVICE_UNAVAILABLE");
        } catch (Exception ignored) {
            throw new IllegalStateException("PYTHON_SERVICE_UNAVAILABLE");
        }
    }

    public static String interviewJobPath() { return "/internal/v4/interview-jobs"; }

    private static boolean usableToken(String token) {
        if (token == null || token.length() < 32) return false;
        for (int index = 0; index < token.length(); index++) {
            char value = token.charAt(index);
            if (value < 0x21 || value > 0x7e) return false;
        }
        String lower = token.toLowerCase(java.util.Locale.ROOT);
        return !lower.contains("replace-with") && !lower.contains("change-me") && !lower.contains("placeholder");
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record InternalInterviewJob(String contractVersion, String workType, String sessionId,
                                       String revisionId, String matchTaskId, long sessionVersion,
                                       int attempt, String callbackId, URI callbackUrl,
                                       String callbackToken, boolean redactionRequired, Provider provider,
                                       Map<String, Object> questionGeneration,
                                       Map<String, Object> answerAnalysis, UUID correlationId) {
        @Override public String toString() {
            return "InternalInterviewJob[contractVersion=" + contractVersion + ", workType=" + workType
                    + ", sessionId=" + sessionId + ", revisionId=" + revisionId + ", matchTaskId=" + matchTaskId
                    + ", sessionVersion=" + sessionVersion + ", attempt=" + attempt + ", callbackId=" + callbackId
                    + ", callbackUrl=<redacted>, callbackToken=<redacted>, redactionRequired=" + redactionRequired
                    + ", provider=" + provider + ", questionGeneration=<redacted>, answerAnalysis=<redacted>, correlationId=" + correlationId + "]";
        }
    }

    public record Provider(URI baseUrl, String model, String apiKey) {
        @Override public String toString() { return "Provider[baseUrl=" + baseUrl + ", model=" + model + ", apiKey=<redacted>]"; }
    }

    public static final class Noop extends PythonInterviewClient {
        public Noop() { super(URI.create("http://127.0.0.1:1"), ""); }
        @Override public void dispatch(InternalInterviewJob job) { }
    }
}
