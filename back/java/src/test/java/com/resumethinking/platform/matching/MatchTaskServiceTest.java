package com.resumethinking.platform.matching;

import com.resumethinking.platform.auth.UserRole;
import com.resumethinking.platform.profiles.LlmProfileService;
import com.resumethinking.platform.resumes.*;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.Set;
import java.util.ArrayList;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        var service = new MatchTaskService(new ResumeLifecycleService(resumes, new ResumeCache.InMemory(), new ResumeAuditRepository.InMemory(),
                        Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC)),
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
        var task = service.createTask(new CreateMatchTaskCommand(userId, resumeId, profileId,
                "Build reliable software with clear communication and practical testing.", "plain-key-0000001"));
        assertThat(task.state()).isEqualTo(MatchTask.State.PROCESSING);
        assertThat(new String(java.util.Base64.getDecoder().decode(capture.job.document().contentBase64()), StandardCharsets.UTF_8)).isEqualTo("Java\nTesting");
        assertThat(capture.job.allowedEvidence()).allSatisfy(e -> assertThat(e.sourceEnd()).isLessThanOrEqualTo(12));
    }

    @Test
    void missingCryptoNeverDispatchesPersistedCiphertext() {
        var resumes = new ResumeRepository.InMemory();
        byte[] ciphertext = "ciphertext-must-not-leak".getBytes(StandardCharsets.UTF_8);
        resumes.save(new Resume(resumeId, userId, "CV", Resume.SourceType.TXT, UserRole.USER,
                ciphertext, new byte[12], Instant.now(), "v1"));
        var capture = new CapturingClient();
        var service = new MatchTaskService(new ResumeLifecycleService(resumes, new ResumeCache.Noop(), new ResumeAuditRepository.InMemory()),
                new TestProfileService(userId, profileId), new MatchTaskRepository.InMemory(), capture,
                new AnalysisResultRepository.InMemory(), new CallbackReceiptRepository.InMemory(),
                new AnalysisEvidenceRepository.InMemory(), null);

        var task = service.createTask(new CreateMatchTaskCommand(userId, resumeId, profileId,
                "Build reliable software with clear communication and practical testing.", "missing-crypto-0001"));

        assertThat(task.state()).isEqualTo(MatchTask.State.FAILED);
        assertThat(capture.job).isNull();
    }

    @Test
    void blockedTaskIsGoneFromTaskAndResultReads() {
        var resumes = new ResumeRepository.InMemory();
        var resume = Resume.active(resumeId, userId, "CV", Resume.SourceType.TXT, UserRole.USER, Instant.now(), 0L); resumes.save(resume);
        var task = new MatchTask(UUID.randomUUID(), resumeId, profileId, userId, 0L, "Build reliable software with clear communication and practical testing.", "blocked-key-00001", "token-token-token-token-token-token", Set.of(UUID.randomUUID()), Instant.now()); task.markBlocked();
        var repo = new MatchTaskRepository.InMemory(); repo.save(task);
        var service = new MatchTaskService(new ResumeLifecycleService(resumes, new ResumeCache.Noop(), new ResumeAuditRepository.InMemory()), null, repo, new PythonAnalysisClient.Noop());
        assertThatThrownBy(() -> service.getTask(task.id(), userId, UserRole.USER)).isInstanceOf(TaskGoneException.class);
    }

    @Test
    void rejectsJobDescriptionAndIdempotencyKeysAboveContractBounds() {
        var resumes = new ResumeRepository.InMemory();
        resumes.save(Resume.active(resumeId, userId, "CV", Resume.SourceType.TXT, UserRole.USER, Instant.now(), 0L));
        var service = new MatchTaskService(new ResumeLifecycleService(resumes, new ResumeCache.Noop(), new ResumeAuditRepository.InMemory()),
                null, new MatchTaskRepository.InMemory(), new PythonAnalysisClient.Noop());
        assertThatThrownBy(() -> service.createTask(new CreateMatchTaskCommand(userId, resumeId, profileId,
                "x".repeat(20_001), "valid-key-00000001")))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("VALIDATION_ERROR");
        assertThatThrownBy(() -> service.createTask(new CreateMatchTaskCommand(userId, resumeId, profileId,
                "Build reliable software with clear communication and practical testing.", "k".repeat(129))))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("VALIDATION_ERROR");
    }

    @Test
    void txtEvidenceCoordinatesNormalizeCrLfForPython() {
        var crypto = new com.resumethinking.platform.crypto.AesGcmCryptoService("MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
        var encrypted = crypto.encrypt("Java\r\nTesting");
        var resumes = new ResumeRepository.InMemory();
        resumes.save(new Resume(resumeId, userId, "CV", Resume.SourceType.TXT, UserRole.USER, encrypted.ciphertext(), encrypted.nonce(), Instant.now(), "v1"));
        var capture = new CapturingClient();
        var service = new MatchTaskService(new ResumeLifecycleService(resumes, new ResumeCache.Noop(), new ResumeAuditRepository.InMemory()),
                new TestProfileService(userId, profileId), new MatchTaskRepository.InMemory(), capture,
                new AnalysisResultRepository.InMemory(), new CallbackReceiptRepository.InMemory(), new AnalysisEvidenceRepository.InMemory(), crypto);
        service.createTask(new CreateMatchTaskCommand(userId, resumeId, profileId,
                "Build reliable software with clear communication and practical testing.", "crlf-key-00000001"));
        assertThat(new String(java.util.Base64.getDecoder().decode(capture.job.document().contentBase64()), StandardCharsets.UTF_8))
                .isEqualTo("Java\nTesting");
        assertThat(capture.job.allowedEvidence()).extracting(PythonAnalysisClient.AllowedEvidence::sourceStart)
                .containsExactly(0, 5);
        assertThat(capture.job.allowedEvidence()).extracting(PythonAnalysisClient.AllowedEvidence::sourceEnd)
                .containsExactly(4, 12);
    }

    @Test
    void blankAndTrailingTxtLinesDoNotCreateZeroLengthAllowedEvidence() {
        var crypto = new com.resumethinking.platform.crypto.AesGcmCryptoService("MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
        var encrypted = crypto.encrypt("Java\n\nTesting\n");
        var resumes = new ResumeRepository.InMemory();
        resumes.save(new Resume(resumeId, userId, "CV", Resume.SourceType.TXT, UserRole.USER, encrypted.ciphertext(), encrypted.nonce(), Instant.now(), "v1"));
        var capture = new CapturingClient();
        var service = new MatchTaskService(new ResumeLifecycleService(resumes, new ResumeCache.Noop(), new ResumeAuditRepository.InMemory()),
                new TestProfileService(userId, profileId), new MatchTaskRepository.InMemory(), capture,
                new AnalysisResultRepository.InMemory(), new CallbackReceiptRepository.InMemory(), new AnalysisEvidenceRepository.InMemory(), crypto);
        service.createTask(new CreateMatchTaskCommand(userId, resumeId, profileId,
                "Build reliable software with clear communication and practical testing.", "blank-key-0000001"));
        assertThat(capture.job.allowedEvidence()).hasSize(2).allSatisfy(e -> assertThat(e.sourceStart()).isLessThan(e.sourceEnd()));
        assertThat(capture.job.allowedEvidence()).extracting(PythonAnalysisClient.AllowedEvidence::sourceStart).containsExactly(0, 6);
        assertThat(capture.job.allowedEvidence()).extracting(PythonAnalysisClient.AllowedEvidence::sourceEnd).containsExactly(4, 13);
    }

    @Test
    void unicodeAstralCharactersUsePythonCodePointOffsets() {
        var crypto = new com.resumethinking.platform.crypto.AesGcmCryptoService("MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
        var encrypted = crypto.encrypt("Java😀\nTesting");
        var resumes = new ResumeRepository.InMemory();
        resumes.save(new Resume(resumeId, userId, "CV", Resume.SourceType.TXT, UserRole.USER, encrypted.ciphertext(), encrypted.nonce(), Instant.now(), "v1"));
        var capture = new CapturingClient();
        var service = new MatchTaskService(new ResumeLifecycleService(resumes, new ResumeCache.Noop(), new ResumeAuditRepository.InMemory()),
                new TestProfileService(userId, profileId), new MatchTaskRepository.InMemory(), capture,
                new AnalysisResultRepository.InMemory(), new CallbackReceiptRepository.InMemory(), new AnalysisEvidenceRepository.InMemory(), crypto);
        service.createTask(new CreateMatchTaskCommand(userId, resumeId, profileId,
                "Build reliable software with clear communication and practical testing.", "unicode-key-00001"));
        assertThat(capture.job.allowedEvidence()).extracting(PythonAnalysisClient.AllowedEvidence::sourceStart).containsExactly(0, 6);
        assertThat(capture.job.allowedEvidence()).extracting(PythonAnalysisClient.AllowedEvidence::sourceEnd).containsExactly(5, 13);
    }

    @Test
    void forgedEvidenceExcerptIsRejectedEvenWhenRangeAndIdAreValid() {
        var crypto = new com.resumethinking.platform.crypto.AesGcmCryptoService("MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
        var encrypted = crypto.encrypt("Java\nTesting");
        var resumes = new ResumeRepository.InMemory();
        resumes.save(new Resume(resumeId, userId, "CV", Resume.SourceType.TXT, UserRole.USER, encrypted.ciphertext(), encrypted.nonce(), Instant.now(), "v1"));
        var service = new MatchTaskService(new ResumeLifecycleService(resumes, new ResumeCache.Noop(), new ResumeAuditRepository.InMemory()),
                null, new MatchTaskRepository.InMemory(), new PythonAnalysisClient.Noop(),
                new AnalysisResultRepository.InMemory(), new CallbackReceiptRepository.InMemory(), new AnalysisEvidenceRepository.InMemory(), crypto);
        var task = service.createTask(new CreateMatchTaskCommand(userId, resumeId, profileId,
                "Build reliable software with clear communication and practical testing.", "evidence-key-00001"));
        var evidenceId = service.evidenceForTask(task.id()).getFirst().getId();
        var requirement = new AnalysisCallbackRequest.RequirementMatch(UUID.randomUUID(), "Java", "MANDATORY", "SATISFIED", "EXACT", "SKILLS", .8,
                java.util.List.of(new AnalysisCallbackRequest.EvidenceReference(evidenceId, 0, 4, "Forged", .9)), "HIGH", null, "SUPPORTED_FACT");
        var result = new AnalysisCallbackRequest.AnalysisResultPayload(new AnalysisCallbackRequest.ScoreBreakdown(.8,.8,.8,.8,.8,.8), java.util.List.of(requirement), java.util.List.of());
        var callback = new AnalysisCallbackRequest(task.id(), 1, UUID.randomUUID(), task.callbackTokenForTests(), "", "SUCCEEDED", result, null, UUID.randomUUID()).withComputedPayloadHash();
        assertThat(service.acceptCallback(callback).code()).isEqualTo("MODEL_OUTPUT_INVALID");
    }

    @Test
    void rejectsCompositeScoreThatIsNotRoundedWeightedValue() {
        var resumes = new ResumeRepository.InMemory();
        resumes.save(Resume.active(resumeId, userId, "CV", Resume.SourceType.TXT, UserRole.USER, Instant.now(), 0L));
        var service = new MatchTaskService(new ResumeLifecycleService(resumes, new ResumeCache.Noop(), new ResumeAuditRepository.InMemory()),
                null, new MatchTaskRepository.InMemory(), new PythonAnalysisClient.Noop());
        var task = service.createTask(new CreateMatchTaskCommand(userId, resumeId, profileId,
                "Build reliable software with clear communication and practical testing.", "score-key-000001"));
        var score = new AnalysisCallbackRequest.ScoreBreakdown(.1111,.2222,.3333,.4444,.5555,.9999);
        var result = new AnalysisCallbackRequest.AnalysisResultPayload(score, java.util.List.of(), java.util.List.of());
        var callback = new AnalysisCallbackRequest(task.id(), 1, UUID.randomUUID(), task.callbackTokenForTests(), "", "SUCCEEDED", result, null, UUID.randomUUID()).withComputedPayloadHash();
        assertThat(service.acceptCallback(callback).code()).isEqualTo("MODEL_OUTPUT_INVALID");
    }

    @Test
    void suggestionRequirementMustBeReturnedByTheSameResult() {
        var resumes = new ResumeRepository.InMemory();
        resumes.save(Resume.active(resumeId, userId, "CV", Resume.SourceType.TXT, UserRole.USER, Instant.now(), 0L));
        var service = new MatchTaskService(new ResumeLifecycleService(resumes, new ResumeCache.Noop(), new ResumeAuditRepository.InMemory()),
                null, new MatchTaskRepository.InMemory(), new PythonAnalysisClient.Noop());
        var task = service.createTask(new CreateMatchTaskCommand(userId, resumeId, profileId,
                "Build reliable software with clear communication and practical testing.", "suggestion-owner-01"));

        UUID returnedRequirementId = UUID.randomUUID();
        var requirement = new AnalysisCallbackRequest.RequirementMatch(returnedRequirementId, "Java", "MANDATORY",
                "UNMET", "NO_MATCH", "SKILLS", 0.0,
                java.util.List.<AnalysisCallbackRequest.EvidenceReference>of(), "NONE", "gap", "NEEDS_USER_CONFIRMATION");
        var suggestion = new AnalysisCallbackRequest.Suggestion(UUID.randomUUID(), UUID.randomUUID(),
                "NEEDS_USER_CONFIRMATION", "Consider adding Java experience", java.util.List.of());
        var result = new AnalysisCallbackRequest.AnalysisResultPayload(
                new AnalysisCallbackRequest.ScoreBreakdown(0, 0, 0, 0, 0, 0),
                java.util.List.of(requirement), java.util.List.of(suggestion));
        var callback = new AnalysisCallbackRequest(task.id(), 1, UUID.randomUUID(), task.callbackTokenForTests(),
                "", "SUCCEEDED", result, null, UUID.randomUUID()).withComputedPayloadHash();

        assertThat(service.acceptCallback(callback).code()).isEqualTo("MODEL_OUTPUT_INVALID");
    }

    @Test
    void callbackRaceRecomputesHashBeforeAcceptingReplay() {
        var resumes = new ResumeRepository.InMemory();
        resumes.save(Resume.active(resumeId, userId, "CV", Resume.SourceType.TXT, UserRole.USER, Instant.now(), 0L));
        var receipts = new MutatingRaceReceipts();
        var service = new MatchTaskService(new ResumeLifecycleService(resumes, new ResumeCache.Noop(), new ResumeAuditRepository.InMemory()),
                null, new MatchTaskRepository.InMemory(), new PythonAnalysisClient.Noop(),
                new AnalysisResultRepository.InMemory(), receipts);
        var task = service.createTask(new CreateMatchTaskCommand(userId, resumeId, profileId,
                "Build reliable software with clear communication and practical testing.", "callback-race-hash-1"));

        UUID requirementId = UUID.randomUUID();
        var requirements = new ArrayList<>(java.util.List.of(new AnalysisCallbackRequest.RequirementMatch(requirementId,
                "Java", "MANDATORY", "UNMET", "NO_MATCH", "SKILLS", 0.0,
                java.util.List.<AnalysisCallbackRequest.EvidenceReference>of(), "NONE", "gap", "NEEDS_USER_CONFIRMATION")));
        var result = new AnalysisCallbackRequest.AnalysisResultPayload(
                new AnalysisCallbackRequest.ScoreBreakdown(0, 0, 0, 0, 0, 0), requirements,
                java.util.List.of());
        var callback = new AnalysisCallbackRequest(task.id(), 1, UUID.randomUUID(), task.callbackTokenForTests(),
                "", "SUCCEEDED", result, null, UUID.randomUUID()).withComputedPayloadHash();
        receipts.mutation = requirements::clear;

        assertThat(service.acceptCallback(callback).code()).isEqualTo("IDEMPOTENCY_CONFLICT");
    }


    private static final class CapturingClient extends PythonAnalysisClient {
        private InternalAnalysisJob job;
        CapturingClient() { super(URI.create("http://127.0.0.1:1")); }
        @Override public void dispatch(InternalAnalysisJob job) { this.job = job; }
    }

    private static final class MutatingRaceReceipts implements CallbackReceiptRepository {
        private CallbackReceipt receipt;
        private Runnable mutation;

        @Override public synchronized Optional<CallbackReceipt> findByCallbackId(UUID id) {
            return receipt == null ? Optional.empty() : Optional.of(receipt);
        }

        @Override public synchronized CallbackReceipt save(CallbackReceipt value) {
            receipt = value;
            return value;
        }

        @Override public synchronized CallbackReceipt saveAndFlush(CallbackReceipt value) {
            receipt = value;
            if (mutation != null) mutation.run();
            throw new DataIntegrityViolationException("duplicate callback");
        }
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
