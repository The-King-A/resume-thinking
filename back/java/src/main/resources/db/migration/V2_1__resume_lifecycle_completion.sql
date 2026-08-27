ALTER TABLE resumes
    ADD COLUMN parser_version VARCHAR(64) NOT NULL DEFAULT 'v1' AFTER source_type;

ALTER TABLE resume_recovery_audit
    ADD COLUMN prior_visibility_state VARCHAR(32) NULL AFTER action,
    ADD COLUMN new_visibility_state VARCHAR(32) NULL AFTER prior_visibility_state,
    ADD COLUMN correlation_id BINARY(16) NULL AFTER occurred_at;

UPDATE resume_recovery_audit
SET prior_visibility_state = visibility_state,
    new_visibility_state = visibility_state,
    correlation_id = UUID_TO_BIN(UUID())
WHERE prior_visibility_state IS NULL
   OR new_visibility_state IS NULL
   OR correlation_id IS NULL;

ALTER TABLE resume_recovery_audit
    MODIFY COLUMN prior_visibility_state VARCHAR(32) NOT NULL,
    MODIFY COLUMN new_visibility_state VARCHAR(32) NOT NULL,
    MODIFY COLUMN correlation_id BINARY(16) NOT NULL,
    MODIFY COLUMN visibility_state VARCHAR(32) NULL;

CREATE INDEX ix_resume_audit_resume_occurred
    ON resume_recovery_audit(resume_id, occurred_at);
