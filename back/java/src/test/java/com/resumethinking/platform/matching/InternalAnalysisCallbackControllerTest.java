package com.resumethinking.platform.matching;

import com.resumethinking.platform.auth.UserRole;
import com.resumethinking.platform.resumes.*;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.UUID;

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
        var lifecycle = new ResumeLifecycleService(resumes, new ResumeCache.InMemory(), new ResumeAuditRepository.InMemory(),
                Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));
        var taskRepo = new MatchTaskRepository.InMemory();
        var resultRepo = new AnalysisResultRepository.InMemory();
        var service = new MatchTaskService(lifecycle, null, taskRepo, new PythonAnalysisClient.Noop(), resultRepo);
        var task = service.createTask(new CreateMatchTaskCommand(owner, resumeId, UUID.randomUUID(),
                "Build reliable software with clear communication and practical testing.", "archive-key-0000001"));
        task.markProcessing();
        lifecycle.archiveDue(Instant.parse("2026-01-08T00:00:00Z"));

        var response = new InternalAnalysisCallbackController(service).accept(new AnalysisCallbackRequest(
                task.id(), 1, UUID.randomUUID(), task.callbackTokenForTests(), "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "SUCCEEDED", null, null, UUID.randomUUID()));

        assertThat(response.code()).isEqualTo("TASK_GONE");
        assertThat(resultRepo.countByTaskId(task.id())).isZero();
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
}
