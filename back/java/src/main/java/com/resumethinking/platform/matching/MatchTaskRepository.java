package com.resumethinking.platform.matching;

import java.util.*;

/** Domain-facing task store. The JPA adapter provides the locking/flush semantics. */
public interface MatchTaskRepository {
    MatchTask save(MatchTask task);
    default MatchTask saveAndFlush(MatchTask task) { return save(task); }
    Optional<MatchTask> findById(String id);
    Optional<MatchTask> findByCreatorIdAndIdempotencyKey(String creatorId, String idempotencyKey);
    Optional<MatchTask> findByIdForUpdate(String id);
    /**
     * Return all in-flight tasks for a resume while holding write locks.
     * Callers must invoke this inside the resume lifecycle transaction; the
     * stable ordering keeps concurrent lifecycle workers deterministic.
     */
    List<MatchTask> findByResumeIdAndStateInForUpdate(String resumeId, Collection<MatchTask.State> states);
    default Optional<MatchTask> lockById(String id) { return findByIdForUpdate(id); }

    final class InMemory implements MatchTaskRepository {
        private final Map<String, MatchTask> values = new LinkedHashMap<>();
        public synchronized MatchTask save(MatchTask task) { values.put(task.getId(), task); return task; }
        public synchronized MatchTask saveAndFlush(MatchTask task) { return save(task); }
        public synchronized Optional<MatchTask> findById(String id) { return Optional.ofNullable(values.get(id)); }
        public synchronized Optional<MatchTask> findByIdForUpdate(String id) { return findById(id); }
        public synchronized Optional<MatchTask> findByCreatorIdAndIdempotencyKey(String owner, String key) { return values.values().stream().filter(t -> owner.equals(t.getCreatorId()) && key.equals(t.getIdempotencyKey())).findFirst(); }
        public synchronized List<MatchTask> findByResumeIdAndStateInForUpdate(String resumeId, Collection<MatchTask.State> states) {
            return values.values().stream()
                    .filter(t -> Objects.equals(resumeId, t.getResumeId()) && states.contains(t.getState()))
                    .sorted(Comparator.comparing(MatchTask::getId))
                    .toList();
        }
    }
}
