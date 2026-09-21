package com.resumethinking.platform.interviews;

import com.resumethinking.platform.matching.JobFamily;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class InterviewSessionDomainTest {
    private static final Instant NOW = Instant.parse("2026-09-20T08:00:00Z");

    @Test
    void acceptsQuestionSetThenOneAnswerFeedbackInOrder() {
        InterviewSession session = InterviewSession.create(
                "session001", "user001", "resume001", "revision001", "task001", "profile001",
                JobFamily.JAVA_BACKEND, "interview-key-0001", NOW);

        session.startQuestionGeneration("callback001", "placeholder-callback-token-000000000000", 1, NOW);
        session.acceptQuestions(4, NOW.plusSeconds(2));
        assertThat(session.getState()).isEqualTo(InterviewSession.State.WAITING_FOR_ANSWER);
        assertThat(session.getQuestionCount()).isEqualTo(4);

        session.startAnswerAnalysis("answer001", "callback002", "placeholder-callback-token-000000000000", 2, NOW.plusSeconds(3));
        assertThat(session.getState()).isEqualTo(InterviewSession.State.ANSWER_ANALYZING);
        session.acceptFeedback("feedback001", NOW.plusSeconds(5));

        assertThat(session.getState()).isEqualTo(InterviewSession.State.FEEDBACK_READY);
        assertThat(session.getFeedbackId()).isEqualTo("feedback001");
    }

    @Test
    void deletionIsTerminalAndCannotBeReactivatedByAStaleCallback() {
        InterviewSession session = InterviewSession.create(
                "session001", "user001", "resume001", "revision001", "task001", "profile001",
                JobFamily.JAVA_BACKEND, "interview-key-0001", NOW);
        session.startQuestionGeneration("callback001", "placeholder-callback-token-000000000000", 1, NOW);
        session.delete(NOW.plusSeconds(1));

        assertThat(session.getState()).isEqualTo(InterviewSession.State.DELETED);
        assertThat(session.acceptsCallback("callback001", 1, 1)).isFalse();
    }

    @Test
    void canMoveFromFeedbackToTheNextUnansweredQuestion() {
        InterviewSession session = InterviewSession.create(
                "session001", "user001", "resume001", "revision001", "task001", "profile001",
                JobFamily.JAVA_BACKEND, "interview-key-0001", NOW);
        session.startQuestionGeneration("callback001", "placeholder-callback-token-000000000000", 1, NOW);
        session.acceptQuestions(4, NOW.plusSeconds(2));
        session.startAnswerAnalysis("answer001", "question001", "callback002", "placeholder-callback-token-000000000000", 1, NOW.plusSeconds(3));
        session.acceptFeedback("feedback001", NOW.plusSeconds(5));

        session.moveToNextQuestion("question002", NOW.plusSeconds(6));

        assertThat(session.getState()).isEqualTo(InterviewSession.State.WAITING_FOR_ANSWER);
        assertThat(session.getCurrentQuestionId()).isEqualTo("question002");
        assertThat(session.getCurrentAnswerId()).isNull();
        assertThat(session.getFeedbackId()).isNull();
    }
}
