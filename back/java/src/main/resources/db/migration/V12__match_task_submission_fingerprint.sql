-- V12: durable semantic identity for v3 submission idempotency.
-- Legacy v2 rows remain NULL because their historical upload/title inputs
-- cannot be reconstructed unambiguously. Every new v3 task stores a SHA-256
-- fingerprint before it can win the existing creator/key unique constraint.
ALTER TABLE analysis_tasks
    ADD COLUMN submission_fingerprint CHAR(64) NULL COMMENT 'v3匹配提交规范化请求的SHA-256指纹' AFTER idempotency_key;
