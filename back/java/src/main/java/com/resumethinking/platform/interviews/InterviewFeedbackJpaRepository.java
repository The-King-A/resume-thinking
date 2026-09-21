package com.resumethinking.platform.interviews;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

interface InterviewFeedbackJpaRepository extends JpaRepository<InterviewFeedback, String> {
    Optional<InterviewFeedback> findByAnswerId(String answerId);
    void deleteBySessionId(String sessionId);
}
