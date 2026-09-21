package com.resumethinking.platform.interviews;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

interface InterviewQuestionJpaRepository extends JpaRepository<InterviewQuestion, String> {
    List<InterviewQuestion> findBySessionIdOrderBySequenceNo(String sessionId);
    Optional<InterviewQuestion> findByIdAndSessionId(String id, String sessionId);
    void deleteBySessionId(String sessionId);
}
