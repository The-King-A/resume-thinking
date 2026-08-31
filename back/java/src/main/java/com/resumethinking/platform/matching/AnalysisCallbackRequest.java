package com.resumethinking.platform.matching;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = false)
public record AnalysisCallbackRequest(String taskId, int attempt, String callbackId, String callbackToken,
                                     String payloadHash, String outcome, AnalysisResultPayload result,
                                     String errorCode, UUID correlationId) {
    public AnalysisCallbackRequest withComputedPayloadHash() { return new AnalysisCallbackRequest(taskId, attempt, callbackId, callbackToken, CallbackPayloadHash.compute(this), outcome, result, errorCode, correlationId); }
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record AnalysisResultPayload(ScoreBreakdown score, java.util.List<RequirementMatch> requirements, java.util.List<Suggestion> suggestions) {}
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record ScoreBreakdown(double skills, double projectExperience, double workContent, double educationExperience, double softSkills, double composite) {}
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record RequirementMatch(String requirementId, String jobRequirementText, String requirementType, String matchStatus,
                                   String matchType, String component, double componentScore, java.util.List<EvidenceReference> evidence,
                                   String evidenceStrength, String gap, String suggestionState) {}
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record EvidenceReference(String evidenceId, int sourceStart, int sourceEnd, String excerpt, double confidence) {}
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record Suggestion(String suggestionId, String requirementId, String state, String proposedText, java.util.List<String> evidenceIds) {}
}
