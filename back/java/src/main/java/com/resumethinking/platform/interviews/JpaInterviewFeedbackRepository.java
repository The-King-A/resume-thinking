package com.resumethinking.platform.interviews;

import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public class JpaInterviewFeedbackRepository implements InterviewFeedbackRepository {
    private final InterviewFeedbackJpaRepository delegate;
    public JpaInterviewFeedbackRepository(InterviewFeedbackJpaRepository delegate) { this.delegate = delegate; }
    public InterviewFeedback save(InterviewFeedback value) { return delegate.save(value); }
    public Optional<InterviewFeedback> findById(String id) { return delegate.findById(id); }
    public Optional<InterviewFeedback> findByAnswerId(String answerId) { return delegate.findByAnswerId(answerId); }
    public void deleteBySessionId(String sessionId) { delegate.deleteBySessionId(sessionId); }
}
