package com.resumethinking.platform.interviews;

import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.time.Instant;
import java.util.Collection;

@Repository
public class JpaInterviewSessionRepository implements InterviewSessionRepository {
    private final InterviewSessionJpaRepository delegate;
    public JpaInterviewSessionRepository(InterviewSessionJpaRepository delegate) { this.delegate = delegate; }
    public InterviewSession save(InterviewSession value) { return delegate.save(value); }
    public InterviewSession saveAndFlush(InterviewSession value) { return delegate.saveAndFlush(value); }
    public Optional<InterviewSession> findById(String id) { return delegate.findById(id); }
    public Optional<InterviewSession> findByIdForUpdate(String id) { return delegate.findByIdForUpdate(id); }
    public Optional<InterviewSession> findByOwnerIdAndIdempotencyKey(String ownerId, String key) { return delegate.findByOwnerIdAndIdempotencyKey(ownerId, key); }
    public List<InterviewSession> findByResumeIdAndStateNot(String resumeId, InterviewSession.State state) { return delegate.findByResumeIdAndStateNot(resumeId, state); }
    public List<InterviewSession> findInFlightUpdatedBeforeForUpdate(Collection<InterviewSession.State> states, Instant cutoff) {
        return delegate.findByStateInAndUpdatedAtBeforeForUpdate(states, cutoff);
    }
}
