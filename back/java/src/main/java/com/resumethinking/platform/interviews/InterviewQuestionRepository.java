package com.resumethinking.platform.interviews;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface InterviewQuestionRepository {
    InterviewQuestion save(InterviewQuestion question);
    List<InterviewQuestion> findBySessionIdOrderBySequenceNo(String sessionId);
    Optional<InterviewQuestion> findById(String id);
    void deleteBySessionId(String sessionId);

    final class InMemory implements InterviewQuestionRepository {
        private final Map<String, InterviewQuestion> values = new LinkedHashMap<>();
        public synchronized InterviewQuestion save(InterviewQuestion value) { values.put(value.getId(), value); return value; }
        public synchronized List<InterviewQuestion> findBySessionIdOrderBySequenceNo(String id) { return values.values().stream().filter(v -> id.equals(v.getSessionId())).sorted(Comparator.comparingInt(InterviewQuestion::getSequenceNo)).toList(); }
        public synchronized Optional<InterviewQuestion> findById(String id) { return Optional.ofNullable(values.get(id)); }
        public synchronized void deleteBySessionId(String id) { new ArrayList<>(values.values()).stream().filter(v -> id.equals(v.getSessionId())).forEach(v -> values.remove(v.getId())); }
    }
}
