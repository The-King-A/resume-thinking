package com.resumethinking.platform.matching;

import java.util.*;

public interface AnalysisResultRepository {
    AnalysisResult save(AnalysisResult result);
    Optional<AnalysisResult> findByTaskId(String taskId);
    Optional<AnalysisResult> findLatestByResumeId(String resumeId);
    Optional<AnalysisResult> findLatestByResumeIdAndRevisionId(String resumeId, String revisionId);
    long countByTaskId(String taskId);
    final class InMemory implements AnalysisResultRepository {
        private final Map<String, AnalysisResult> values = new LinkedHashMap<>();
        public synchronized AnalysisResult save(AnalysisResult result) { values.put(result.taskId(), result); return result; }
        public synchronized Optional<AnalysisResult> findByTaskId(String id) { return Optional.ofNullable(values.get(id)); }
        public synchronized Optional<AnalysisResult> findLatestByResumeId(String resumeId) { return values.values().stream().filter(result -> result.resumeId().equals(resumeId)).max(Comparator.comparing(AnalysisResult::completedAt)); }
        public synchronized Optional<AnalysisResult> findLatestByResumeIdAndRevisionId(String resumeId,String revisionId) { return values.values().stream().filter(result -> result.resumeId().equals(resumeId) && Objects.equals(result.revisionId(),revisionId)).max(Comparator.comparing(AnalysisResult::completedAt)); }
        public synchronized long countByTaskId(String id) { return values.containsKey(id) ? 1 : 0; }
    }
}
