package com.resumethinking.platform.interviews;

import com.resumethinking.platform.matching.JobFamily;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class InterviewSessionTimeoutServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-20T12:00:00Z");

    @Test
    void marksStaleQuestionGenerationAsModelUnavailable() {
        var repository = new InterviewSessionRepository.InMemory();
        var session = InterviewSession.create("session001", "user001", "resume001", "revision001", "task001",
                "profile001", JobFamily.JAVA_BACKEND, "interview-timeout-key-0001", NOW.minus(Duration.ofMinutes(6)));
        session.startQuestionGeneration("callback001", "callback-token-000000000000000000000000000000", 1,
                NOW.minus(Duration.ofMinutes(6)));
        repository.save(session);

        var service = new InterviewSessionTimeoutService(repository, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(5));

        assertThat(service.timeoutStaleSessions()).isEqualTo(1);
        assertThat(session.getState()).isEqualTo(InterviewSession.State.FAILED);
        assertThat(session.getFailureCode()).isEqualTo("INTERVIEW_MODEL_UNAVAILABLE");
    }

    @Test
    void leavesFreshInterviewWorkInProgress() {
        var repository = new InterviewSessionRepository.InMemory();
        var session = InterviewSession.create("session001", "user001", "resume001", "revision001", "task001",
                "profile001", JobFamily.JAVA_BACKEND, "interview-timeout-key-0002", NOW.minus(Duration.ofMinutes(4)));
        session.startQuestionGeneration("callback001", "callback-token-000000000000000000000000000000", 1,
                NOW.minus(Duration.ofMinutes(4)));
        repository.save(session);

        var service = new InterviewSessionTimeoutService(repository, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(5));

        assertThat(service.timeoutStaleSessions()).isZero();
        assertThat(session.getState()).isEqualTo(InterviewSession.State.QUESTION_GENERATING);
    }
}
