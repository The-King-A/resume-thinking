package com.resumethinking.platform.resumes;

import com.resumethinking.platform.auth.UserRole;
import com.resumethinking.platform.ids.InMemoryReadableIdGenerator;
import com.resumethinking.platform.matching.AnalysisEvidence;
import com.resumethinking.platform.matching.AnalysisEvidenceRepository;
import com.resumethinking.platform.matching.AnalysisResult;
import com.resumethinking.platform.matching.AnalysisResultRepository;
import com.resumethinking.platform.matching.JobFamily;
import com.resumethinking.platform.matching.MatchTask;
import com.resumethinking.platform.matching.MatchTaskRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lifecycle invariants for a candidate revision that is interrupted by a
 * visibility transition.  These tests are intentionally independent of JPA;
 * the user-owned validation run can execute them in the IDE or Maven later.
 */
class ResumePendingRevisionCleanupTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final String OWNER = "user001";

    @Test
    void softDeleteSupersedesPendingCandidateButPreservesEffectiveRevision() {
        Fixture fixture = fixture(NOW);
        Resume resume = fixture.resume;

        fixture.lifecycle.softDelete(new DeleteResumeCommand(
                resume.getId(), OWNER, UserRole.USER,
                ResumeLifecycleService.CONFIRMATION, resume.getVersion()));

        assertThat(resume.getPendingRevisionId()).isNull();
        assertThat(fixture.pending.getState()).isEqualTo(ResumeRevision.State.SUPERSEDED);
        assertThat(fixture.effective.getState()).isEqualTo(ResumeRevision.State.EFFECTIVE);
        assertThat(resume.getEffectiveRevisionId()).isEqualTo(fixture.effective.getId());
    }

    @Test
    void cacheArchiveSupersedesPendingCandidateButPreservesEffectiveRevision() {
        Fixture fixture = fixture(NOW.minusSeconds(8 * 24 * 60 * 60L));
        Resume resume = fixture.resume;

        assertThat(fixture.lifecycle.archiveDue(NOW)).isEqualTo(1);

        assertThat(resume.getVisibilityState()).isEqualTo(VisibilityState.USER_CACHE_ARCHIVED);
        assertThat(resume.getPendingRevisionId()).isNull();
        assertThat(fixture.pending.getState()).isEqualTo(ResumeRevision.State.SUPERSEDED);
        assertThat(fixture.effective.getState()).isEqualTo(ResumeRevision.State.EFFECTIVE);
    }

    @Test
    void restoreClearsAndSupersedesCandidateAfterSuccessfulValidation() {
        Fixture fixture = fixture(NOW);
        Resume resume = fixture.resume;
        fixture.lifecycle.softDelete(new DeleteResumeCommand(
                resume.getId(), OWNER, UserRole.USER,
                ResumeLifecycleService.CONFIRMATION, resume.getVersion()));

        fixture.lifecycle.recover(resume.getId(), OWNER, UserRole.USER, resume.getVersion());

        assertThat(resume.getVisibilityState()).isEqualTo(VisibilityState.ACTIVE);
        assertThat(resume.getPendingRevisionId()).isNull();
        assertThat(fixture.pending.getState()).isEqualTo(ResumeRevision.State.SUPERSEDED);
        assertThat(fixture.effective.getState()).isEqualTo(ResumeRevision.State.EFFECTIVE);
    }

    private static Fixture fixture(Instant createdAt) {
        ResumeRepository.InMemory resumes = new ResumeRepository.InMemory();
        ResumeRevisionRepository.InMemory revisions = new ResumeRevisionRepository.InMemory();
        MatchTaskRepository.InMemory tasks = new MatchTaskRepository.InMemory();
        AnalysisResultRepository.InMemory results = new AnalysisResultRepository.InMemory();
        AnalysisEvidenceRepository.InMemory evidence = new AnalysisEvidenceRepository.InMemory();
        Resume resume = new Resume("resume001", OWNER, "Java CV", Resume.SourceType.TXT,
                UserRole.USER, new byte[]{1}, new byte[12], createdAt, "v1");
        ResumeRevision effective = new ResumeRevision("revision001", resume.getId(), 1,
                "Java CV", "java cv", Resume.SourceType.TXT, "v1", new byte[]{2}, new byte[12],
                ResumeRevision.State.EFFECTIVE, createdAt);
        ResumeRevision pending = new ResumeRevision("revision002", resume.getId(), 2,
                "Java CV revised", "java cv revised", Resume.SourceType.TXT, "v1", new byte[]{3}, new byte[12],
                ResumeRevision.State.PENDING, createdAt);
        revisions.save(effective);
        revisions.save(pending);
        resume.publish(effective, createdAt);
        resume.stageRevision(pending, createdAt);
        resumes.save(resume);
        MatchTask task = new MatchTask("task001", "callback001", resume.getId(), effective.getId(),
                "profile001", OWNER, resume.getVersion(), JobFamily.JAVA_BACKEND,
                "Build reliable Java services with clear tests and operational ownership.",
                "cleanup-fixture-key", "fixture-callback-token", Set.of("evidence001"),
                MatchTask.PublicationState.PUBLISHED, createdAt);
        task.markSucceeded();
        tasks.save(task);
        results.save(new AnalysisResult(task.getId(), resume.getId(), effective.getId(), resume.getVersion(),
                "Build reliable Java services with clear tests and operational ownership.",
                0.8, List.of(), List.of(), createdAt, "SUCCEEDED", null));
        evidence.save(new AnalysisEvidence("evidence001", task.getId(), "TXT", "line:1", 0, 1, "J"));
        ResumeLifecycleService lifecycle = new ResumeLifecycleService(
                resumes, new ResumeCache.Noop(), new ResumeAuditRepository.InMemory(),
                Clock.fixed(NOW, ZoneOffset.UTC), ResumeTaskBlocker.NOOP,
                new InMemoryReadableIdGenerator(), null, revisions, tasks, results, evidence);
        return new Fixture(resume, effective, pending, lifecycle);
    }

    private record Fixture(Resume resume, ResumeRevision effective, ResumeRevision pending,
                           ResumeLifecycleService lifecycle) {
    }
}
