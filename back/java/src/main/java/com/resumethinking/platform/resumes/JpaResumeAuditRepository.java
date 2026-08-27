package com.resumethinking.platform.resumes;

import org.springframework.stereotype.Repository;

@Repository
public class JpaResumeAuditRepository implements ResumeAuditRepository {
    private final ResumeAuditJpaRepository delegate;
    public JpaResumeAuditRepository(ResumeAuditJpaRepository delegate){this.delegate=delegate;}
    @Override public void save(ResumeLifecycleAudit audit){delegate.save(new ResumeAudit(audit));}
}
