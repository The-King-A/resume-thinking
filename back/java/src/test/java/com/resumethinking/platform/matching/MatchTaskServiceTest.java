package com.resumethinking.platform.matching;

import com.resumethinking.platform.auth.UserRole;
import com.resumethinking.platform.profiles.LlmProfileService;
import com.resumethinking.platform.resumes.*;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MatchTaskServiceTest {
    private final UUID userId = UUID.randomUUID();
    private final UUID resumeId = UUID.randomUUID();
    private final UUID profileId = UUID.randomUUID();

    @Test
    void duplicateSubmissionReturnsOriginalTaskForSameOwnerAndIdempotencyKey() {
        var resumes = new ResumeRepository.InMemory();
        resumes.save(Resume.active(resumeId, userId, "CV", Resume.SourceType.TXT, UserRole.USER,
                Instant.parse("2026-01-01T00:00:00Z"), 0L));
        var profiles = new TestProfileService(userId, profileId);
        var tasks = new MatchTaskRepository.InMemory();
        var service = new MatchTaskService(new ResumeLifecycleService(resumes, new ResumeCache.InMemory(), new ResumeAuditRepository.InMemory()),
                profiles, tasks, new PythonAnalysisClient.Noop());

        var first = service.createTask(new CreateMatchTaskCommand(userId, resumeId, profileId,
                "Build reliable software with clear communication and practical testing.", "same-key-00000001"));
        var second = service.createTask(new CreateMatchTaskCommand(userId, resumeId, profileId,
                "Build reliable software with clear communication and practical testing.", "same-key-00000001"));

        assertThat(second.id()).isEqualTo(first.id());
    }

    @Test
    void dispatchUsesDecryptedResumeBytesAndBoundedEvidence() {
        var crypto = new com.resumethinking.platform.crypto.AesGcmCryptoService("MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
        var encrypted = crypto.encrypt("Java\nTesting");
        var resumes = new ResumeRepository.InMemory();
        resumes.save(new Resume(resumeId, userId, "CV", Resume.SourceType.TXT, UserRole.USER, encrypted.ciphertext(), encrypted.nonce(), Instant.now(), "v1"));
        var capture = new CapturingClient();
        var service = new MatchTaskService(new ResumeLifecycleService(resumes, new ResumeCache.Noop(), new ResumeAuditRepository.InMemory()),
                new TestProfileService(userId, profileId), new MatchTaskRepository.InMemory(), capture,
                new AnalysisResultRepository.InMemory(), new CallbackReceiptRepository.InMemory(), new AnalysisEvidenceRepository.InMemory(), crypto);
        service.createTask(new CreateMatchTaskCommand(userId, resumeId, profileId,
                "Build reliable software with clear communication and practical testing.", "plain-key-0000001"));
        assertThat(new String(java.util.Base64.getDecoder().decode(capture.job.document().contentBase64()), StandardCharsets.UTF_8)).isEqualTo("Java\nTesting");
        assertThat(capture.job.allowedEvidence()).allSatisfy(e -> assertThat(e.sourceEnd()).isLessThanOrEqualTo(12));
    }

    private static final class CapturingClient extends PythonAnalysisClient {
        private InternalAnalysisJob job;
        CapturingClient() { super(URI.create("http://127.0.0.1:1")); }
        @Override public void dispatch(InternalAnalysisJob job) { this.job = job; }
    }

    private static final class TestProfileService extends LlmProfileService {
        private final UUID owner;
        private final UUID id;
        TestProfileService(UUID owner, UUID id) { super(new com.resumethinking.platform.auth.InMemoryRepositories.LlmProfileRepositoryStub(), new com.resumethinking.platform.crypto.AesGcmCryptoService("MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=")); this.owner = owner; this.id = id; }
        @Override public com.resumethinking.platform.profiles.DispatchLlmProfile decryptForDispatch(UUID actorId, UUID profileId) {
            if (!owner.equals(actorId) || !id.equals(profileId)) throw new com.resumethinking.platform.profiles.ResourceNotFoundException();
            return new com.resumethinking.platform.profiles.DispatchLlmProfile(URI.create("https://provider.example"), "model", "key");
        }
    }
}
