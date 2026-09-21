package com.resumethinking.platform.interviews;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

interface InterviewConfirmationJpaRepository extends JpaRepository<InterviewConfirmation, String> {
    Optional<InterviewConfirmation> findByFeedbackIdAndClaimId(String feedbackId, String claimId);
    void deleteBySessionId(String sessionId);
}
