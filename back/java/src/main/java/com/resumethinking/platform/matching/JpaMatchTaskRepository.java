package com.resumethinking.platform.matching;

import java.util.Optional;
import java.util.Collection;
import java.util.List;
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
    @Override public Optional<MatchTask> findById(String id) { return delegate.findById(id); }
    @Override public Optional<MatchTask> findByCreatorIdAndIdempotencyKey(String owner, String key) {
        return delegate.findByCreatorIdAndIdempotencyKey(owner, key);
    }
    @Override public Optional<MatchTask> findByIdForUpdate(String id) { return delegate.findByIdForUpdate(id); }
    @Override public List<MatchTask> findByResumeIdAndStateInForUpdate(String resumeId, Collection<MatchTask.State> states) {
        return delegate.findByResumeIdAndStateInForUpdate(resumeId, states);
    }
}
