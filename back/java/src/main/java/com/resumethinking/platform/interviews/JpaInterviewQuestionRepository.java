package com.resumethinking.platform.interviews;

import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public class JpaInterviewQuestionRepository implements InterviewQuestionRepository {
    private final InterviewQuestionJpaRepository delegate;
    public JpaInterviewQuestionRepository(InterviewQuestionJpaRepository delegate) { this.delegate = delegate; }
    public InterviewQuestion save(InterviewQuestion value) { return delegate.save(value); }
    public List<InterviewQuestion> findBySessionIdOrderBySequenceNo(String id) { return delegate.findBySessionIdOrderBySequenceNo(id); }
    public Optional<InterviewQuestion> findById(String id) { return delegate.findById(id); }
    public void deleteBySessionId(String id) { delegate.deleteBySessionId(id); }
}
