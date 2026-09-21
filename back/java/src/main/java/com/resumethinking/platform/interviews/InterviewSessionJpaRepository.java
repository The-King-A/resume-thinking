package com.resumethinking.platform.interviews;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.time.Instant;
import java.util.Collection;

interface InterviewSessionJpaRepository extends JpaRepository<InterviewSession, String> {
    Optional<InterviewSession> findByOwnerIdAndIdempotencyKey(String ownerId, String idempotencyKey);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from InterviewSession s where s.id = :id")
    Optional<InterviewSession> findByIdForUpdate(@Param("id") String id);
    List<InterviewSession> findByResumeIdAndStateNot(String resumeId, InterviewSession.State state);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from InterviewSession s where s.state in :states and s.updatedAt < :cutoff order by s.id")
    List<InterviewSession> findByStateInAndUpdatedAtBeforeForUpdate(@Param("states") Collection<InterviewSession.State> states,
                                                                      @Param("cutoff") Instant cutoff);
}
