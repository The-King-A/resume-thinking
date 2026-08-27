package com.resumethinking.platform.resumes;

import java.time.Instant; import java.util.*;

public interface ResumeAuditRepository { void save(ResumeLifecycleAudit audit); record ResumeLifecycleAudit(UUID resumeId, UUID actorId, String action, VisibilityState state, Instant occurredAt) {} class InMemory implements ResumeAuditRepository { private final List<ResumeLifecycleAudit> values=new ArrayList<>(); public void save(ResumeLifecycleAudit a){values.add(a);} public List<ResumeLifecycleAudit> all(){return values;} } }
