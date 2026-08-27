package com.resumethinking.platform.matching;

import java.time.Instant;
import java.util.*;

public record AnalysisResult(UUID taskId, UUID resumeId, long resumeVersion, String jobDescriptionText,
                             Object score, List<?> requirements, List<?> suggestions, Instant completedAt,
                             String outcome, String errorCode) {
    public static AnalysisResult from(UUID taskId, UUID resumeId, long version, String job, AnalysisCallbackRequest request) {
        var value = request.result();
        return new AnalysisResult(taskId, resumeId, version, job,
                value == null ? null : value.score(), value == null ? List.of() : value.requirements(),
                value == null ? List.of() : value.suggestions(), Instant.now(), request.outcome(), request.errorCode());
    }
}
