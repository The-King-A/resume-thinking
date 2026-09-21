package com.resumethinking.platform.interviews;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.time.Instant;
import java.util.Collection;

public interface InterviewSessionRepository {
    InterviewSession save(InterviewSession session);
    default InterviewSession saveAndFlush(InterviewSession session) { return save(session); }
    Optional<InterviewSession> findById(String id);
    Optional<InterviewSession> findByIdForUpdate(String id);
    Optional<InterviewSession> findByOwnerIdAndIdempotencyKey(String ownerId, String idempotencyKey);
    List<InterviewSession> findByResumeIdAndStateNot(String resumeId, InterviewSession.State state);
    default List<InterviewSession> findInFlightUpdatedBeforeForUpdate(Collection<InterviewSession.State> states, Instant cutoff) {
        return List.of();
    }

    final class InMemory implements InterviewSessionRepository {
        private final Map<String, InterviewSession> values = new LinkedHashMap<>();
        public synchronized InterviewSession save(InterviewSession value) { values.put(value.getId(), value); return value; }
        public synchronized Optional<InterviewSession> findById(String id) { return Optional.ofNullable(values.get(id)); }
        public synchronized Optional<InterviewSession> findByIdForUpdate(String id) { return findById(id); }
        public synchronized Optional<InterviewSession> findByOwnerIdAndIdempotencyKey(String ownerId, String key) {
            return values.values().stream().filter(v -> ownerId.equals(v.getOwnerId()) && key.equals(v.getIdempotencyKey())).findFirst();
        }
        public synchronized List<InterviewSession> findByResumeIdAndStateNot(String resumeId, InterviewSession.State state) {
            return new ArrayList<>(values.values().stream().filter(v -> resumeId.equals(v.getResumeId()) && v.getState() != state).toList());
        }
        @Override public synchronized List<InterviewSession> findInFlightUpdatedBeforeForUpdate(Collection<InterviewSession.State> states, Instant cutoff) {
            return values.values().stream()
                    .filter(value -> states.contains(value.getState()) && value.getUpdatedAt().isBefore(cutoff))
                    .toList();
        }
    }
}
