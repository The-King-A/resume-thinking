package com.resumethinking.platform.resumes;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ResumeAuditJpaRepository extends JpaRepository<ResumeAudit, Long> {}
