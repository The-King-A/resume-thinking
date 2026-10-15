package com.resumethinking.platform.matching;

import java.time.Instant;
import java.util.*;

public record AnalysisResult(String taskId, String resumeId, String revisionId, long resumeVersion, String jobDescriptionText,
                             Object score, List<?> requirements, List<?> suggestions, Instant completedAt,
                             String outcome, String errorCode) {
    public AnalysisResult(String taskId, String resumeId, long resumeVersion, String jobDescriptionText,
                          Object score, List<?> requirements, List<?> suggestions, Instant completedAt,
                          String outcome, String errorCode) {
        this(taskId, resumeId, null, resumeVersion, jobDescriptionText, score, requirements,
                suggestions, completedAt, outcome, errorCode);
    }

    public static AnalysisResult from(String taskId, String resumeId, String revisionId, long version,
                                      String job, AnalysisCallbackRequest request) {
        var value = request.result();
        return new AnalysisResult(taskId, resumeId, revisionId, version, job,
                value == null ? null : value.score(), value == null ? List.of() : value.requirements(),
                value == null ? List.of() : value.suggestions(), Instant.now(), request.outcome(), request.errorCode());
    }

    public static AnalysisResult from(String taskId, String resumeId, long version, String job, AnalysisCallbackRequest request) {
        return from(taskId, resumeId, null, version, job, request);
    }

    public static AnalysisResult from(String taskId, String resumeId, String revisionId, long version,
                                      String job, V3AnalysisCallbackRequest request) {
        var value = request.result();
        return new AnalysisResult(taskId, resumeId, revisionId, version, job,
                value == null ? null : value.score(), value == null ? List.of() : value.requirements(),
                value == null ? List.of() : value.suggestions(), Instant.now(), request.outcome(), request.errorCode());
    }
}
