package com.resumethinking.platform.matching;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.resumethinking.platform.ids.BusinessIdType;
import com.resumethinking.platform.ids.ReadableIdGenerator;
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
        this.http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(3)).build(); this.mapper = new ObjectMapper().findAndRegisterModules();
    }
    public void dispatch(InternalAnalysisJob job) {
        // Never serialize or send the job when the shared credential is an
        // example value.  The payload contains the decrypted provider key and
        // resume bytes, so fail closed before any network side effect.
        if (!usableToken(internalServiceToken)) {
            throw new InternalTokenConfigurationException();
        }
        try {
            String json = mapper.writeValueAsString(job);
            URI jobUri = baseUrl.resolve(job.revisionId() == null ? "/internal/v2/analysis-jobs" : "/internal/v3/analysis-jobs");
            HttpRequest request = HttpRequest.newBuilder(jobUri)
                    .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                    .header(INTERNAL_TOKEN_HEADER, internalServiceToken)
                    .POST(HttpRequest.BodyPublishers.ofString(json)).build();
            var response = http.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() / 100 != 2) {
                if (response.statusCode() == 401 || response.statusCode() == 403) {
                    throw new InternalAuthenticationException();
                }
                throw new ServiceUnavailableException();
            }
        } catch (InternalAuthenticationException | ServiceUnavailableException failure) {
            throw failure;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ServiceUnavailableException();
        } catch (Exception ignored) {
            // The worker endpoint, network details, and original exception may
            // contain deployment information.  The task layer exposes only a
            // stable, safe failure code to callers.
            throw new ServiceUnavailableException();
        }
    }
    public static final class ServiceUnavailableException extends RuntimeException {
        public ServiceUnavailableException() { super("PYTHON_SERVICE_UNAVAILABLE"); }
    }
    public static final class InternalAuthenticationException extends RuntimeException {
        public InternalAuthenticationException() { super("PYTHON_SERVICE_AUTHENTICATION_FAILED"); }
    }
    public static final class InternalTokenConfigurationException extends IllegalStateException {
        public InternalTokenConfigurationException() { super("PYTHON_INTERNAL_SERVICE_TOKEN_MISSING"); }
    }
    public static String failureCodeFor(RuntimeException failure) {
        if (failure instanceof InternalAuthenticationException || failure instanceof InternalTokenConfigurationException) {
            return "PYTHON_SERVICE_AUTHENTICATION_FAILED";
        }
        return "PYTHON_SERVICE_UNAVAILABLE";
    }
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record InternalAnalysisJob(String taskId, String revisionId, String callbackId, int attempt, long resumeVersion, String sourceType, JobFamily jobFamily,
                                      Document document, Set<AllowedEvidence> allowedEvidence, String jobDescriptionText,
                                      boolean redactionRequired, URI callbackUrl, String callbackToken,
                                      Provider provider, UUID correlationId) {
        public InternalAnalysisJob(String taskId, String callbackId, int attempt, long resumeVersion, String sourceType, JobFamily jobFamily,
                                   Document document, Set<AllowedEvidence> allowedEvidence, String jobDescriptionText,
                                   boolean redactionRequired, URI callbackUrl, String callbackToken,
                                   Provider provider, UUID correlationId) {
            this(taskId, null, callbackId, attempt, resumeVersion, sourceType, jobFamily, document,
                    allowedEvidence, jobDescriptionText, redactionRequired, callbackUrl, callbackToken,
                    provider, correlationId);
        }
        /** Compatibility constructor for callers created before job families were explicit. */
        public InternalAnalysisJob(String taskId, int attempt, long resumeVersion, String sourceType,
                                   Document document, Set<AllowedEvidence> allowedEvidence, String jobDescriptionText,
                                   boolean redactionRequired, URI callbackUrl, String callbackToken,
                                   Provider provider, UUID correlationId) {
            this(taskId, null, compatibilityCallbackId(taskId), attempt, resumeVersion, sourceType, JobFamily.JAVA_BACKEND, document,
                    allowedEvidence, jobDescriptionText, redactionRequired, callbackUrl, callbackToken,
                    provider, correlationId);
        }

        private static String compatibilityCallbackId(String taskId) {
            if (!ReadableIdGenerator.isValid(BusinessIdType.TASK, taskId)) {
                throw new IllegalArgumentException("compatibility construction requires a v2 task id");
            }
            String callbackId = BusinessIdType.CALLBACK.prefix + taskId.substring(BusinessIdType.TASK.prefix.length());
            ReadableIdGenerator.validate(BusinessIdType.CALLBACK, callbackId);
            return callbackId;
        }

        @Override public String toString() {
            return "InternalAnalysisJob[taskId=" + taskId + ", revisionId=" + revisionId
                    + ", callbackId=" + callbackId + ", attempt=" + attempt
                    + ", resumeVersion=" + resumeVersion + ", sourceType=" + sourceType
                    + ", jobFamily=" + jobFamily + ", document=<redacted>"
                    + ", allowedEvidenceCount=" + (allowedEvidence == null ? 0 : allowedEvidence.size())
                    + ", jobDescriptionText=<redacted>, redactionRequired=" + redactionRequired
                    + ", callbackUrl=<redacted>, callbackToken=<redacted>, provider=" + provider
                    + ", correlationId=" + correlationId + "]";
        }
    }
    public record Document(String contentBase64, String originalFilename) {
        @Override public String toString() {
            return "Document[contentBase64=<redacted>, originalFilename=<redacted>]";
        }
    }
    public record AllowedEvidence(String evidenceId, String sourceLocation, int sourceStart, int sourceEnd) {}
    public record Provider(URI baseUrl, String model, String apiKey) {
        @Override public String toString() {
            return "Provider[baseUrl=" + baseUrl + ", model=" + model + ", apiKey=<redacted>]";
        }
    }
    private static String configuredInternalToken() {
        String token = System.getProperty("app.python-internal-service-token");
        if (token == null || token.isBlank()) token = System.getProperty("python.internal.service-token");
        if (token == null || token.isBlank()) token = System.getenv("PYTHON_INTERNAL_SERVICE_TOKEN");
        return token;
    }
    private static boolean usableToken(String token) {
        if (token == null || token.length() < 32) return false;
        for (int i = 0; i < token.length(); i++) {
            char character = token.charAt(i);
            if (character < 0x21 || character > 0x7e) return false;
        }
        String normalized = token.toLowerCase(Locale.ROOT);
        return !normalized.contains("replace-with")
                && !normalized.contains("change-me")
                && !normalized.contains("placeholder");
    }
    public static final class Noop extends PythonAnalysisClient { public Noop() { super(URI.create("http://127.0.0.1:1")); } @Override public void dispatch(InternalAnalysisJob job) {} }
}
