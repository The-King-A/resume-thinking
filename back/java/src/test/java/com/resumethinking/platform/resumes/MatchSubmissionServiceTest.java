package com.resumethinking.platform.resumes;

import com.resumethinking.platform.auth.UserRole;
import com.resumethinking.platform.crypto.AesGcmCryptoService;
import com.resumethinking.platform.ids.InMemoryReadableIdGenerator;
import com.resumethinking.platform.matching.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MatchSubmissionServiceTest {
    private static final String OWNER = "user901";
    private static final String OTHER = "user902";
    private static final String ADMIN = "user903";
    private static final String PROFILE = "profile901";
    private static final String JOB = "Build reliable Java services with clear tests and operational ownership.";
    private static final Instant NOW = Instant.parse("2026-09-02T00:00:00Z");

    private ResumeRepository.InMemory resumes;
    private ResumeRevisionRepository.InMemory revisions;
    private MatchTaskRepository.InMemory tasks;
    private AnalysisResultRepository.InMemory results;
    private AnalysisEvidenceRepository.InMemory evidence;
    private ResumeLifecycleService lifecycle;
    private MatchTaskService taskService;
    private MatchSubmissionService submissions;
    private AesGcmCryptoService crypto;

    @BeforeEach
    void setUp() {
        resumes = new ResumeRepository.InMemory();
        revisions = new ResumeRevisionRepository.InMemory();
        tasks = new MatchTaskRepository.InMemory();
        results = new AnalysisResultRepository.InMemory();
        evidence = new AnalysisEvidenceRepository.InMemory();
        var ids = new InMemoryReadableIdGenerator();
        crypto = new AesGcmCryptoService(java.util.Base64.getEncoder().encodeToString(new byte[32]));
        lifecycle = new ResumeLifecycleService(resumes, new ResumeCache.InMemory(Clock.fixed(NOW, ZoneOffset.UTC)),
                new ResumeAuditRepository.InMemory(), Clock.fixed(NOW, ZoneOffset.UTC),
                new MatchTaskResumeTaskBlocker(tasks), ids, null, revisions);
        taskService = new MatchTaskService(lifecycle, null, tasks, new PythonAnalysisClient.Noop(), results,
                new CallbackReceiptRepository.InMemory(), evidence, crypto,
                null, null, ids, revisions);
        submissions = new MatchSubmissionService(resumes, revisions, taskService, results, crypto, ids,
                Clock.fixed(NOW, ZoneOffset.UTC), lifecycle);
    }

    @Test
    void initialSubmissionIsInvisibleUntilValidRevisionBoundCallbackPublishesIt() {
        MatchTask task = initial("Initial Java CV", "initial-key-000001");

        assertThat(task.getPublicationState()).isEqualTo(MatchTask.PublicationState.PENDING);
        assertThat(lifecycle.listActive(OWNER, UserRole.USER, PageRequest.of(0, 20)).getContent()).isEmpty();
        assertThat(taskService.acceptV3Callback(success(task, "Java services")).code()).isEqualTo("ACCEPTED");

        Resume published = lifecycle.listActive(OWNER, UserRole.USER, PageRequest.of(0, 20)).getContent().getFirst();
        assertThat(published.getEffectiveRevisionId()).isEqualTo(task.getRevisionId());
        assertThat(published.getPendingRevisionId()).isNull();
        assertThat(task.getPublicationState()).isEqualTo(MatchTask.PublicationState.PUBLISHED);
        assertThat(submissions.getMatchContext(published.getId(), OWNER, UserRole.USER).latestSuccessfulTaskId())
                .isEqualTo(task.getId());
    }

    @Test
    void v3ListExcludesLegacyImmediateUploadWithoutAValidatedReport() {
        AesGcmCryptoService.EncryptedValue encrypted = crypto.encryptBytes("legacy".getBytes());
        lifecycle.upload(OWNER, UserRole.USER, "Legacy", Resume.SourceType.TXT,
                encrypted.ciphertext(), encrypted.nonce());

        assertThat(submissions.listEffective(OWNER, UserRole.USER, PageRequest.of(0, 20))).isEmpty();
    }

    @Test
    void effectivePaginationIsAppliedAfterReportlessLegacyRowsAreExcluded() {
        AesGcmCryptoService.EncryptedValue encrypted = crypto.encryptBytes("legacy".getBytes());
        lifecycle.upload(OWNER, UserRole.USER, "Legacy without report", Resume.SourceType.TXT,
                encrypted.ciphertext(), encrypted.nonce());
        MatchTask visible = initial("Validated report", "page-valid-key-01");
        taskService.acceptV3Callback(success(visible, "Java services"));

        var page = submissions.listEffective(OWNER, UserRole.USER, PageRequest.of(0, 1));

        assertThat(page.getContent()).extracting(value -> value.resume().getId())
                .containsExactly(visible.getResumeId());
        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getTotalPages()).isEqualTo(1);
    }

    @Test
    void initialIdempotencyRequiresTheSameFileTitleProfileAndJobInputs() {
        String key = "semantic-key-0001";
        MatchTask first = initial("Semantic CV", key);

        assertThat(submissions.submitInitial("Java services".getBytes(), "resume.txt", "Semantic CV", command(key)))
                .isSameAs(first);
        assertThatThrownBy(() -> submissions.submitInitial("Changed services".getBytes(), "resume.txt",
                "Semantic CV", command(key))).isInstanceOf(IdempotencyConflictException.class);
        assertThatThrownBy(() -> submissions.submitInitial("Java services".getBytes(), "resume.txt",
                "Changed title", command(key))).isInstanceOf(IdempotencyConflictException.class);
        assertThatThrownBy(() -> submissions.submitInitial("Java services".getBytes(), "resume.txt",
                "Semantic CV", new MatchSubmissionService.SubmissionCommand(OWNER, UserRole.USER, "profile902",
                        JobFamily.JAVA_BACKEND, JOB, key))).isInstanceOf(IdempotencyConflictException.class);
        assertThatThrownBy(() -> submissions.submitInitial("Java services".getBytes(), "resume.txt",
                "Semantic CV", new MatchSubmissionService.SubmissionCommand(OWNER, UserRole.USER, PROFILE,
                        JobFamily.JAVA_BACKEND, JOB + " Changed.", key)))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    void rematchIdempotencyBindsTheExpectedBaseRevision() {
        MatchTask initial = initial("Original", "target-base-key-01");
        taskService.acceptV3Callback(success(initial, "Java services"));
        Resume resume = resumes.findById(initial.getResumeId()).orElseThrow();
        String key = "target-rematch-001";
        MatchTask first = submissions.rematch(resume.getId(), resume.getEffectiveRevisionId(), null, null,
                null, command(key));

        assertThatThrownBy(() -> submissions.rematch(resume.getId(), "revision999", null, null,
                null, command(key))).isInstanceOf(IdempotencyConflictException.class);
        assertThat(submissions.rematch(resume.getId(), resume.getEffectiveRevisionId(), null, null,
                null, command(key))).isSameAs(first);
    }

    @Test
    void uniqueKeyRaceReReadsAndReturnsOnlyAnEquivalentCommittedSubmission() {
        String key = "cross-instance-key";
        MatchTask winner = initial("Race CV", key);
        MatchTaskService racingTasks = mock(MatchTaskService.class);
        when(racingTasks.findIdempotentSubmission(OWNER, key))
                .thenReturn(java.util.Optional.empty(), java.util.Optional.of(winner));
        when(racingTasks.createRevisionTask(any(), any(), any(), any()))
                .thenThrow(new DataIntegrityViolationException("uq_analysis_task_owner_key"));
        var contender = new MatchSubmissionService(new ResumeRepository.InMemory(),
                new ResumeRevisionRepository.InMemory(), racingTasks, new AnalysisResultRepository.InMemory(),
                crypto, new InMemoryReadableIdGenerator(), Clock.fixed(NOW, ZoneOffset.UTC), lifecycle);

        assertThat(contender.submitInitial("Java services".getBytes(), "resume.txt", "Race CV", command(key)))
                .isSameAs(winner);
    }

    @Test
    void serviceRejectsMalformedBusinessIdsAndExplicitBlankTitles() {
        assertThatThrownBy(() -> submissions.submitInitial("Java services".getBytes(), "resume.txt", "CV",
                new MatchSubmissionService.SubmissionCommand(OWNER, UserRole.USER, "bad-profile",
                        JobFamily.JAVA_BACKEND, JOB, "invalid-profile-01")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> submissions.submitInitial("Java services".getBytes(), "resume.txt", "   ",
                command("blank-title-key-1"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> submissions.rematch("bad-resume", "revision901", null, null, null,
                command("invalid-resume-001"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> submissions.rematch("resume901", "bad-revision", null, null, null,
                command("invalid-revision1"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void failedInitialCandidateRemainsOwnerRetryContextAndCanBeDeleted() {
        MatchTask task = initial("Pending Java CV", "pending-key-000001");
        Resume pending = resumes.findById(task.getResumeId()).orElseThrow();

        assertThat(taskService.acceptV3Callback(failure(task)).code()).isEqualTo("ACCEPTED");
        ResumeMatchContext context = submissions.getMatchContext(pending.getId(), OWNER, UserRole.USER);
        assertThat(context.effectiveRevisionId()).isNull();
        assertThat(context.pendingRevisionId()).isEqualTo(task.getRevisionId());
        assertThatThrownBy(() -> submissions.getMatchContext(pending.getId(), OTHER, UserRole.USER))
                .isInstanceOf(com.resumethinking.platform.profiles.ResourceNotFoundException.class);

        lifecycle.softDelete(new DeleteResumeCommand(pending.getId(), OWNER, UserRole.USER,
                ResumeLifecycleService.CONFIRMATION, pending.getVersion()));
        assertThat(taskService.acceptV3Callback(failure(task)).code()).isEqualTo("TASK_GONE");
    }

    @Test
    void changedReplacementFailurePreservesPublishedRevisionAndReport() {
        MatchTask initial = initial("Original", "original-key-0001");
        taskService.acceptV3Callback(success(initial, "Java services"));
        Resume resume = resumes.findById(initial.getResumeId()).orElseThrow();
        String oldRevision = resume.getEffectiveRevisionId();

        MatchTask replacement = submissions.rematch(resume.getId(), oldRevision,
                "New Java services".getBytes(java.nio.charset.StandardCharsets.UTF_8), "replacement.txt", "Renamed",
                command("replacement-key-01"));
        assertThat(replacement.getPublicationState()).isEqualTo(MatchTask.PublicationState.PENDING);
        assertThat(taskService.acceptV3Callback(failure(replacement)).code()).isEqualTo("ACCEPTED");

        assertThat(resume.getEffectiveRevisionId()).isEqualTo(oldRevision);
        assertThat(resume.getTitle()).isEqualTo("Original");
        assertThat(submissions.getMatchContext(resume.getId(), OWNER, UserRole.USER).latestSuccessfulTaskId())
                .isEqualTo(initial.getId());
    }

    @Test
    void unchangedRematchUsesEffectiveRevisionAndUpdatesLatestReportOnlyOnSuccess() {
        MatchTask initial = initial("Original", "unchanged-base-01");
        taskService.acceptV3Callback(success(initial, "Java services"));
        Resume resume = resumes.findById(initial.getResumeId()).orElseThrow();

        MatchTask failed = submissions.rematch(resume.getId(), resume.getEffectiveRevisionId(), null, null,
                " Original ", command("unchanged-fail-01"));
        assertThat(failed.getRevisionId()).isEqualTo(resume.getEffectiveRevisionId());
        assertThat(failed.getPublicationState()).isEqualTo(MatchTask.PublicationState.NOT_REQUESTED);
        taskService.acceptV3Callback(failure(failed));
        assertThat(submissions.getMatchContext(resume.getId(), OWNER, UserRole.USER).latestSuccessfulTaskId())
                .isEqualTo(initial.getId());

        MatchTask succeeded = submissions.rematch(resume.getId(), resume.getEffectiveRevisionId(), null, null,
                null, command("unchanged-pass-01"));
        taskService.acceptV3Callback(success(succeeded, "Java services"));
        assertThat(succeeded.getPublicationState()).isEqualTo(MatchTask.PublicationState.NOT_REQUESTED);
        assertThat(submissions.getMatchContext(resume.getId(), OWNER, UserRole.USER).latestSuccessfulTaskId())
                .isEqualTo(succeeded.getId());
    }

    @Test
    void newerChangedCandidateSupersedesEarlierCandidateAndRejectsItsCallback() {
        MatchTask initial = initial("Original", "supersede-base-1");
        taskService.acceptV3Callback(success(initial, "Java services"));
        Resume resume = resumes.findById(initial.getResumeId()).orElseThrow();
        MatchTask first = submissions.rematch(resume.getId(), resume.getEffectiveRevisionId(),
                "First Java edit".getBytes(), "first.txt", "First", command("supersede-first-1"));
        MatchTask second = submissions.rematch(resume.getId(), resume.getEffectiveRevisionId(),
                "Second Java edit".getBytes(), "second.txt", "Second", command("supersede-second1"));

        assertThat(first.getState()).isEqualTo(MatchTask.State.BLOCKED);
        assertThat(revisions.findById(first.getRevisionId()).orElseThrow().getState())
                .isEqualTo(ResumeRevision.State.SUPERSEDED);
        assertThat(resume.getPendingRevisionId()).isEqualTo(second.getRevisionId());
        assertThat(taskService.acceptV3Callback(failure(first)).code()).isEqualTo("TASK_GONE");
    }

    @Test
    void duplicateTitlePublicationKeepsValidatedResultReadableOnlyToOwnerAndAdmin() {
        MatchTask first = initial("Same Title", "duplicate-first-1");
        MatchTask second = initial(" same title ", "duplicate-second1");
        taskService.acceptV3Callback(success(first, "Java services"));

        assertThat(taskService.acceptV3Callback(success(second, "Java services")).code())
                .isEqualTo("DUPLICATE_RESOURCE");
        assertThat(second.getState()).isEqualTo(MatchTask.State.SUCCEEDED);
        assertThat(second.getPublicationState()).isEqualTo(MatchTask.PublicationState.REJECTED_DUPLICATE_TITLE);
        assertThat(results.countByTaskId(second.getId())).isEqualTo(1);
        assertThat(lifecycle.listActive(OWNER, UserRole.USER, PageRequest.of(0, 20))).hasSize(1);
        assertThatThrownBy(() -> taskService.getTask(second.getId(), OWNER, UserRole.USER))
                .isInstanceOf(DuplicateResumeTitleException.class);
        assertThat(taskService.getResult(second.getId(), OWNER, UserRole.USER).taskId())
                .isEqualTo(second.getId());
        assertThat(taskService.getResult(second.getId(), ADMIN, UserRole.ADMIN).taskId())
                .isEqualTo(second.getId());
        assertThatThrownBy(() -> taskService.getResult(second.getId(), OTHER, UserRole.USER))
                .isInstanceOf(com.resumethinking.platform.profiles.ResourceNotFoundException.class);
    }

    @Test
    void taskAndResultReadsFollowLogicalResumeOwnershipRatherThanTaskCreator() {
        AesGcmCryptoService.EncryptedValue encrypted = crypto.encryptBytes("Java services".getBytes());
        Resume resume = lifecycle.upload(OWNER, UserRole.USER, "Owner CV", Resume.SourceType.TXT,
                encrypted.ciphertext(), encrypted.nonce());
        MatchTask adminTask = taskService.createTask(new CreateMatchTaskCommand(ADMIN, resume.getId(), PROFILE,
                JobFamily.JAVA_BACKEND, JOB, "admin-rematch-key1", UserRole.ADMIN));

        assertThat(taskService.getTask(adminTask.getId(), OWNER, UserRole.USER)).isSameAs(adminTask);
        assertThatThrownBy(() -> taskService.getResult(adminTask.getId(), OWNER, UserRole.USER))
                .isInstanceOf(TaskNotReadyException.class);
        assertThatThrownBy(() -> taskService.getTask(adminTask.getId(), OTHER, UserRole.USER))
                .isInstanceOf(com.resumethinking.platform.profiles.ResourceNotFoundException.class);
        assertThatThrownBy(() -> taskService.getResult(adminTask.getId(), OTHER, UserRole.USER))
                .isInstanceOf(com.resumethinking.platform.profiles.ResourceNotFoundException.class);
    }

    @Test
    void v3CallbackRejectsMalformedRequirementIdsWithoutPersistingAResult() {
        MatchTask task = initial("Requirement IDs", "requirement-id-key");
        V3AnalysisCallbackRequest callback = success(task, "Java services");
        var original = callback.result().requirements().getFirst();
        var malformed = new AnalysisCallbackRequest.RequirementMatch("requirement9", original.jobRequirementText(),
                original.requirementType(), original.matchStatus(), original.matchType(), original.component(),
                original.componentScore(), original.evidence(), original.evidenceStrength(), original.gap(),
                original.suggestionState());
        var result = new AnalysisCallbackRequest.AnalysisResultPayload(callback.result().score(), List.of(malformed),
                List.of());

        assertThat(taskService.acceptV3Callback(withResult(callback, result)).code())
                .isEqualTo("MODEL_OUTPUT_INVALID");
        assertThat(results.countByTaskId(task.getId())).isZero();
    }

    @Test
    void v3CallbackRejectsMalformedEvidenceIdsEvenWhenTheEvidenceExists() {
        MatchTask task = initial("Evidence IDs", "evidence-id-key-01");
        evidence.save(new AnalysisEvidence("proof901", task.getId(), "TXT", "line:1", 0, 13,
                "Java services"));
        V3AnalysisCallbackRequest callback = success(task, "Java services");
        var original = callback.result().requirements().getFirst();
        var reference = new AnalysisCallbackRequest.EvidenceReference("proof901", 0, 13,
                "Java services", .95);
        var malformed = new AnalysisCallbackRequest.RequirementMatch(original.requirementId(),
                original.jobRequirementText(), original.requirementType(), original.matchStatus(), original.matchType(),
                original.component(), original.componentScore(), List.of(reference), original.evidenceStrength(),
                original.gap(), original.suggestionState());
        var result = new AnalysisCallbackRequest.AnalysisResultPayload(callback.result().score(), List.of(malformed),
                List.of());

        assertThat(taskService.acceptV3Callback(withResult(callback, result)).code())
                .isEqualTo("MODEL_OUTPUT_INVALID");
        assertThat(results.countByTaskId(task.getId())).isZero();
    }

    @Test
    void v3CallbackRejectsMalformedAndDuplicateSuggestionIds() {
        MatchTask malformedTask = initial("Suggestion IDs A", "suggestion-id-key1");
        V3AnalysisCallbackRequest malformedCallback = success(malformedTask, "Java services");
        var requirement = malformedCallback.result().requirements().getFirst();
        var malformed = new AnalysisCallbackRequest.Suggestion("suggestion9", requirement.requirementId(),
                "SUPPORTED_FACT", "Emphasize verified Java delivery.",
                List.of(malformedTask.getAllowedEvidence().iterator().next()));
        var malformedResult = new AnalysisCallbackRequest.AnalysisResultPayload(malformedCallback.result().score(),
                List.of(requirement), List.of(malformed));

        assertThat(taskService.acceptV3Callback(withResult(malformedCallback, malformedResult)).code())
                .isEqualTo("MODEL_OUTPUT_INVALID");
        assertThat(results.countByTaskId(malformedTask.getId())).isZero();

        MatchTask duplicateTask = initial("Suggestion IDs B", "suggestion-id-key2");
        V3AnalysisCallbackRequest duplicateCallback = success(duplicateTask, "Java services");
        var duplicateRequirement = duplicateCallback.result().requirements().getFirst();
        var first = new AnalysisCallbackRequest.Suggestion("suggestion901", duplicateRequirement.requirementId(),
                "SUPPORTED_FACT", "Emphasize verified Java delivery.",
                List.of(duplicateTask.getAllowedEvidence().iterator().next()));
        var second = new AnalysisCallbackRequest.Suggestion("suggestion901", duplicateRequirement.requirementId(),
                "SUPPORTED_FACT", "Keep the claim evidence based.",
                List.of(duplicateTask.getAllowedEvidence().iterator().next()));
        var duplicateResult = new AnalysisCallbackRequest.AnalysisResultPayload(duplicateCallback.result().score(),
                List.of(duplicateRequirement), List.of(first, second));

        assertThat(taskService.acceptV3Callback(withResult(duplicateCallback, duplicateResult)).code())
                .isEqualTo("MODEL_OUTPUT_INVALID");
        assertThat(results.countByTaskId(duplicateTask.getId())).isZero();
    }

    @Test
    void ownerAndAdministratorCanReadContextButOtherOwnerCannot() {
        MatchTask task = initial("Private", "isolation-key-001");
        assertThat(submissions.getMatchContext(task.getResumeId(), OWNER, UserRole.USER).pendingRevisionId())
                .isEqualTo(task.getRevisionId());
        assertThat(submissions.getMatchContext(task.getResumeId(), ADMIN, UserRole.ADMIN).pendingRevisionId())
                .isEqualTo(task.getRevisionId());
        assertThatThrownBy(() -> submissions.getMatchContext(task.getResumeId(), OTHER, UserRole.USER))
                .isInstanceOf(com.resumethinking.platform.profiles.ResourceNotFoundException.class);
    }

    @Test
    void revisionOnlyCallbackMutationIsRejectedWithoutPublishing() {
        MatchTask task = initial("Hash Bound", "hash-bound-key-01");
        V3AnalysisCallbackRequest valid = success(task, "Java services");
        V3AnalysisCallbackRequest mutated = new V3AnalysisCallbackRequest(valid.taskId(), "revision999",
                valid.attempt(), valid.callbackId(), valid.callbackToken(), valid.payloadHash(), valid.outcome(),
                valid.result(), valid.errorCode(), valid.correlationId());

        assertThat(taskService.acceptV3Callback(mutated).code()).isEqualTo("VALIDATION_ERROR");
        assertThat(resumes.findById(task.getResumeId()).orElseThrow().getEffectiveRevisionId()).isNull();
    }

    private MatchTask initial(String title, String key) {
        return submissions.submitInitial("Java services".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                "resume.txt", title, command(key));
    }

    private MatchSubmissionService.SubmissionCommand command(String key) {
        return new MatchSubmissionService.SubmissionCommand(OWNER, UserRole.USER, PROFILE,
                JobFamily.JAVA_BACKEND, JOB, key);
    }

    private static V3AnalysisCallbackRequest failure(MatchTask task) {
        return new V3AnalysisCallbackRequest(task.getId(), task.getRevisionId(), task.getAttempt(),
                task.getCallbackId(), task.callbackTokenForTests(), "", "FAILED", null,
                "MODEL_UNAVAILABLE", UUID.randomUUID()).withComputedPayloadHash();
    }

    private static V3AnalysisCallbackRequest success(MatchTask task, String source) {
        String evidenceId = task.getAllowedEvidence().iterator().next();
        var score = new AnalysisCallbackRequest.ScoreBreakdown(.9, .8, .7, .6, .7, .795);
        var evidence = new AnalysisCallbackRequest.EvidenceReference(evidenceId, 0,
                source.codePointCount(0, source.length()), source, .95);
        var requirement = new AnalysisCallbackRequest.RequirementMatch("requirement901", "Reliable Java delivery",
                "MANDATORY", "SATISFIED", "EXACT", "SKILLS", .9, List.of(evidence), "HIGH", null,
                "SUPPORTED_FACT");
        var suggestion = new AnalysisCallbackRequest.Suggestion("suggestion901", "requirement901",
                "SUPPORTED_FACT", "Emphasize verified Java delivery.", List.of(evidenceId));
        var result = new AnalysisCallbackRequest.AnalysisResultPayload(score, List.of(requirement), List.of(suggestion));
        return new V3AnalysisCallbackRequest(task.getId(), task.getRevisionId(), task.getAttempt(),
                task.getCallbackId(), task.callbackTokenForTests(), "", "SUCCEEDED", result, null,
                UUID.randomUUID()).withComputedPayloadHash();
    }

    private static V3AnalysisCallbackRequest withResult(V3AnalysisCallbackRequest callback,
                                                         AnalysisCallbackRequest.AnalysisResultPayload result) {
        return new V3AnalysisCallbackRequest(callback.taskId(), callback.revisionId(), callback.attempt(),
                callback.callbackId(), callback.callbackToken(), "", callback.outcome(), result,
                callback.errorCode(), callback.correlationId()).withComputedPayloadHash();
    }
}
