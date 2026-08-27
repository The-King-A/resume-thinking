-- Existing installations created before the explicit role-family contract
-- receive the same default as fresh V3 installations. The MODIFY moves the
-- legacy VARCHAR column to off-page-capable MEDIUMTEXT before enforcing the
-- application limit in characters.
ALTER TABLE analysis_tasks
    ADD COLUMN job_family VARCHAR(32) NOT NULL DEFAULT 'JAVA_BACKEND' AFTER resume_version,
    MODIFY COLUMN job_description_text MEDIUMTEXT NOT NULL,
    ADD CONSTRAINT chk_analysis_task_job_description_length
        CHECK (CHAR_LENGTH(job_description_text) <= 20000);

ALTER TABLE analysis_tasks
    ADD CONSTRAINT chk_analysis_task_job_family
        CHECK (job_family IN ('JAVA_BACKEND'));
