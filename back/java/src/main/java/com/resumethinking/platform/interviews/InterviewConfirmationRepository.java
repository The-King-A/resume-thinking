package com.resumethinking.platform.interviews;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public interface InterviewConfirmationRepository {
    InterviewConfirmation save(InterviewConfirmation confirmation);
    Optional<InterviewConfirmation> findByFeedbackIdAndClaimId(String feedbackId, String claimId);
    void deleteBySessionId(String sessionId);

    final class InMemory implements InterviewConfirmationRepository {
        private final Map<String, InterviewConfirmation> values = new LinkedHashMap<>();
        public synchronized InterviewConfirmation save(InterviewConfirmation value) { values.put(value.getId(), value); return value; }
        public synchronized Optional<InterviewConfirmation> findByFeedbackIdAndClaimId(String feedbackId, String claimId) { return values.values().stream().filter(v -> feedbackId.equals(v.getFeedbackId()) && claimId.equals(v.getClaimId())).findFirst(); }
        public synchronized void deleteBySessionId(String sessionId) { new ArrayList<>(values.values()).stream().filter(v -> sessionId.equals(v.getSessionId())).forEach(v -> values.remove(v.getId())); }
    }
}
