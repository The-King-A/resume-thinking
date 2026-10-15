package com.resumethinking.platform.matching;

import com.resumethinking.platform.auth.UserRole;
import com.resumethinking.platform.profiles.LlmProfileService;
import com.resumethinking.platform.resumes.*;
import com.resumethinking.platform.TestIds;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.Set;
import java.util.ArrayList;
import java.util.Optional;
import java.io.ByteArrayOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MatchTaskServiceTest {
    private final String userId = TestIds.user();
    private final String resumeId = TestIds.resume();
    private final String profileId = TestIds.profile();

    @Test
    void v2CompletedTaskAndResultRemainReadableWhileAReplacementRevisionIsPending() {
        LegacyV2Fixture fixture = legacyV2Fixture("pending-v2-read-01");
        fixture.task().markSucceeded();
        fixture.results().save(new AnalysisResult(fixture.task().getId(), fixture.resume().getId(),
                fixture.task().getRevisionId(), fixture.task().getResumeVersion(),
                fixture.task().getJobDescriptionText(), null, java.util.List.of(), java.util.List.of(),
                fixture.now(), "SUCCEEDED", null));

        stageReplacement(fixture, "Updated CV");

        assertThat(fixture.task().getSubmissionFingerprint()).isNull();
        assertThat(fixture.service().getTask(fixture.task().getId(), userId, UserRole.USER))
                .isSameAs(fixture.task());
        assertThat(fixture.service().getResult(fixture.task().getId(), userId, UserRole.USER).revisionId())
                .isEqualTo(fixture.task().getRevisionId());
    }

    @Test
    void v2ProcessingTaskAcceptsCallbackWhileAReplacementRevisionIsPending() {
        LegacyV2Fixture fixture = legacyV2Fixture("pending-v2-callback-01");
        String evidenceId = fixture.task().getAllowedEvidence().iterator().next();
        var reference = new AnalysisCallbackRequest.EvidenceReference(evidenceId, 0, 13,
                "Java services", .95);
        var requirement = new AnalysisCallbackRequest.RequirementMatch("requirement901", "Reliable Java delivery",
                "MANDATORY", "SATISFIED", "EXACT", "SKILLS", .8, java.util.List.of(reference), "HIGH", null,
                "SUPPORTED_FACT");
        var result = new AnalysisCallbackRequest.AnalysisResultPayload(
                new AnalysisCallbackRequest.ScoreBreakdown(.8, .8, .8, .8, .8, .8),
                java.util.List.of(requirement), java.util.List.of());
        var callback = new AnalysisCallbackRequest(fixture.task().getId(), fixture.task().getAttempt(),
                fixture.task().getCallbackId(), fixture.task().callbackTokenForTests(), "", "SUCCEEDED",
                result, null, UUID.randomUUID()).withComputedPayloadHash();

        stageReplacement(fixture, "Updated CV");

        assertThat(fixture.task().getSubmissionFingerprint()).isNull();
        assertThat(fixture.service().acceptCallback(callback).code()).isEqualTo("ACCEPTED");
        assertThat(fixture.task().getState()).isEqualTo(MatchTask.State.SUCCEEDED);
    }

    @Test
    void historicalTaskWithoutRevisionStillFallsBackToResumeVersion() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        var resumes = new ResumeRepository.InMemory();
        Resume resume = Resume.active(resumeId, userId, "CV", Resume.SourceType.TXT, UserRole.USER, now, 0L);
        resumes.save(resume);
        var tasks = new MatchTaskRepository.InMemory();
        MatchTask task = new MatchTask(TestIds.task(), resumeId, profileId, userId, resume.getVersion(),
                "Build reliable software with clear communication and practical testing.",
                "historical-version-01", "token-token-token-token-token-token", Set.of(), now);
        tasks.save(task);
        var service = new MatchTaskService(new ResumeLifecycleService(resumes, new ResumeCache.Noop(),
                new ResumeAuditRepository.InMemory(), Clock.fixed(now, ZoneOffset.UTC)), null, tasks,
                new PythonAnalysisClient.Noop());

        assertThat(task.getRevisionId()).isNull();
        assertThat(service.getTask(task.getId(), userId, UserRole.USER)).isSameAs(task);

        ResumeRevision candidate = new ResumeRevision("revision999", resumeId, 1, "Updated CV", "updated cv",
                Resume.SourceType.TXT, "v1", new byte[]{1}, new byte[12], ResumeRevision.State.PENDING, now);
        resume.stageRevision(candidate, now);
        resumes.save(resume);

        assertThatThrownBy(() -> service.getTask(task.getId(), userId, UserRole.USER))
                .isInstanceOf(TaskGoneException.class);
    }

    @Test
    void v2TaskAndSuccessfulResultPersistTheEffectiveRevisionBinding() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        var resumes = new ResumeRepository.InMemory();
        var revisions = new ResumeRevisionRepository.InMemory();
        var crypto = new com.resumethinking.platform.crypto.AesGcmCryptoService(
                "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
        var ids = new com.resumethinking.platform.ids.InMemoryReadableIdGenerator();
        var lifecycle = new ResumeLifecycleService(resumes, new ResumeCache.Noop(),
                new ResumeAuditRepository.InMemory(), Clock.fixed(now, ZoneOffset.UTC),
                ResumeTaskBlocker.NOOP, ids, null, revisions);
        var encrypted = crypto.encryptBytes("Java services".getBytes(StandardCharsets.UTF_8));
        Resume resume = lifecycle.upload(userId, UserRole.USER, "CV", Resume.SourceType.TXT,
                encrypted.ciphertext(), encrypted.nonce());
        var results = new AnalysisResultRepository.InMemory();
        var evidence = new AnalysisEvidenceRepository.InMemory();
        var service = new MatchTaskService(lifecycle, null, new MatchTaskRepository.InMemory(),
                new PythonAnalysisClient.Noop(), results, new CallbackReceiptRepository.InMemory(), evidence,
                crypto, null, null, ids, revisions);

        MatchTask task = service.createTask(new CreateMatchTaskCommand(userId, resume.getId(), profileId,
                "Build reliable software with clear communication and practical testing.",
                "v2-revision-key-01"));
        String evidenceId = task.getAllowedEvidence().iterator().next();
        var reference = new AnalysisCallbackRequest.EvidenceReference(evidenceId, 0, 13,
                "Java services", .95);
        var requirement = new AnalysisCallbackRequest.RequirementMatch("requirement901", "Reliable Java delivery",
                "MANDATORY", "SATISFIED", "EXACT", "SKILLS", .8, java.util.List.of(reference), "HIGH", null,
                "SUPPORTED_FACT");
        var result = new AnalysisCallbackRequest.AnalysisResultPayload(
                new AnalysisCallbackRequest.ScoreBreakdown(.8, .8, .8, .8, .8, .8),
                java.util.List.of(requirement), java.util.List.of());
        var callback = new AnalysisCallbackRequest(task.getId(), task.getAttempt(), task.getCallbackId(),
                task.callbackTokenForTests(), "", "SUCCEEDED", result, null, UUID.randomUUID())
                .withComputedPayloadHash();

        assertThat(task.getRevisionId()).isEqualTo(resume.getEffectiveRevisionId()).isNotNull();
        assertThat(service.acceptCallback(callback).code()).isEqualTo("ACCEPTED");
        assertThat(results.findByTaskId(task.getId()).orElseThrow().revisionId())
                .isEqualTo(resume.getEffectiveRevisionId()).isNotNull();
    }

    @Test
    void compatibilityTaskConstructorsDoNotReuseOneCallbackId() {
        var first = new MatchTask("task901", resumeId, profileId, userId, 0L,
                "Build reliable software with clear communication and practical testing.",
                "compat-key-000001", "token-token-token-token-token-token", Set.of(), Instant.now());
        var second = new MatchTask("task902", resumeId, profileId, userId, 0L,
                "Build reliable software with clear communication and practical testing.",
                "compat-key-000002", "token-token-token-token-token-token", Set.of(), Instant.now());

        assertThat(first.callbackId()).isEqualTo("callback901");
        assertThat(second.callbackId()).isEqualTo("callback902");
        assertThat(first.callbackId()).isNotEqualTo(second.callbackId());
    }

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
    void createTaskUsesManagedInstanceReturnedByJpaSave() {
        var resumes = new ResumeRepository.InMemory();
        resumes.save(Resume.active(resumeId, userId, "CV", Resume.SourceType.TXT, UserRole.USER,
                Instant.parse("2026-01-01T00:00:00Z"), 0L));
        var tasks = new JpaMergeLikeTasks();
        var service = new MatchTaskService(new ResumeLifecycleService(resumes, new ResumeCache.Noop(), new ResumeAuditRepository.InMemory(),
                        Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC)),
                null, tasks, new PythonAnalysisClient.Noop());

        var task = service.createTask(new CreateMatchTaskCommand(userId, resumeId, profileId,
                "Build reliable software with clear communication and practical testing.", "jpa-merge-task-0001"));

        assertThat(task.state()).isEqualTo(MatchTask.State.PROCESSING);
        assertThat(tasks.saveCalls).isEqualTo(3);
    }
    @Test
    void dispatchUsesDecryptedResumeBytesAndBoundedEvidence() throws Exception {
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
        assertThat(new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules().writeValueAsString(capture.job))
                .contains("\"jobFamily\":\"JAVA_BACKEND\"");
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
    void pythonWorkerDispatchFailureUsesDedicatedPublicTaskFailureCode() {
        var crypto = new com.resumethinking.platform.crypto.AesGcmCryptoService("MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
        var encrypted = crypto.encrypt("Java\nTesting");
        var resumes = new ResumeRepository.InMemory();
        resumes.save(new Resume(resumeId, userId, "CV", Resume.SourceType.TXT, UserRole.USER,
                encrypted.ciphertext(), encrypted.nonce(), Instant.now(), "v1"));
        var tasks = new MatchTaskRepository.InMemory();
        var service = new MatchTaskService(new ResumeLifecycleService(resumes, new ResumeCache.Noop(), new ResumeAuditRepository.InMemory()),
                new TestProfileService(userId, profileId), tasks, new UnavailablePythonClient(),
                new AnalysisResultRepository.InMemory(), new CallbackReceiptRepository.InMemory(), new AnalysisEvidenceRepository.InMemory(), crypto);

        var task = service.createTask(new CreateMatchTaskCommand(userId, resumeId, profileId,
                "Build reliable software with clear communication and practical testing.", "python-unavailable-01"));

        assertThat(task.getState()).isEqualTo(MatchTask.State.FAILED);
        assertThat(task.getFailureCode()).isEqualTo("PYTHON_SERVICE_UNAVAILABLE");
    }

    @Test
    void pythonWorkerAuthenticationFailureUsesAConfigurationSpecificTaskFailureCode() {
        var crypto = new com.resumethinking.platform.crypto.AesGcmCryptoService("MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
        var encrypted = crypto.encrypt("Java\nTesting");
        var resumes = new ResumeRepository.InMemory();
        resumes.save(new Resume(resumeId, userId, "CV", Resume.SourceType.TXT, UserRole.USER,
                encrypted.ciphertext(), encrypted.nonce(), Instant.now(), "v1"));
        var service = new MatchTaskService(new ResumeLifecycleService(resumes, new ResumeCache.Noop(), new ResumeAuditRepository.InMemory()),
                new TestProfileService(userId, profileId), new MatchTaskRepository.InMemory(), new AuthenticationRejectedPythonClient(),
                new AnalysisResultRepository.InMemory(), new CallbackReceiptRepository.InMemory(), new AnalysisEvidenceRepository.InMemory(), crypto);

        var task = service.createTask(new CreateMatchTaskCommand(userId, resumeId, profileId,
                "Build reliable software with clear communication and practical testing.", "python-auth-failure-01"));

        assertThat(task.getState()).isEqualTo(MatchTask.State.FAILED);
        assertThat(task.getFailureCode()).isEqualTo("PYTHON_SERVICE_AUTHENTICATION_FAILED");
    }

    @Test
    void blockedTaskIsGoneFromTaskAndResultReads() {
        var resumes = new ResumeRepository.InMemory();
        var resume = Resume.active(resumeId, userId, "CV", Resume.SourceType.TXT, UserRole.USER, Instant.now(), 0L); resumes.save(resume);
        var task = new MatchTask(TestIds.task(), resumeId, profileId, userId, 0L, "Build reliable software with clear communication and practical testing.", "blocked-key-00001", "token-token-token-token-token-token", Set.of(TestIds.evidence()), Instant.now()); task.markBlocked();
        var repo = new MatchTaskRepository.InMemory(); repo.save(task);
        var service = new MatchTaskService(new ResumeLifecycleService(resumes, new ResumeCache.Noop(), new ResumeAuditRepository.InMemory()), null, repo, new PythonAnalysisClient.Noop());
        assertThatThrownBy(() -> service.getTask(task.id(), userId, UserRole.USER)).isInstanceOf(TaskGoneException.class);
    }

    @Test
    void softDeletedResumeHidesRevisionTaskAndResultOnlyWhileTheResumeIsHidden() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        var resumes = new ResumeRepository.InMemory();
        var resume = Resume.active(resumeId, userId, "CV", Resume.SourceType.TXT, UserRole.USER, now, 0L);
        resumes.save(resume);
        var tasks = new MatchTaskRepository.InMemory();
        var results = new AnalysisResultRepository.InMemory();
        var lifecycle = new ResumeLifecycleService(resumes, new ResumeCache.Noop(), new ResumeAuditRepository.InMemory(),
                Clock.fixed(now, ZoneOffset.UTC));
        var service = new MatchTaskService(lifecycle, null, tasks, new PythonAnalysisClient.Noop(), results);

        var task = service.createTask(new CreateMatchTaskCommand(userId, resumeId, profileId,
                "Build reliable software with clear communication and practical testing.", "soft-read-key-0001"));
        task.markSucceeded();
        results.save(new AnalysisResult(task.id(), resumeId, task.getResumeVersion(), task.getJobDescriptionText(),
                null, java.util.List.of(), java.util.List.of(), now, "SUCCEEDED", null));
        assertThat(service.getTask(task.id(), userId, UserRole.USER)).isSameAs(task);
        assertThat(service.getResult(task.id(), userId, UserRole.USER).taskId()).isEqualTo(task.id());

        lifecycle.softDelete(new DeleteResumeCommand(resumeId, userId, UserRole.USER,
                ResumeLifecycleService.CONFIRMATION, resume.getVersion()));
        assertThatThrownBy(() -> service.getTask(task.id(), userId, UserRole.USER)).isInstanceOf(TaskGoneException.class);
        assertThatThrownBy(() -> service.getResult(task.id(), userId, UserRole.USER)).isInstanceOf(TaskGoneException.class);
        assertThatThrownBy(() -> service.getTask(task.id(), TestIds.user(), UserRole.ADMIN)).isInstanceOf(TaskGoneException.class);

        lifecycle.recover(resumeId, userId, UserRole.USER, resume.getVersion());
        assertThat(service.getTask(task.id(), userId, UserRole.USER)).isSameAs(task);
        assertThat(service.getResult(task.id(), userId, UserRole.USER).taskId()).isEqualTo(task.id());

        var restoredTask = service.createTask(new CreateMatchTaskCommand(userId, resumeId, profileId,
                "Build reliable software with clear communication and practical testing.", "soft-read-key-0002"));
        restoredTask.markSucceeded();
        results.save(new AnalysisResult(restoredTask.id(), resumeId, restoredTask.getResumeVersion(),
                restoredTask.getJobDescriptionText(), null, java.util.List.of(), java.util.List.of(), now, "SUCCEEDED", null));
        assertThat(service.getTask(restoredTask.id(), userId, UserRole.USER)).isSameAs(restoredTask);
        assertThat(service.getResult(restoredTask.id(), userId, UserRole.USER).taskId()).isEqualTo(restoredTask.id());
    }

    @Test
    void cacheArchivedResumeHidesTaskAndResultUntilFreshTaskAfterRestore() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        var resumes = new ResumeRepository.InMemory();
        var resume = Resume.active(resumeId, userId, "CV", Resume.SourceType.TXT, UserRole.USER,
                now, 0L);
        resumes.save(resume);
        var tasks = new MatchTaskRepository.InMemory();
        var results = new AnalysisResultRepository.InMemory();
        var lifecycle = new ResumeLifecycleService(resumes, new ResumeCache.Noop(), new ResumeAuditRepository.InMemory(),
                Clock.fixed(now, ZoneOffset.UTC));
        var service = new MatchTaskService(lifecycle, null, tasks, new PythonAnalysisClient.Noop(), results);

        var task = service.createTask(new CreateMatchTaskCommand(userId, resumeId, profileId,
                "Build reliable software with clear communication and practical testing.", "archive-read-key-01"));
        task.markSucceeded();
        results.save(new AnalysisResult(task.id(), resumeId, task.getResumeVersion(), task.getJobDescriptionText(),
                null, java.util.List.of(), java.util.List.of(), now, "SUCCEEDED", null));
        lifecycle.archiveDue(now.plus(Duration.ofDays(8)));
        assertThatThrownBy(() -> service.getTask(task.id(), userId, UserRole.USER)).isInstanceOf(TaskGoneException.class);
        assertThatThrownBy(() -> service.getResult(task.id(), userId, UserRole.ADMIN)).isInstanceOf(TaskGoneException.class);

        lifecycle.recover(resumeId, userId, UserRole.USER, resume.getVersion());
        var restoredTask = service.createTask(new CreateMatchTaskCommand(userId, resumeId, profileId,
                "Build reliable software with clear communication and practical testing.", "archive-read-key-02"));
        restoredTask.markSucceeded();
        results.save(new AnalysisResult(restoredTask.id(), resumeId, restoredTask.getResumeVersion(),
                restoredTask.getJobDescriptionText(), null, java.util.List.of(), java.util.List.of(), now, "SUCCEEDED", null));
        assertThat(service.getTask(restoredTask.id(), userId, UserRole.USER)).isSameAs(restoredTask);
        assertThat(service.getResult(restoredTask.id(), userId, UserRole.ADMIN).taskId()).isEqualTo(restoredTask.id());
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
        var requirement = new AnalysisCallbackRequest.RequirementMatch(TestIds.requirement(), "Java", "MANDATORY", "SATISFIED", "EXACT", "SKILLS", .8,
                java.util.List.of(new AnalysisCallbackRequest.EvidenceReference(evidenceId, 0, 4, "Forged", .9)), "HIGH", null, "SUPPORTED_FACT");
        var result = new AnalysisCallbackRequest.AnalysisResultPayload(new AnalysisCallbackRequest.ScoreBreakdown(.8,.8,.8,.8,.8,.8), java.util.List.of(requirement), java.util.List.of());
        var callback = new AnalysisCallbackRequest(task.id(), 1, task.callbackId(), task.callbackTokenForTests(), "", "SUCCEEDED", result, null, UUID.randomUUID()).withComputedPayloadHash();
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
        var callback = new AnalysisCallbackRequest(task.id(), 1, task.callbackId(), task.callbackTokenForTests(), "", "SUCCEEDED", result, null, UUID.randomUUID()).withComputedPayloadHash();
        assertThat(service.acceptCallback(callback).code()).isEqualTo("MODEL_OUTPUT_INVALID");
        assertThat(task.getState()).isEqualTo(MatchTask.State.FAILED);
        assertThat(task.getFailureCode()).isEqualTo("MODEL_OUTPUT_INVALID");
    }

    @Test
    void acceptsPythonRedactedEvidenceExcerptAtOriginalOffsets() {
        var crypto = new com.resumethinking.platform.crypto.AesGcmCryptoService("MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
        var encrypted = crypto.encrypt("alice@example.com\nJava");
        var resumes = new ResumeRepository.InMemory();
        resumes.save(new Resume(resumeId, userId, "CV", Resume.SourceType.TXT, UserRole.USER, encrypted.ciphertext(), encrypted.nonce(), Instant.now(), "v1"));
        var evidenceRepository = new AnalysisEvidenceRepository.InMemory();
        var service = new MatchTaskService(new ResumeLifecycleService(resumes, new ResumeCache.Noop(), new ResumeAuditRepository.InMemory()),
                null, new MatchTaskRepository.InMemory(), new PythonAnalysisClient.Noop(),
                new AnalysisResultRepository.InMemory(), new CallbackReceiptRepository.InMemory(), evidenceRepository, crypto);
        var task = service.createTask(new CreateMatchTaskCommand(userId, resumeId, profileId,
                "Build reliable software with clear communication and practical testing.", "redacted-evidence-01"));
        var evidenceId = evidenceRepository.findByTaskId(task.id()).getFirst().getId();
        assertThat(evidenceRepository.findByTaskId(task.id()).getFirst().getSourceExcerpt())
                .isEqualTo("[REDACTED_EMAIL]")
                .doesNotContain("alice@example.com");
        var requirement = new AnalysisCallbackRequest.RequirementMatch(TestIds.requirement(), "Email", "PREFERRED", "SATISFIED", "EXACT", "SOFT_SKILLS", .8,
                java.util.List.of(new AnalysisCallbackRequest.EvidenceReference(evidenceId, 0, 17, "[REDACTED_EMAIL]", .9)), "HIGH", null, "SUPPORTED_FACT");
        var result = new AnalysisCallbackRequest.AnalysisResultPayload(new AnalysisCallbackRequest.ScoreBreakdown(.8,.8,.8,.8,.8,.8), java.util.List.of(requirement), java.util.List.of());
        var callback = new AnalysisCallbackRequest(task.id(), 1, task.callbackId(), task.callbackTokenForTests(), "", "SUCCEEDED", result, null, UUID.randomUUID()).withComputedPayloadHash();
        assertThat(service.acceptCallback(callback).code()).isEqualTo("ACCEPTED");
    }

    @Test
    void redactsExplicitNameEvidenceUsingThePythonCompatibleRule() {
        var crypto = new com.resumethinking.platform.crypto.AesGcmCryptoService("MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
        var encrypted = crypto.encrypt("姓名：张三\nJava");
        var resumes = new ResumeRepository.InMemory();
        resumes.save(new Resume(resumeId, userId, "CV", Resume.SourceType.TXT, UserRole.USER,
                encrypted.ciphertext(), encrypted.nonce(), Instant.now(), "v1"));
        var capture = new CapturingClient();
        var evidenceRepository = new AnalysisEvidenceRepository.InMemory();
        var service = new MatchTaskService(new ResumeLifecycleService(resumes, new ResumeCache.Noop(), new ResumeAuditRepository.InMemory()),
                new TestProfileService(userId, profileId), new MatchTaskRepository.InMemory(), capture,
                new AnalysisResultRepository.InMemory(), new CallbackReceiptRepository.InMemory(), evidenceRepository, crypto);

        var task = service.createTask(new CreateMatchTaskCommand(userId, resumeId, profileId,
                "Build reliable software with clear communication and practical testing.", "redacted-name-evidence-1"));

        assertThat(task.state()).isEqualTo(MatchTask.State.PROCESSING);
        assertThat(evidenceRepository.findByTaskId(task.id()).getFirst().getSourceExcerpt())
                .isEqualTo("姓名：[REDACTED_NAME]");
        assertThat(capture.job.allowedEvidence()).extracting(PythonAnalysisClient.AllowedEvidence::sourceStart)
                .containsExactly(0, 6);
        assertThat(capture.job.allowedEvidence()).extracting(PythonAnalysisClient.AllowedEvidence::sourceEnd)
                .containsExactly(5, 10);
    }

    @Test
    void parsesSmallDocxAndDispatches() throws Exception {
        var xml = ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
                + "<w:body><w:p><w:r><w:t>Java</w:t></w:r></w:p></w:body></w:document>")
                .getBytes(StandardCharsets.UTF_8);
        var crypto = new com.resumethinking.platform.crypto.AesGcmCryptoService("MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
        var encrypted = crypto.encryptBytes(zipDocument(xml));
        var resumes = new ResumeRepository.InMemory();
        resumes.save(new Resume(resumeId, userId, "CV", Resume.SourceType.DOCX, UserRole.USER,
                encrypted.ciphertext(), encrypted.nonce(), Instant.now(), "v1"));
        var capture = new CapturingClient();
        var service = new MatchTaskService(new ResumeLifecycleService(resumes, new ResumeCache.Noop(), new ResumeAuditRepository.InMemory()),
                new TestProfileService(userId, profileId), new MatchTaskRepository.InMemory(), capture,
                new AnalysisResultRepository.InMemory(), new CallbackReceiptRepository.InMemory(), new AnalysisEvidenceRepository.InMemory(), crypto);

        var task = service.createTask(new CreateMatchTaskCommand(userId, resumeId, profileId,
                "Build reliable software with clear communication and practical testing.", "docx-small-key-01"));

        assertThat(task.state()).isEqualTo(MatchTask.State.PROCESSING);
        assertThat(capture.job).isNotNull();
        assertThat(capture.job.allowedEvidence()).hasSize(1);
    }

    @Test
    void rejectsDocxWhenDocumentXmlExceedsUncompressedLimit() throws Exception {
        var oversizedText = "x".repeat(9 * 1024 * 1024);
        var xml = ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
                + "<w:body><w:p><w:r><w:t>" + oversizedText + "</w:t></w:r></w:p></w:body></w:document>")
                .getBytes(StandardCharsets.UTF_8);
        var docx = zipDocument(xml);
        var resumes = new ResumeRepository.InMemory();
        var crypto = new com.resumethinking.platform.crypto.AesGcmCryptoService("MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
        var encrypted = crypto.encryptBytes(docx);
        resumes.save(new Resume(resumeId, userId, "CV", Resume.SourceType.DOCX, UserRole.USER, encrypted.ciphertext(), encrypted.nonce(), Instant.now(), "v1"));
        var capture = new CapturingClient();
        var service = new MatchTaskService(new ResumeLifecycleService(resumes, new ResumeCache.Noop(), new ResumeAuditRepository.InMemory()),
                new TestProfileService(userId, profileId), new MatchTaskRepository.InMemory(), capture,
                new AnalysisResultRepository.InMemory(), new CallbackReceiptRepository.InMemory(), new AnalysisEvidenceRepository.InMemory(), crypto);

        var task = service.createTask(new CreateMatchTaskCommand(userId, resumeId, profileId,
                "Build reliable software with clear communication and practical testing.", "docx-limit-key-0001"));

        assertThat(task.state()).isEqualTo(MatchTask.State.FAILED);
        assertThat(task.getFailureCode()).isEqualTo("MODEL_OUTPUT_INVALID");
        assertThat(capture.job).isNull();
    }

    @Test
    void rejectsDocxWhenAnUncompressedZipEntryExceedsLimit() throws Exception {
        var xml = ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
                + "<w:body><w:p><w:r><w:t>Java</w:t></w:r></w:p></w:body></w:document>")
                .getBytes(StandardCharsets.UTF_8);
        var oversizedEntry = "x".repeat(9 * 1024 * 1024).getBytes(StandardCharsets.UTF_8);
        var docx = zipEntries(new ZipEntryData("word/huge.bin", oversizedEntry),
                new ZipEntryData("word/document.xml", xml));
        var resumes = new ResumeRepository.InMemory();
        var crypto = new com.resumethinking.platform.crypto.AesGcmCryptoService("MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
        var encrypted = crypto.encryptBytes(docx);
        resumes.save(new Resume(resumeId, userId, "CV", Resume.SourceType.DOCX, UserRole.USER,
                encrypted.ciphertext(), encrypted.nonce(), Instant.now(), "v1"));
        var capture = new CapturingClient();
        var service = new MatchTaskService(new ResumeLifecycleService(resumes, new ResumeCache.Noop(), new ResumeAuditRepository.InMemory()),
                new TestProfileService(userId, profileId), new MatchTaskRepository.InMemory(), capture,
                new AnalysisResultRepository.InMemory(), new CallbackReceiptRepository.InMemory(), new AnalysisEvidenceRepository.InMemory(), crypto);

        var task = service.createTask(new CreateMatchTaskCommand(userId, resumeId, profileId,
                "Build reliable software with clear communication and practical testing.", "docx-entry-limit-1"));

        assertThat(task.state()).isEqualTo(MatchTask.State.FAILED);
        assertThat(task.getFailureCode()).isEqualTo("MODEL_OUTPUT_INVALID");
        assertThat(capture.job).isNull();
    }

    @Test
    void suggestionRequirementMustBeReturnedByTheSameResult() {
        var resumes = new ResumeRepository.InMemory();
        resumes.save(Resume.active(resumeId, userId, "CV", Resume.SourceType.TXT, UserRole.USER, Instant.now(), 0L));
        var service = new MatchTaskService(new ResumeLifecycleService(resumes, new ResumeCache.Noop(), new ResumeAuditRepository.InMemory()),
                null, new MatchTaskRepository.InMemory(), new PythonAnalysisClient.Noop());
        var task = service.createTask(new CreateMatchTaskCommand(userId, resumeId, profileId,
                "Build reliable software with clear communication and practical testing.", "suggestion-owner-01"));

        String returnedRequirementId = TestIds.requirement();
        var requirement = new AnalysisCallbackRequest.RequirementMatch(returnedRequirementId, "Java", "MANDATORY",
                "UNMET", "NO_MATCH", "SKILLS", 0.0,
                java.util.List.<AnalysisCallbackRequest.EvidenceReference>of(), "NONE", "gap", "NEEDS_USER_CONFIRMATION");
        var suggestion = new AnalysisCallbackRequest.Suggestion(TestIds.suggestion(), TestIds.requirement(),
                "NEEDS_USER_CONFIRMATION", "Consider adding Java experience", java.util.List.of());
        var result = new AnalysisCallbackRequest.AnalysisResultPayload(
                new AnalysisCallbackRequest.ScoreBreakdown(0, 0, 0, 0, 0, 0),
                java.util.List.of(requirement), java.util.List.of(suggestion));
        var callback = new AnalysisCallbackRequest(task.id(), 1, task.callbackId(), task.callbackTokenForTests(),
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

        String requirementId = TestIds.requirement();
        var requirements = new ArrayList<>(java.util.List.of(new AnalysisCallbackRequest.RequirementMatch(requirementId,
                "Java", "MANDATORY", "UNMET", "NO_MATCH", "SKILLS", 0.0,
                java.util.List.<AnalysisCallbackRequest.EvidenceReference>of(), "NONE", "gap", "NEEDS_USER_CONFIRMATION")));
        var result = new AnalysisCallbackRequest.AnalysisResultPayload(
                new AnalysisCallbackRequest.ScoreBreakdown(0, 0, 0, 0, 0, 0), requirements,
                java.util.List.of());
        var callback = new AnalysisCallbackRequest(task.id(), 1, task.callbackId(), task.callbackTokenForTests(),
                "", "SUCCEEDED", result, null, UUID.randomUUID()).withComputedPayloadHash();
        receipts.mutation = requirements::clear;

        assertThat(service.acceptCallback(callback).code()).isEqualTo("IDEMPOTENCY_CONFLICT");
    }


    private static final class CapturingClient extends PythonAnalysisClient {
        private InternalAnalysisJob job;
        CapturingClient() { super(URI.create("http://127.0.0.1:1")); }
        @Override public void dispatch(InternalAnalysisJob job) { this.job = job; }
    }

    private static final class UnavailablePythonClient extends PythonAnalysisClient {
        UnavailablePythonClient() { super(URI.create("http://127.0.0.1:1")); }
        @Override public void dispatch(InternalAnalysisJob job) { throw new ServiceUnavailableException(); }
    }

    private static final class AuthenticationRejectedPythonClient extends PythonAnalysisClient {
        AuthenticationRejectedPythonClient() { super(URI.create("http://127.0.0.1:1")); }
        @Override public void dispatch(InternalAnalysisJob job) { throw new InternalAuthenticationException(); }
    }

    private LegacyV2Fixture legacyV2Fixture(String idempotencyKey) {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        var resumes = new ResumeRepository.InMemory();
        var revisions = new ResumeRevisionRepository.InMemory();
        var tasks = new MatchTaskRepository.InMemory();
        var results = new AnalysisResultRepository.InMemory();
        var evidence = new AnalysisEvidenceRepository.InMemory();
        var ids = new com.resumethinking.platform.ids.InMemoryReadableIdGenerator();
        var crypto = new com.resumethinking.platform.crypto.AesGcmCryptoService(
                "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
        var encrypted = crypto.encryptBytes("Java services".getBytes(StandardCharsets.UTF_8));
        var lifecycle = new ResumeLifecycleService(resumes, new ResumeCache.Noop(),
                new ResumeAuditRepository.InMemory(), Clock.fixed(now, ZoneOffset.UTC),
                ResumeTaskBlocker.NOOP, ids, null, revisions);
        Resume resume = lifecycle.upload(userId, UserRole.USER, "CV", Resume.SourceType.TXT,
                encrypted.ciphertext(), encrypted.nonce());
        var service = new MatchTaskService(lifecycle, null, tasks, new PythonAnalysisClient.Noop(), results,
                new CallbackReceiptRepository.InMemory(), evidence, crypto, null, null, ids, revisions);
        MatchTask task = service.createTask(new CreateMatchTaskCommand(userId, resume.getId(), profileId,
                "Build reliable software with clear communication and practical testing.", idempotencyKey));
        return new LegacyV2Fixture(now, resume, resumes, revisions, results, service, task);
    }

    private static void stageReplacement(LegacyV2Fixture fixture, String title) {
        ResumeRevision candidate = new ResumeRevision("revision999", fixture.resume().getId(), 2, title,
                ResumeLifecycleService.normalizeTitle(title), Resume.SourceType.TXT, "v1", new byte[]{1},
                new byte[12], ResumeRevision.State.PENDING, fixture.now());
        fixture.revisions().save(candidate);
        fixture.resume().stageRevision(candidate, fixture.now().plusSeconds(1));
        fixture.resumes().save(fixture.resume());
    }

    private record LegacyV2Fixture(Instant now, Resume resume, ResumeRepository.InMemory resumes,
                                   ResumeRevisionRepository.InMemory revisions,
                                   AnalysisResultRepository.InMemory results, MatchTaskService service,
                                   MatchTask task) {}

    /** Models Spring Data's merge contract for an assigned string identifier. */
    private static final class JpaMergeLikeTasks implements MatchTaskRepository {
        private MatchTask managed;
        private int saveCalls;

        @Override public MatchTask save(MatchTask task) { return saveAndFlush(task); }

        @Override public MatchTask saveAndFlush(MatchTask task) {
            saveCalls++;
            if (managed == null) {
                managed = new MatchTask(task.getId(), task.getCallbackId(), task.getResumeId(), task.getLlmProfileId(),
                        task.getCreatorId(), task.getResumeVersion(), task.getJobFamily(), task.getJobDescriptionText(),
                        task.getIdempotencyKey(), task.callbackTokenForTests(), task.getAllowedEvidence(), task.getCreatedAt());
                return managed;
            }
            if (task != managed) throw new OptimisticLockingFailureException("detached task merge used after version advance");
            return managed;
        }

        @Override public Optional<MatchTask> findById(String id) { return Optional.ofNullable(managed); }
        @Override public Optional<MatchTask> findByCreatorIdAndIdempotencyKey(String owner, String key) { return Optional.empty(); }
        @Override public Optional<MatchTask> findByIdForUpdate(String id) { return Optional.ofNullable(managed); }
        @Override public java.util.List<MatchTask> findByResumeIdAndStateInForUpdate(String id, java.util.Collection<MatchTask.State> states) { return java.util.List.of(); }
    }

    private static byte[] zipDocument(byte[] xml) throws Exception {
        return zipEntries(new ZipEntryData("word/document.xml", xml));
    }

    private static byte[] zipEntries(ZipEntryData... entries) throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            for (var entry : entries) {
                zip.putNextEntry(new ZipEntry(entry.name()));
                zip.write(entry.content());
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private record ZipEntryData(String name, byte[] content) {}

    private static final class MutatingRaceReceipts implements CallbackReceiptRepository {
        private CallbackReceipt receipt;
        private Runnable mutation;

        @Override public synchronized Optional<CallbackReceipt> findByCallbackId(String id) {
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
        private final String owner;
        private final String id;
        TestProfileService(String owner, String id) { super(new com.resumethinking.platform.auth.InMemoryRepositories.LlmProfileRepositoryStub(), new com.resumethinking.platform.crypto.AesGcmCryptoService("MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=")); this.owner = owner; this.id = id; }
        @Override public com.resumethinking.platform.profiles.DispatchLlmProfile decryptForDispatch(String actorId, String profileId) {
            if (!owner.equals(actorId) || !id.equals(profileId)) throw new com.resumethinking.platform.profiles.ResourceNotFoundException();
            return new com.resumethinking.platform.profiles.DispatchLlmProfile(URI.create("https://provider.example"), "model", "key");
        }
    }
}
