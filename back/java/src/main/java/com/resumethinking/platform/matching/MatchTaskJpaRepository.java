package com.resumethinking.platform.matching;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface MatchTaskJpaRepository extends JpaRepository<MatchTask, String> {
    Optional<MatchTask> findByCreatorIdAndIdempotencyKey(String creatorId, String idempotencyKey);
    Optional<MatchTask> findFirstByResumeIdAndRevisionIdOrderByCreatedAtDesc(String resumeId, String revisionId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from MatchTask t where t.id = :id")
    Optional<MatchTask> findByIdForUpdate(@Param("id") String id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from MatchTask t where t.resumeId = :resumeId and t.state in :states order by t.id")
    List<MatchTask> findByResumeIdAndStateInForUpdate(@Param("resumeId") String resumeId,
                                                       @Param("states") Collection<MatchTask.State> states);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from MatchTask t where t.revisionId = :revisionId and t.state in :states order by t.id")
    List<MatchTask> findByRevisionIdAndStateInForUpdate(@Param("revisionId") String revisionId,
                                                         @Param("states") Collection<MatchTask.State> states);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from MatchTask t where t.state = :state and t.updatedAt < :cutoff order by t.id")
    List<MatchTask> findByStateAndUpdatedAtBeforeForUpdate(@Param("state") MatchTask.State state,
                                                            @Param("cutoff") Instant cutoff);
}
