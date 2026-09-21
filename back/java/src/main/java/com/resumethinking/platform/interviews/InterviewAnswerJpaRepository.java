package com.resumethinking.platform.interviews;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

interface InterviewAnswerJpaRepository extends JpaRepository<InterviewAnswer, String> {
    Optional<InterviewAnswer> findBySessionIdAndIdempotencyKey(String sessionId, String key);
    Optional<InterviewAnswer> findBySessionIdAndQuestionId(String sessionId, String questionId);
    void deleteBySessionId(String sessionId);
}
