package com.resumethinking.platform.interviews;

import com.resumethinking.platform.resumes.ResumeTaskBlocker;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

/** Blocks and clears interview content while the resume lifecycle row is locked. */
@Component
public final class InterviewSessionResumeTaskBlocker implements ResumeTaskBlocker {
    private final InterviewSessionRepository sessions;
    private final InterviewQuestionRepository questions;
    private final InterviewAnswerRepository answers;
    private final InterviewFeedbackRepository feedback;
    private final InterviewConfirmationRepository confirmations;
    private final Clock clock;

    @Autowired
    public InterviewSessionResumeTaskBlocker(InterviewSessionRepository sessions,
                                             InterviewQuestionRepository questions,
                                             InterviewAnswerRepository answers,
                                             InterviewFeedbackRepository feedback,
                                             InterviewConfirmationRepository confirmations) {
        this(sessions, questions, answers, feedback, confirmations, Clock.systemUTC());
    }

    public InterviewSessionResumeTaskBlocker(InterviewSessionRepository sessions,
                                             InterviewQuestionRepository questions,
                                             InterviewAnswerRepository answers,
                                             InterviewFeedbackRepository feedback,
                                             InterviewConfirmationRepository confirmations,
                                             Clock clock) {
        this.sessions = sessions;
        this.questions = questions;
        this.answers = answers;
        this.feedback = feedback;
        this.confirmations = confirmations;
        this.clock = clock;
    }

    @Override
    public void blockPendingTasks(String resumeId) {
        for (InterviewSession session : sessions.findByResumeIdAndStateNot(resumeId, InterviewSession.State.DELETED)) {
            session.delete(clock.instant());
            sessions.saveAndFlush(session);
            confirmations.deleteBySessionId(session.getId());
            feedback.deleteBySessionId(session.getId());
            answers.deleteBySessionId(session.getId());
            questions.deleteBySessionId(session.getId());
        }
    }
}
