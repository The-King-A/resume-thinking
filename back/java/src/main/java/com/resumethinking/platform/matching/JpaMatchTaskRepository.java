package com.resumethinking.platform.matching;

import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/** Adapter keeps the domain repository small while exposing JPA flush/lock operations. */
@Repository
public class JpaMatchTaskRepository implements MatchTaskRepository {
    private final MatchTaskJpaRepository delegate;

    public JpaMatchTaskRepository(MatchTaskJpaRepository delegate) {
        this.delegate = delegate;
    }

    @Override public MatchTask save(MatchTask task) { return delegate.save(task); }
    @Override public MatchTask saveAndFlush(MatchTask task) { return delegate.saveAndFlush(task); }
    @Override public Optional<MatchTask> findById(UUID id) { return delegate.findById(id); }
    @Override public Optional<MatchTask> findByCreatorIdAndIdempotencyKey(UUID owner, String key) {
        return delegate.findByCreatorIdAndIdempotencyKey(owner, key);
    }
    @Override public Optional<MatchTask> findByIdForUpdate(UUID id) { return delegate.findByIdForUpdate(id); }
}
