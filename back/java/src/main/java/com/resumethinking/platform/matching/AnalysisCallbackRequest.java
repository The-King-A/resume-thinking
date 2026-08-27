package com.resumethinking.platform.matching;

import java.util.UUID;

public record AnalysisCallbackRequest(UUID taskId, int attempt, UUID callbackId, String callbackToken,
                                     String payloadHash, String outcome, AnalysisResultPayload result,
                                     String errorCode, UUID correlationId) {
    public AnalysisCallbackRequest withComputedPayloadHash() { return new AnalysisCallbackRequest(taskId, attempt, callbackId, callbackToken, CallbackPayloadHash.compute(this), outcome, result, errorCode, correlationId); }
    public record AnalysisResultPayload(ScoreBreakdown score, java.util.List<RequirementMatch> requirements, java.util.List<Suggestion> suggestions) {}
    public record ScoreBreakdown(double skills, double projectExperience, double workContent, double educationExperience, double softSkills, double composite) {}
    public record RequirementMatch(UUID requirementId, String jobRequirementText, String requirementType, String matchStatus,
                                   String matchType, String component, double componentScore, java.util.List<EvidenceReference> evidence,
                                   String evidenceStrength, String gap, String suggestionState) {}
    public record EvidenceReference(UUID evidenceId, int sourceStart, int sourceEnd, String excerpt, double confidence) {}
    public record Suggestion(UUID suggestionId, UUID requirementId, String state, String proposedText, java.util.List<UUID> evidenceIds) {}
}
