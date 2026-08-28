package com.resumethinking.platform.matching;

import com.resumethinking.platform.auth.UserRole;
import com.resumethinking.platform.resumes.*;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

class InternalAnalysisCallbackControllerTest {
    @Test
    void callbackAfterArchiveReturnsTaskGoneAndDoesNotPersistResult() {
        UUID owner = UUID.randomUUID();
        UUID resumeId = UUID.randomUUID();
        ResumeRepository resumes = new ResumeRepository.InMemory();
        Resume resume = Resume.active(resumeId, owner, "CV", Resume.SourceType.TXT, UserRole.USER,
                Instant.parse("2026-01-01T00:00:00Z"), 0L);
        resumes.save(resume);
        var taskRepo = new MatchTaskRepository.InMemory();
        var lifecycle = new ResumeLifecycleService(resumes, new ResumeCache.InMemory(), new ResumeAuditRepository.InMemory(),
                Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC),
                new MatchTaskResumeTaskBlocker(taskRepo));
        var resultRepo = new AnalysisResultRepository.InMemory();
        var service = new MatchTaskService(lifecycle, null, taskRepo, new PythonAnalysisClient.Noop(), resultRepo);
        var task = service.createTask(new CreateMatchTaskCommand(owner, resumeId, UUID.randomUUID(),
                "Build reliable software with clear communication and practical testing.", "archive-key-0000001"));
        task.markProcessing();
        lifecycle.archiveDue(Instant.parse("2026-01-08T00:00:00Z"));

        assertThat(task.getState()).isEqualTo(MatchTask.State.BLOCKED);

        var response = new InternalAnalysisCallbackController(service).accept(new AnalysisCallbackRequest(
                task.id(), 1, UUID.randomUUID(), task.callbackTokenForTests(), "",
                "FAILED", null, "MODEL_UNAVAILABLE", UUID.randomUUID()).withComputedPayloadHash());

