package com.resumethinking.platform.interviews;

import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public class JpaInterviewConfirmationRepository implements InterviewConfirmationRepository {
    private final InterviewConfirmationJpaRepository delegate;
    public JpaInterviewConfirmationRepository(InterviewConfirmationJpaRepository delegate) { this.delegate = delegate; }
    public InterviewConfirmation save(InterviewConfirmation value) { return delegate.save(value); }
    public Optional<InterviewConfirmation> findByFeedbackIdAndClaimId(String feedbackId, String claimId) { return delegate.findByFeedbackIdAndClaimId(feedbackId, claimId); }
    public void deleteBySessionId(String sessionId) { delegate.deleteBySessionId(sessionId); }
}
