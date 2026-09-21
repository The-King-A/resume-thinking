package com.resumethinking.platform.interviews;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public interface InterviewFeedbackRepository {
    InterviewFeedback save(InterviewFeedback feedback);
    Optional<InterviewFeedback> findById(String id);
    Optional<InterviewFeedback> findByAnswerId(String answerId);
    void deleteBySessionId(String sessionId);

    final class InMemory implements InterviewFeedbackRepository {
        private final Map<String, InterviewFeedback> values = new LinkedHashMap<>();
        public synchronized InterviewFeedback save(InterviewFeedback value) { values.put(value.getId(), value); return value; }
        public synchronized Optional<InterviewFeedback> findById(String id) { return Optional.ofNullable(values.get(id)); }
        public synchronized Optional<InterviewFeedback> findByAnswerId(String answerId) { return values.values().stream().filter(v -> answerId.equals(v.getAnswerId())).findFirst(); }
        public synchronized void deleteBySessionId(String sessionId) { new ArrayList<>(values.values()).stream().filter(v -> sessionId.equals(v.getSessionId())).forEach(v -> values.remove(v.getId())); }
    }
}
