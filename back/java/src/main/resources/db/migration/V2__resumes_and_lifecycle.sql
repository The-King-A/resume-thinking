CREATE TABLE resumes (
 id BINARY(16) NOT NULL PRIMARY KEY,
 owner_id BINARY(16) NOT NULL,
 title VARCHAR(200) NOT NULL,
 source_type VARCHAR(8) NOT NULL,
 parser_version VARCHAR(64) NOT NULL,
 raw_content_ciphertext BLOB NOT NULL,
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
 CONSTRAINT fk_resumes_owner FOREIGN KEY (owner_id) REFERENCES users(id)
);
CREATE INDEX ix_resumes_owner_visibility ON resumes(owner_id, visibility_state, id);
CREATE INDEX ix_resumes_due ON resumes(visibility_state, visible_until);
CREATE TABLE resume_recovery_audit (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 resume_id BINARY(16) NOT NULL,
 actor_id BINARY(16) NULL,
 action VARCHAR(32) NOT NULL,
 prior_visibility_state VARCHAR(32) NOT NULL,
 new_visibility_state VARCHAR(32) NOT NULL,
 occurred_at TIMESTAMP(6) NOT NULL,
 correlation_id BINARY(16) NOT NULL,
 CONSTRAINT fk_resume_audit_resume FOREIGN KEY (resume_id) REFERENCES resumes(id)
 );
CREATE INDEX ix_resume_audit_resume_occurred ON resume_recovery_audit(resume_id, occurred_at);
