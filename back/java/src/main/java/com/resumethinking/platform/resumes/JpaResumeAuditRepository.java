package com.resumethinking.platform.resumes;

import org.springframework.stereotype.Repository;

@Repository
public class JpaResumeAuditRepository implements ResumeAuditRepository {
    private final ResumeAuditJpaRepository delegate; private final com.resumethinking.platform.ids.ReadableIdGenerator ids;
    public JpaResumeAuditRepository(ResumeAuditJpaRepository delegate, com.resumethinking.platform.ids.ReadableIdGenerator ids){this.delegate=delegate;this.ids=ids;}
    @Override public void save(ResumeLifecycleAudit audit){delegate.save(new ResumeAudit(ids.next(com.resumethinking.platform.ids.BusinessIdType.AUDIT), audit));}
    @Override public void backfillNullActorForLifecycleAudit(String resumeId, String actorId,
                                                             String action, VisibilityState priorVisibilityState,
                                                             VisibilityState newVisibilityState){
        delegate.backfillNullActorForLifecycleAudit(resumeId,actorId,action,priorVisibilityState,newVisibilityState);
    }
}
