package com.resumethinking.platform.resumes;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ResumeAuditJpaRepository extends JpaRepository<ResumeAudit, String> {
    @Modifying
    @Query("update ResumeAudit a set a.actorId = :actorId where a.resumeId = :resumeId and a.action = :action "
            + "and a.priorVisibilityState = :priorVisibilityState and a.newVisibilityState = :newVisibilityState "
            + "and a.actorId is null")
    int backfillNullActorForLifecycleAudit(@Param("resumeId") String resumeId, @Param("actorId") String actorId,
                                           @Param("action") String action,
                                           @Param("priorVisibilityState") VisibilityState priorVisibilityState,
                                           @Param("newVisibilityState") VisibilityState newVisibilityState);
}
