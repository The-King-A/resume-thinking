CREATE TABLE analysis_tasks (
    id BINARY(16) NOT NULL PRIMARY KEY,
    resume_id BINARY(16) NOT NULL,
    llm_profile_id BINARY(16) NOT NULL,
    creator_id BINARY(16) NOT NULL,
    resume_version BIGINT NOT NULL,
    job_description_text VARCHAR(20000) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    attempt INT NOT NULL DEFAULT 1,
    callback_token_hash CHAR(64) NOT NULL,
    state VARCHAR(16) NOT NULL,
    failure_code VARCHAR(64) NULL,
    result_available BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_analysis_task_owner_key UNIQUE (creator_id, idempotency_key),
    CONSTRAINT fk_analysis_task_resume FOREIGN KEY (resume_id) REFERENCES resumes(id),
    CONSTRAINT fk_analysis_task_profile FOREIGN KEY (llm_profile_id) REFERENCES llm_profiles(id),
    CONSTRAINT fk_analysis_task_creator FOREIGN KEY (creator_id) REFERENCES users(id)
);

ALTER TABLE resumes ADD COLUMN raw_content_nonce VARBINARY(12) NULL AFTER raw_content_ciphertext;
CREATE INDEX ix_analysis_tasks_resume ON analysis_tasks(resume_id, state);

CREATE TABLE analysis_evidence (
    id BINARY(16) NOT NULL PRIMARY KEY,
    task_id BINARY(16) NOT NULL,
    source_location VARCHAR(500) NOT NULL,
    source_start INT NOT NULL,
    source_end INT NOT NULL,
    CONSTRAINT fk_analysis_evidence_task FOREIGN KEY (task_id) REFERENCES analysis_tasks(id)
);

CREATE TABLE analysis_results (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id BINARY(16) NOT NULL UNIQUE,
    resume_id BINARY(16) NOT NULL,
    resume_version BIGINT NOT NULL,
    payload_json JSON NOT NULL,
    completed_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_analysis_result_task FOREIGN KEY (task_id) REFERENCES analysis_tasks(id),
    CONSTRAINT fk_analysis_result_resume FOREIGN KEY (resume_id) REFERENCES resumes(id)
);

CREATE TABLE analysis_callback_receipts (
    callback_id BINARY(16) NOT NULL PRIMARY KEY,
    payload_hash CHAR(64) NOT NULL,
    received_at TIMESTAMP(6) NOT NULL
);
