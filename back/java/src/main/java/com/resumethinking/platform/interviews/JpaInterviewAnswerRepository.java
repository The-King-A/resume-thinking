package com.resumethinking.platform.interviews;

import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public class JpaInterviewAnswerRepository implements InterviewAnswerRepository {
    private final InterviewAnswerJpaRepository delegate;
    public JpaInterviewAnswerRepository(InterviewAnswerJpaRepository delegate) { this.delegate = delegate; }
    public InterviewAnswer save(InterviewAnswer value) { return delegate.save(value); }
    public Optional<InterviewAnswer> findById(String id) { return delegate.findById(id); }
    public Optional<InterviewAnswer> findBySessionIdAndIdempotencyKey(String sessionId, String key) { return delegate.findBySessionIdAndIdempotencyKey(sessionId, key); }
    public Optional<InterviewAnswer> findBySessionIdAndQuestionId(String sessionId, String questionId) { return delegate.findBySessionIdAndQuestionId(sessionId, questionId); }
    public void deleteBySessionId(String sessionId) { delegate.deleteBySessionId(sessionId); }
}
