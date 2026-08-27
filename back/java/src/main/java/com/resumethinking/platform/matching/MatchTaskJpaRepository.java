package com.resumethinking.platform.matching;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface MatchTaskJpaRepository extends JpaRepository<MatchTask, UUID> {
    Optional<MatchTask> findByCreatorIdAndIdempotencyKey(UUID creatorId, String idempotencyKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from MatchTask t where t.id = :id")
    Optional<MatchTask> findByIdForUpdate(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from MatchTask t where t.resumeId = :resumeId and t.state in :states order by t.id")
    List<MatchTask> findByResumeIdAndStateInForUpdate(@Param("resumeId") UUID resumeId,
                                                       @Param("states") Collection<MatchTask.State> states);
}
