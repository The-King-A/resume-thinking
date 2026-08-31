package com.resumethinking.platform.resumes;

import java.time.Instant; import java.util.*;

public interface ResumeAuditRepository {
    void save(ResumeLifecycleAudit audit);
    void backfillNullActorForLifecycleAudit(String resumeId, String actorId, String action,
                                            VisibilityState priorVisibilityState,
                                            VisibilityState newVisibilityState);
    record ResumeLifecycleAudit(String resumeId, String actorId, String action,
                                VisibilityState priorVisibilityState, VisibilityState newVisibilityState,
                                Instant occurredAt, UUID correlationId) {}
    class InMemory implements ResumeAuditRepository {
        private final List<ResumeLifecycleAudit> values=new ArrayList<>();
        public void save(ResumeLifecycleAudit a){values.add(a);}
        public void backfillNullActorForLifecycleAudit(String resumeId, String actorId, String action,
                                                       VisibilityState priorVisibilityState,
                                                       VisibilityState newVisibilityState){
            for(int i=0;i<values.size();i++){
                ResumeLifecycleAudit audit=values.get(i);
                if(audit.resumeId().equals(resumeId) && audit.actorId()==null && audit.action().equals(action)
                        && audit.priorVisibilityState()==priorVisibilityState
                        && audit.newVisibilityState()==newVisibilityState){
                    values.set(i,new ResumeLifecycleAudit(audit.resumeId(),actorId,audit.action(),
                            audit.priorVisibilityState(),audit.newVisibilityState(),audit.occurredAt(),
                            audit.correlationId()));
                }
            }
        }
        public List<ResumeLifecycleAudit> all(){return values;}
    }
}
