package com.resumethinking.platform.matching;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = false)
public record V3AnalysisCallbackRequest(
        String taskId,
        String revisionId,
        int attempt,
        String callbackId,
        String callbackToken,
        String payloadHash,
        String outcome,
        AnalysisCallbackRequest.AnalysisResultPayload result,
        String errorCode,
        UUID correlationId) {
    public V3AnalysisCallbackRequest withComputedPayloadHash() {
        return new V3AnalysisCallbackRequest(taskId, revisionId, attempt, callbackId, callbackToken,
                V3CallbackPayloadHash.compute(this), outcome, result, errorCode, correlationId);
    }
}
