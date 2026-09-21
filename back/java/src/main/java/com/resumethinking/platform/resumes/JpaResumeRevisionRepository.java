package com.resumethinking.platform.resumes;

import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class JpaResumeRevisionRepository implements ResumeRevisionRepository {
    private final ResumeRevisionJpaRepository delegate;

    public JpaResumeRevisionRepository(ResumeRevisionJpaRepository delegate) {
        this.delegate = delegate;
    }

    @Override
    public ResumeRevision save(ResumeRevision revision) {
        return delegate.save(revision);
    }

    @Override
    public Optional<ResumeRevision> findById(String id) {
        return delegate.findById(id);
    }

    @Override
    public Optional<ResumeRevision> findByIdForUpdate(String id) {
        return delegate.findByIdForUpdate(id);
    }

    @Override
    public Optional<ResumeRevision> findLatestByResumeId(String resumeId) {
        return delegate.findFirstByResumeIdOrderByRevisionNoDesc(resumeId);
    }
}
