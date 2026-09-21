package com.resumethinking.platform.resumes;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ResumeRevisionJpaRepository extends JpaRepository<ResumeRevision, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select revision from ResumeRevision revision where revision.id = :id")
    Optional<ResumeRevision> findByIdForUpdate(@Param("id") String id);

    Optional<ResumeRevision> findFirstByResumeIdOrderByRevisionNoDesc(String resumeId);
}