        assertThat(response.code()).isEqualTo("TASK_GONE");
        assertThat(resultRepo.countByTaskId(task.id())).isZero();
    }

    @Test
    void callbackAfterSoftDeleteIsBlockedImmediatelyAndCannotPersistResult() {
        UUID owner = UUID.randomUUID();
        UUID resumeId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-01-01T00:00:00Z");
        ResumeRepository resumes = new ResumeRepository.InMemory();
        Resume resume = Resume.active(resumeId, owner, "CV", Resume.SourceType.TXT, UserRole.USER,
                createdAt, 0L);
        resumes.save(resume);
        var taskRepo = new MatchTaskRepository.InMemory();
        var lifecycle = new ResumeLifecycleService(resumes, new ResumeCache.InMemory(), new ResumeAuditRepository.InMemory(),
                Clock.fixed(createdAt, ZoneOffset.UTC), new MatchTaskResumeTaskBlocker(taskRepo));
        var resultRepo = new AnalysisResultRepository.InMemory();
        var service = new MatchTaskService(lifecycle, null, taskRepo, new PythonAnalysisClient.Noop(), resultRepo);
        var task = service.createTask(new CreateMatchTaskCommand(owner, resumeId, UUID.randomUUID(),
                "Build reliable software with clear communication and practical testing.", "delete-key-0000001"));

        lifecycle.softDelete(new DeleteResumeCommand(resumeId, owner, UserRole.USER,
                ResumeLifecycleService.CONFIRMATION, resume.getVersion()));

        assertThat(task.getState()).isEqualTo(MatchTask.State.BLOCKED);
        var response = service.acceptCallback(new AnalysisCallbackRequest(
                task.id(), 1, UUID.randomUUID(), task.callbackTokenForTests(), "",
                "FAILED", null, "MODEL_UNAVAILABLE", UUID.randomUUID()).withComputedPayloadHash());

        assertThat(response.code()).isEqualTo("TASK_GONE");
        assertThat(resultRepo.countByTaskId(task.id())).isZero();
    }

    @Test
    void invalidTokenCannotBlockAnInFlightTaskEvenWhenResumeIsAlreadyInactive() {
        UUID owner = UUID.randomUUID();
        UUID resumeId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-01-01T00:00:00Z");
        ResumeRepository resumes = new ResumeRepository.InMemory();
        Resume resume = Resume.active(resumeId, owner, "CV", Resume.SourceType.TXT, UserRole.USER,
                createdAt, 0L);
        resumes.save(resume);
        var taskRepo = new MatchTaskRepository.InMemory();
        var lifecycle = new ResumeLifecycleService(resumes, new ResumeCache.Noop(), new ResumeAuditRepository.InMemory(),
                Clock.fixed(createdAt, ZoneOffset.UTC));
        var service = new MatchTaskService(lifecycle, null, taskRepo, new PythonAnalysisClient.Noop(),
                new AnalysisResultRepository.InMemory());
        var task = service.createTask(new CreateMatchTaskCommand(owner, resumeId, UUID.randomUUID(),
                "Build reliable software with clear communication and practical testing.", "invalid-token-key-01"));

        lifecycle.softDelete(new DeleteResumeCommand(resumeId, owner, UserRole.USER,
                ResumeLifecycleService.CONFIRMATION, resume.getVersion()));
        assertThat(task.getState()).isEqualTo(MatchTask.State.PROCESSING);

        var response = service.acceptCallback(new AnalysisCallbackRequest(
                task.id(), 1, UUID.randomUUID(), "x".repeat(32), "", "FAILED", null,
                "MODEL_UNAVAILABLE", UUID.randomUUID()).withComputedPayloadHash());

        assertThat(response.code()).isEqualTo("TASK_GONE");
        assertThat(task.getState()).isEqualTo(MatchTask.State.PROCESSING);
    }

    @Test
    void callbackRaceWithInvalidTokenCannotBlockTaskDuringReplayResolution() {
        UUID owner = UUID.randomUUID();
        UUID resumeId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-01-01T00:00:00Z");
        ResumeRepository resumes = new ResumeRepository.InMemory();
        Resume resume = Resume.active(resumeId, owner, "CV", Resume.SourceType.TXT, UserRole.USER,
                createdAt, 0L);
        resumes.save(resume);
        var taskRepo = new SwitchingTaskRepository();
        var receipts = new ArchivingRaceReceipts(resume);
        var lifecycle = new ResumeLifecycleService(resumes, new ResumeCache.Noop(), new ResumeAuditRepository.InMemory(),
                Clock.fixed(createdAt, ZoneOffset.UTC));
        var service = new MatchTaskService(lifecycle, null, taskRepo, new PythonAnalysisClient.Noop(),
                new AnalysisResultRepository.InMemory(), receipts);
        var task = service.createTask(new CreateMatchTaskCommand(owner, resumeId, UUID.randomUUID(),
                "Build reliable software with clear communication and practical testing.", "race-token-key-01"));
        var replacement = new MatchTask(task.id(), task.resumeId(), task.llmProfileId(), task.creatorId(),
                task.resumeVersion(), task.jobFamily(), task.jobDescriptionText(), task.getIdempotencyKey(),
                "replacement-token-which-is-invalid-000000", task.getAllowedEvidence(), createdAt);
        replacement.markProcessing();
        taskRepo.replacement = replacement;

        var callback = new AnalysisCallbackRequest(task.id(), 1, UUID.randomUUID(), task.callbackTokenForTests(),
                "", "FAILED", null, "MODEL_UNAVAILABLE", UUID.randomUUID()).withComputedPayloadHash();

        assertThat(service.acceptCallback(callback).code()).isEqualTo("TASK_GONE");
        assertThat(replacement.getState()).isEqualTo(MatchTask.State.PROCESSING);
    }

    @Test
    void duplicateCallbackIsAcceptedReplayAndChangedPayloadConflicts() {
        UUID owner = UUID.randomUUID(); UUID resumeId = UUID.randomUUID();
        ResumeRepository resumes = new ResumeRepository.InMemory();
        resumes.save(Resume.active(resumeId, owner, "CV", Resume.SourceType.TXT, UserRole.USER, Instant.now(), 0L));
        var lifecycle = new ResumeLifecycleService(resumes, new ResumeCache.Noop(), new ResumeAuditRepository.InMemory());
        var taskRepo = new MatchTaskRepository.InMemory();
        var service = new MatchTaskService(lifecycle, null, taskRepo, new PythonAnalysisClient.Noop());
        var task = service.createTask(new CreateMatchTaskCommand(owner, resumeId, UUID.randomUUID(),
                "Build reliable software with clear communication and practical testing.", "callback-key-00001"));
        UUID callbackId = UUID.randomUUID();
        var first = new AnalysisCallbackRequest(task.id(), 1, callbackId, task.callbackTokenForTests(), "", "FAILED", null, "MODEL_UNAVAILABLE", UUID.randomUUID()).withComputedPayloadHash();
        assertThat(service.acceptCallback(first).code()).isEqualTo("ACCEPTED");
        assertThat(service.acceptCallback(first).code()).isEqualTo("ACCEPTED_REPLAY");
        var conflict = new AnalysisCallbackRequest(task.id(), 1, callbackId, task.callbackTokenForTests(), "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc", "FAILED", null, "MODEL_UNAVAILABLE", UUID.randomUUID());
        assertThat(service.acceptCallback(conflict).code()).isEqualTo("IDEMPOTENCY_CONFLICT");
    }

    @Test
    void staleAttemptIsRejectedWithoutWritingResult() {
        UUID owner = UUID.randomUUID(); UUID resumeId = UUID.randomUUID();
        ResumeRepository resumes = new ResumeRepository.InMemory();
        resumes.save(Resume.active(resumeId, owner, "CV", Resume.SourceType.TXT, UserRole.USER, Instant.now(), 0L));
        var lifecycle = new ResumeLifecycleService(resumes, new ResumeCache.Noop(), new ResumeAuditRepository.InMemory());
        var taskRepo = new MatchTaskRepository.InMemory(); var resultRepo = new AnalysisResultRepository.InMemory();
        var service = new MatchTaskService(lifecycle, null, taskRepo, new PythonAnalysisClient.Noop(), resultRepo);
        var task = service.createTask(new CreateMatchTaskCommand(owner, resumeId, UUID.randomUUID(),
                "Build reliable software with clear communication and practical testing.", "stale-key-000001"));
        var stale = new AnalysisCallbackRequest(task.id(), 0, UUID.randomUUID(), task.callbackTokenForTests(), "", "FAILED", null, "MODEL_UNAVAILABLE", UUID.randomUUID()).withComputedPayloadHash();
        assertThat(service.acceptCallback(stale).code()).isEqualTo("VALIDATION_ERROR");
        assertThat(resultRepo.countByTaskId(task.id())).isZero();
    }

    @Test
    void callbackIdCannotBeReplayedForAnotherTaskOrWithAnotherToken() {
        UUID owner = UUID.randomUUID(); UUID resumeOne = UUID.randomUUID(); UUID resumeTwo = UUID.randomUUID();
        ResumeRepository resumes = new ResumeRepository.InMemory();
        resumes.save(Resume.active(resumeOne, owner, "one", Resume.SourceType.TXT, UserRole.USER, Instant.now(), 0L));
        resumes.save(Resume.active(resumeTwo, owner, "two", Resume.SourceType.TXT, UserRole.USER, Instant.now(), 0L));
        var lifecycle = new ResumeLifecycleService(resumes, new ResumeCache.Noop(), new ResumeAuditRepository.InMemory());
        var taskRepo = new MatchTaskRepository.InMemory(); var receipts = new CallbackReceiptRepository.InMemory();
        var service = new MatchTaskService(lifecycle, null, taskRepo, new PythonAnalysisClient.Noop(), new AnalysisResultRepository.InMemory(), receipts);
        var firstTask = service.createTask(new CreateMatchTaskCommand(owner, resumeOne, UUID.randomUUID(),
                "Build reliable software with clear communication and practical testing.", "cross-task-key-001"));
        var secondTask = service.createTask(new CreateMatchTaskCommand(owner, resumeTwo, UUID.randomUUID(),
                "Build reliable software with clear communication and practical testing.", "cross-task-key-002"));
        UUID callbackId = UUID.randomUUID();
        var first = new AnalysisCallbackRequest(firstTask.id(), 1, callbackId, firstTask.callbackTokenForTests(), "", "FAILED", null,
                "MODEL_UNAVAILABLE", UUID.randomUUID()).withComputedPayloadHash();
        assertThat(service.acceptCallback(first).code()).isEqualTo("ACCEPTED");

        var wrongToken = new AnalysisCallbackRequest(firstTask.id(), 1, callbackId, "x".repeat(32), first.payloadHash(), "FAILED", null,
                "MODEL_UNAVAILABLE", first.correlationId());
        assertThat(service.acceptCallback(wrongToken).code()).isEqualTo("TASK_GONE");

        var reusedForOtherTask = new AnalysisCallbackRequest(secondTask.id(), 1, callbackId, secondTask.callbackTokenForTests(), first.payloadHash(),
                "FAILED", null, "MODEL_UNAVAILABLE", first.correlationId());
        assertThat(service.acceptCallback(reusedForOtherTask).code()).isEqualTo("IDEMPOTENCY_CONFLICT");
    }

    private static final class SwitchingTaskRepository implements MatchTaskRepository {
        private final MatchTaskRepository.InMemory delegate = new MatchTaskRepository.InMemory();
        private MatchTask replacement;
        private int lockCalls;

        @Override public synchronized MatchTask save(MatchTask task) { return delegate.save(task); }
        @Override public synchronized MatchTask saveAndFlush(MatchTask task) { return delegate.saveAndFlush(task); }
        @Override public synchronized Optional<MatchTask> findById(UUID id) { return delegate.findById(id); }
        @Override public synchronized Optional<MatchTask> findByCreatorIdAndIdempotencyKey(UUID owner, String key) {
            return delegate.findByCreatorIdAndIdempotencyKey(owner, key);
        }
        @Override public synchronized Optional<MatchTask> findByIdForUpdate(UUID id) { return delegate.findByIdForUpdate(id); }
        @Override public synchronized List<MatchTask> findByResumeIdAndStateInForUpdate(UUID resumeId, Collection<MatchTask.State> states) {
            return delegate.findByResumeIdAndStateInForUpdate(resumeId, states);
        }
        @Override
        public synchronized Optional<MatchTask> lockById(UUID id) {
            lockCalls++;
            if (lockCalls >= 2 && replacement != null && replacement.id().equals(id)) {
                return Optional.of(replacement);
            }
            return delegate.lockById(id);
        }
    }

    private static final class ArchivingRaceReceipts implements CallbackReceiptRepository {
        private final Resume resume;
        private CallbackReceipt receipt;

        private ArchivingRaceReceipts(Resume resume) { this.resume = resume; }

        @Override
        public synchronized Optional<CallbackReceipt> findByCallbackId(UUID callbackId) {
            return receipt == null ? Optional.empty() : Optional.of(receipt);
        }

        @Override
        public synchronized CallbackReceipt save(CallbackReceipt value) {
            receipt = value;
            return value;
        }

        @Override
        public synchronized CallbackReceipt saveAndFlush(CallbackReceipt value) {
            receipt = value;
            // Simulate the competing lifecycle commit becoming visible before
            // the callback transaction retries its idempotency read.
            resume.archive(Instant.parse("2026-01-08T00:00:00Z"));
            throw new DataIntegrityViolationException("duplicate callback");
        }
    }
}
