package com.resumethinking.platform.matching;

import org.springframework.data.repository.Repository;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.*;

public interface MatchTaskRepository extends Repository<MatchTask, UUID> {
    MatchTask save(MatchTask task);
    Optional<MatchTask> findById(UUID id);
    Optional<MatchTask> findByCreatorIdAndIdempotencyKey(UUID creatorId, String idempotencyKey);
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select t from MatchTask t where t.id = :id") Optional<MatchTask> findByIdForUpdate(@Param("id") UUID id);
    default Optional<MatchTask> lockById(UUID id) { return findByIdForUpdate(id); }

    final class InMemory implements MatchTaskRepository {
        private final Map<UUID, MatchTask> values = new LinkedHashMap<>();
        public synchronized MatchTask save(MatchTask task) { values.put(task.getId(), task); return task; }
        public synchronized Optional<MatchTask> findById(UUID id) { return Optional.ofNullable(values.get(id)); }
        public synchronized Optional<MatchTask> findByIdForUpdate(UUID id) { return findById(id); }
        public synchronized Optional<MatchTask> findByCreatorIdAndIdempotencyKey(UUID owner, String key) { return values.values().stream().filter(t -> owner.equals(t.getCreatorId()) && key.equals(t.getIdempotencyKey())).findFirst(); }
    }
}
