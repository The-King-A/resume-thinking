package com.resumethinking.platform.interviews;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface InterviewAnswerRepository {
    InterviewAnswer save(InterviewAnswer answer);
    Optional<InterviewAnswer> findById(String id);
    Optional<InterviewAnswer> findBySessionIdAndIdempotencyKey(String sessionId, String key);
    Optional<InterviewAnswer> findBySessionIdAndQuestionId(String sessionId, String questionId);
    void deleteBySessionId(String sessionId);

    final class InMemory implements InterviewAnswerRepository {
        private final Map<String, InterviewAnswer> values = new LinkedHashMap<>();
        public synchronized InterviewAnswer save(InterviewAnswer value) { values.put(value.getId(), value); return value; }
        public synchronized Optional<InterviewAnswer> findById(String id) { return Optional.ofNullable(values.get(id)); }
        public synchronized Optional<InterviewAnswer> findBySessionIdAndIdempotencyKey(String sessionId, String key) { return values.values().stream().filter(v -> sessionId.equals(v.getSessionId()) && key.equals(v.getIdempotencyKey())).findFirst(); }
        public synchronized Optional<InterviewAnswer> findBySessionIdAndQuestionId(String sessionId, String questionId) { return values.values().stream().filter(v -> sessionId.equals(v.getSessionId()) && questionId.equals(v.getQuestionId())).findFirst(); }
        public synchronized void deleteBySessionId(String sessionId) { new ArrayList<>(values.values()).stream().filter(v -> sessionId.equals(v.getSessionId())).forEach(v -> { v.clear(java.time.Instant.now()); values.put(v.getId(), v); }); }
    }
}
