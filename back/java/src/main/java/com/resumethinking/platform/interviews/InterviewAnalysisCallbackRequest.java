package com.resumethinking.platform.interviews;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = false)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record InterviewAnalysisCallbackRequest(
        String contractVersion,
        String workType,
        String sessionId,
        String revisionId,
        String matchTaskId,
        long sessionVersion,
        int attempt,
        String callbackId,
        String callbackToken,
        String payloadHash,
        String outcome,
        UUID correlationId,
        List<QuestionPayload> questions,
        FeedbackPayload feedback,
        String errorCode) {

    public record QuestionPayload(String questionId, int sequence, String questionType, String difficulty,
                                  String questionText, String requirementId, String requirementText,
                                  List<String> evidenceIds, String generationReason, double confidence) { }

    public record FeedbackPayload(String feedbackId, String answerId, String state, String relevance,
                                   String completeness, String technicalAccuracy, String factualConsistency,
                                   String clarity, List<String> evidenceIds, List<RiskFlag> riskFlags,
                                   List<Claim> claims, String improvementSuggestion, String suggestedAnswer,
                                   String answerComparison, long version) { }

    public record RiskFlag(String code, String message, String level, String claimState) { }

    public record Claim(String id, String claimText, String state, List<String> evidenceIds, boolean applied) { }

    public InterviewAnalysisCallbackRequest withComputedPayloadHash() {
        return new InterviewAnalysisCallbackRequest(contractVersion, workType, sessionId, revisionId, matchTaskId,
                sessionVersion, attempt, callbackId, callbackToken, InterviewCallbackPayloadHash.compute(this),
                outcome, correlationId, questions, feedback, errorCode);
    }
}
