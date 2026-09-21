package com.resumethinking.platform.interviews;

import com.resumethinking.platform.crypto.AesGcmCryptoService;
import com.resumethinking.platform.matching.JobFamily;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class InterviewSessionResumeTaskBlockerTest {
    private static final Instant NOW = Instant.parse("2026-09-20T09:00:00Z");

    @Test
    void hidesSessionAndClearsEncryptedAnswerUsingTheLifecycleClock() {
        var sessions = new InterviewSessionRepository.InMemory();
        var questions = new InterviewQuestionRepository.InMemory();
        var answers = new InterviewAnswerRepository.InMemory();
        var feedback = new InterviewFeedbackRepository.InMemory();
        var confirmations = new InterviewConfirmationRepository.InMemory();
        var session = InterviewSession.create("session001", "user001", "resume001", "revision001", "task001",
                "profile001", JobFamily.JAVA_BACKEND, "session-key-0001", NOW);
        sessions.save(session);
        questions.save(new InterviewQuestion("question001", "session001", 1, "BASIC_CONFIRMATION", "BASIC",
                "说明职责", "requirement001", "Java", "[\"evidence001\"]", "核对", .9, NOW));
        var crypto = new AesGcmCryptoService("MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
        var encrypted = crypto.encrypt("敏感回答");
        var answer = new InterviewAnswer("answer001", "session001", "question001", "answer-key-0001",
                "a".repeat(64), encrypted.ciphertext(), encrypted.nonce(), NOW);
        answers.save(answer);

        var blocker = new InterviewSessionResumeTaskBlocker(sessions, questions, answers, feedback,
                confirmations, Clock.fixed(NOW.plusSeconds(10), ZoneOffset.UTC));
        blocker.blockPendingTasks("resume001");

        assertThat(session.getState()).isEqualTo(InterviewSession.State.DELETED);
        assertThat(session.getDeletedAt()).isEqualTo(NOW.plusSeconds(10));
        assertThat(answer.getState()).isEqualTo(InterviewAnswer.State.CLEARED);
        assertThat(answer.getCiphertext()).isEmpty();
    }
}
