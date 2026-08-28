-- 简历匹配平台 MySQL 建表脚本


CREATE DATABASE IF NOT EXISTS `resume_thinking`
    DEFAULT CHARACTER SET utf8mb4
    COLLATE utf8mb4_0900_ai_ci;

USE `resume_thinking`;

CREATE TABLE users (
    id BINARY(16) NOT NULL PRIMARY KEY,
    username VARCHAR(64) NOT NULL UNIQUE,
    email VARCHAR(254) NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    role VARCHAR(16) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE llm_profiles (
    id BINARY(16) NOT NULL PRIMARY KEY,
    owner_id BINARY(16) NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    endpoint_url VARCHAR(2048) NOT NULL,
    model_name VARCHAR(200) NOT NULL,
    api_key_ciphertext BLOB NOT NULL,
    api_key_nonce VARBINARY(12) NOT NULL,
    key_version INT NOT NULL,
    selected BOOLEAN NOT NULL DEFAULT FALSE,
    last_test_status VARCHAR(16) NULL,
    last_tested_at TIMESTAMP(6) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_llm_profiles_owner
        FOREIGN KEY (owner_id) REFERENCES users(id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX ix_llm_profiles_owner ON llm_profiles(owner_id);

CREATE TABLE resumes (
    id BINARY(16) NOT NULL PRIMARY KEY,
    owner_id BINARY(16) NOT NULL,
    title VARCHAR(200) NOT NULL,
    source_type VARCHAR(8) NOT NULL,
    parser_version VARCHAR(64) NOT NULL DEFAULT 'v1',
    raw_content_ciphertext MEDIUMBLOB NOT NULL,
    raw_content_nonce VARBINARY(12) NULL,
    creator_role VARCHAR(16) NOT NULL,
    status TINYINT NOT NULL DEFAULT 0,
    visibility_state VARCHAR(32) NOT NULL,
    visible_until TIMESTAMP(6) NULL,
    soft_deleted_by BINARY(16) NULL,
    soft_deleted_at TIMESTAMP(6) NULL,
    archived_at TIMESTAMP(6) NULL,
    restored_at TIMESTAMP(6) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_resumes_owner
        FOREIGN KEY (owner_id) REFERENCES users(id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX ix_resumes_owner_visibility
    ON resumes(owner_id, visibility_state, id);
CREATE INDEX ix_resumes_due
    ON resumes(visibility_state, visible_until);

CREATE TABLE resume_recovery_audit (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    resume_id BINARY(16) NOT NULL,
    actor_id BINARY(16) NULL,
    action VARCHAR(32) NOT NULL,
    prior_visibility_state VARCHAR(32) NOT NULL,
    new_visibility_state VARCHAR(32) NOT NULL,
    -- 为兼容旧审计记录保留；新记录使用前后状态字段。
    visibility_state VARCHAR(32) NULL,
    occurred_at TIMESTAMP(6) NOT NULL,
    correlation_id BINARY(16) NOT NULL,
    CONSTRAINT fk_resume_audit_resume
        FOREIGN KEY (resume_id) REFERENCES resumes(id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX ix_resume_audit_resume_occurred
    ON resume_recovery_audit(resume_id, occurred_at);

CREATE TABLE analysis_tasks (
    id BINARY(16) NOT NULL PRIMARY KEY,
    resume_id BINARY(16) NOT NULL,
    llm_profile_id BINARY(16) NOT NULL,
    creator_id BINARY(16) NOT NULL,
    resume_version BIGINT NOT NULL,
    job_family VARCHAR(32) NOT NULL DEFAULT 'JAVA_BACKEND',
    job_description_text MEDIUMTEXT NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    attempt INT NOT NULL DEFAULT 1,
    callback_token_hash CHAR(64) NOT NULL,
    state VARCHAR(16) NOT NULL,
    failure_code VARCHAR(64) NULL,
    result_available BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_analysis_task_owner_key
        UNIQUE (creator_id, idempotency_key),
    CONSTRAINT fk_analysis_task_resume
        FOREIGN KEY (resume_id) REFERENCES resumes(id),
    CONSTRAINT fk_analysis_task_profile
        FOREIGN KEY (llm_profile_id) REFERENCES llm_profiles(id),
    CONSTRAINT fk_analysis_task_creator
        FOREIGN KEY (creator_id) REFERENCES users(id),
    CONSTRAINT chk_analysis_task_job_description_length
        CHECK (CHAR_LENGTH(job_description_text) <= 20000),
    CONSTRAINT chk_analysis_task_job_family
        CHECK (job_family IN ('JAVA_BACKEND'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX ix_analysis_tasks_resume
    ON analysis_tasks(resume_id, state);

CREATE TABLE analysis_evidence (
    id BINARY(16) NOT NULL PRIMARY KEY,
    task_id BINARY(16) NOT NULL,
    source_location VARCHAR(500) NOT NULL,
    source_start INT NOT NULL,
    source_end INT NOT NULL,
    source_excerpt TEXT NULL,
    source_type VARCHAR(8) NOT NULL DEFAULT 'TXT',
    CONSTRAINT fk_analysis_evidence_task
        FOREIGN KEY (task_id) REFERENCES analysis_tasks(id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE analysis_results (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id BINARY(16) NOT NULL UNIQUE,
    resume_id BINARY(16) NOT NULL,
    resume_version BIGINT NOT NULL,
    payload_json JSON NOT NULL,
    completed_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_analysis_result_task
        FOREIGN KEY (task_id) REFERENCES analysis_tasks(id),
    CONSTRAINT fk_analysis_result_resume
        FOREIGN KEY (resume_id) REFERENCES resumes(id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE analysis_callback_receipts (
    callback_id BINARY(16) NOT NULL PRIMARY KEY,
    payload_hash CHAR(64) NOT NULL,
    received_at TIMESTAMP(6) NOT NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
