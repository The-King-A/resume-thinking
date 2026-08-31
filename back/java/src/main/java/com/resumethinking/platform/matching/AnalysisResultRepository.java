package com.resumethinking.platform.matching;

import java.util.*;

public interface AnalysisResultRepository {
    AnalysisResult save(AnalysisResult result);
    Optional<AnalysisResult> findByTaskId(String taskId);
    long countByTaskId(String taskId);
    final class InMemory implements AnalysisResultRepository {
        private final Map<String, AnalysisResult> values = new LinkedHashMap<>();
        public synchronized AnalysisResult save(AnalysisResult result) { values.put(result.taskId(), result); return result; }
        public synchronized Optional<AnalysisResult> findByTaskId(String id) { return Optional.ofNullable(values.get(id)); }
        public synchronized long countByTaskId(String id) { return values.containsKey(id) ? 1 : 0; }
    }
}
