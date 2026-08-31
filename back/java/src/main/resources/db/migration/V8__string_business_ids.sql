-- V8: deterministic migration from V1-V7 UUID/numeric business IDs to v2 strings.
-- Run only after backup and write quiescence. Sensitive payload columns are never selected.
-- resume_recovery_audit.correlation_id BINARY(16) remains a UUID trace identifier.
-- UUID maps use ROW_NUMBER() OVER (ORDER BY created_at, old_id); old_id is the source table id.
-- Numeric result/audit maps use ORDER BY old_id; callbacks use ORDER BY received_at, callback_id.

CREATE TABLE id_sequences (
    sequence_name VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    next_value BIGINT NOT NULL
) ENGINE=InnoDB;

CREATE TEMPORARY TABLE user_id_map (old_id BINARY(16) PRIMARY KEY, new_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE);
INSERT INTO user_id_map SELECT id, CONCAT('user', LPAD(ROW_NUMBER() OVER (ORDER BY created_at, id), 3, '0')) FROM users;
CREATE TEMPORARY TABLE profile_id_map (old_id BINARY(16) PRIMARY KEY, new_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE);
INSERT INTO profile_id_map SELECT id, CONCAT('profile', LPAD(ROW_NUMBER() OVER (ORDER BY created_at, id), 3, '0')) FROM llm_profiles;
CREATE TEMPORARY TABLE resume_id_map (old_id BINARY(16) PRIMARY KEY, new_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE);
INSERT INTO resume_id_map SELECT id, CONCAT('resume', LPAD(ROW_NUMBER() OVER (ORDER BY created_at, id), 3, '0')) FROM resumes;
CREATE TEMPORARY TABLE task_id_map (old_id BINARY(16) PRIMARY KEY, new_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE);
INSERT INTO task_id_map SELECT id, CONCAT('task', LPAD(ROW_NUMBER() OVER (ORDER BY created_at, id), 3, '0')) FROM analysis_tasks;
CREATE TEMPORARY TABLE evidence_id_map (old_id BINARY(16) PRIMARY KEY, new_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE);
INSERT INTO evidence_id_map SELECT id, CONCAT('evidence', LPAD(ROW_NUMBER() OVER (ORDER BY id), 3, '0')) FROM analysis_evidence;
CREATE TEMPORARY TABLE result_id_map (old_id BIGINT PRIMARY KEY, new_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE);
INSERT INTO result_id_map SELECT id, CONCAT('result', LPAD(ROW_NUMBER() OVER (ORDER BY id), 3, '0')) FROM analysis_results;
CREATE TEMPORARY TABLE callback_id_map (old_id BINARY(16) PRIMARY KEY, new_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE);
INSERT INTO callback_id_map SELECT callback_id, CONCAT('callback', LPAD(ROW_NUMBER() OVER (ORDER BY received_at, callback_id), 3, '0')) FROM analysis_callback_receipts;
CREATE TEMPORARY TABLE audit_id_map (old_id BIGINT PRIMARY KEY, new_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE);
INSERT INTO audit_id_map SELECT id, CONCAT('audit', LPAD(ROW_NUMBER() OVER (ORDER BY id), 3, '0')) FROM resume_recovery_audit;

-- Mapping completeness checks intentionally exclude password_hash, raw_content_ciphertext and payload_json.
CREATE TEMPORARY TABLE v8_mapping_check (missing_count BIGINT NOT NULL CHECK (missing_count = 0));
INSERT INTO v8_mapping_check SELECT COUNT(*) FROM llm_profiles p LEFT JOIN user_id_map u ON u.old_id = p.owner_id WHERE u.new_id IS NULL;
INSERT INTO v8_mapping_check SELECT COUNT(*) FROM resumes r LEFT JOIN user_id_map u ON u.old_id = r.owner_id WHERE u.new_id IS NULL;
INSERT INTO v8_mapping_check SELECT COUNT(*) FROM analysis_tasks t LEFT JOIN resume_id_map r ON r.old_id = t.resume_id LEFT JOIN profile_id_map p ON p.old_id = t.llm_profile_id LEFT JOIN user_id_map u ON u.old_id = t.creator_id WHERE r.new_id IS NULL OR p.new_id IS NULL OR u.new_id IS NULL;
INSERT INTO v8_mapping_check SELECT COUNT(*) FROM analysis_evidence e LEFT JOIN task_id_map t ON t.old_id = e.task_id WHERE t.new_id IS NULL;
INSERT INTO v8_mapping_check SELECT COUNT(*) FROM analysis_results r LEFT JOIN task_id_map t ON t.old_id = r.task_id LEFT JOIN resume_id_map v ON v.old_id = r.resume_id WHERE t.new_id IS NULL OR v.new_id IS NULL;
INSERT INTO v8_mapping_check SELECT COUNT(*) FROM resume_recovery_audit a LEFT JOIN resume_id_map r ON r.old_id = a.resume_id LEFT JOIN user_id_map u ON u.old_id = a.actor_id WHERE r.new_id IS NULL OR (a.actor_id IS NOT NULL AND u.new_id IS NULL);

ALTER TABLE users ADD COLUMN id_v2 VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL;
ALTER TABLE llm_profiles ADD COLUMN id_v2 VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL, ADD COLUMN owner_id_v2 VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL;
ALTER TABLE resumes ADD COLUMN id_v2 VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL, ADD COLUMN owner_id_v2 VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL, ADD COLUMN soft_deleted_by_v2 VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL;
ALTER TABLE resume_recovery_audit ADD COLUMN id_v2 VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL, ADD COLUMN resume_id_v2 VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL, ADD COLUMN actor_id_v2 VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL;
ALTER TABLE analysis_tasks ADD COLUMN id_v2 VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL, ADD COLUMN resume_id_v2 VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL, ADD COLUMN llm_profile_id_v2 VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL, ADD COLUMN creator_id_v2 VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL;
ALTER TABLE analysis_evidence ADD COLUMN id_v2 VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL, ADD COLUMN task_id_v2 VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL;
ALTER TABLE analysis_results ADD COLUMN id_v2 VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL, ADD COLUMN task_id_v2 VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL, ADD COLUMN resume_id_v2 VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL;
ALTER TABLE analysis_callback_receipts ADD COLUMN callback_id_v2 VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL;

UPDATE users u JOIN user_id_map m ON m.old_id = u.id SET u.id_v2 = m.new_id;
UPDATE llm_profiles p JOIN profile_id_map m ON m.old_id = p.id SET p.id_v2 = m.new_id;
UPDATE llm_profiles p JOIN user_id_map m ON m.old_id = p.owner_id SET p.owner_id_v2 = m.new_id;
UPDATE resumes r JOIN resume_id_map m ON m.old_id = r.id SET r.id_v2 = m.new_id;
UPDATE resumes r JOIN user_id_map m ON m.old_id = r.owner_id SET r.owner_id_v2 = m.new_id;
UPDATE resumes r JOIN user_id_map m ON m.old_id = r.soft_deleted_by SET r.soft_deleted_by_v2 = m.new_id;
UPDATE resume_recovery_audit a JOIN audit_id_map m ON m.old_id = a.id SET a.id_v2 = m.new_id;
UPDATE resume_recovery_audit a JOIN resume_id_map m ON m.old_id = a.resume_id SET a.resume_id_v2 = m.new_id;
UPDATE resume_recovery_audit a JOIN user_id_map m ON m.old_id = a.actor_id SET a.actor_id_v2 = m.new_id;
UPDATE analysis_tasks t JOIN task_id_map m ON m.old_id = t.id SET t.id_v2 = m.new_id;
UPDATE analysis_tasks t JOIN resume_id_map m ON m.old_id = t.resume_id SET t.resume_id_v2 = m.new_id;
UPDATE analysis_tasks t JOIN profile_id_map m ON m.old_id = t.llm_profile_id SET t.llm_profile_id_v2 = m.new_id;
UPDATE analysis_tasks t JOIN user_id_map m ON m.old_id = t.creator_id SET t.creator_id_v2 = m.new_id;
UPDATE analysis_evidence e JOIN evidence_id_map m ON m.old_id = e.id SET e.id_v2 = m.new_id;
UPDATE analysis_evidence e JOIN task_id_map m ON m.old_id = e.task_id SET e.task_id_v2 = m.new_id;
UPDATE analysis_results r JOIN result_id_map m ON m.old_id = r.id SET r.id_v2 = m.new_id;
UPDATE analysis_results r JOIN task_id_map m ON m.old_id = r.task_id SET r.task_id_v2 = m.new_id;
UPDATE analysis_results r JOIN resume_id_map m ON m.old_id = r.resume_id SET r.resume_id_v2 = m.new_id;
UPDATE analysis_callback_receipts c JOIN callback_id_map m ON m.old_id = c.callback_id SET c.callback_id_v2 = m.new_id;

-- Drop every known V1-V7 relationship/index before replacing the key columns.
ALTER TABLE llm_profiles DROP FOREIGN KEY fk_llm_profiles_owner;
ALTER TABLE resumes DROP FOREIGN KEY fk_resumes_owner;
ALTER TABLE resume_recovery_audit DROP FOREIGN KEY fk_resume_audit_resume;
ALTER TABLE analysis_tasks DROP FOREIGN KEY fk_analysis_task_resume, DROP FOREIGN KEY fk_analysis_task_profile, DROP FOREIGN KEY fk_analysis_task_creator;
ALTER TABLE analysis_evidence DROP FOREIGN KEY fk_analysis_evidence_task;
ALTER TABLE analysis_results DROP FOREIGN KEY fk_analysis_result_task, DROP FOREIGN KEY fk_analysis_result_resume;
ALTER TABLE llm_profiles DROP INDEX ix_llm_profiles_owner;
ALTER TABLE resumes DROP INDEX ix_resumes_owner_visibility, DROP INDEX ix_resumes_due;
ALTER TABLE resume_recovery_audit DROP INDEX ix_resume_audit_resume_occurred;
ALTER TABLE analysis_tasks DROP INDEX ix_analysis_tasks_resume, DROP INDEX uq_analysis_task_owner_key;

ALTER TABLE users DROP PRIMARY KEY, DROP COLUMN id, CHANGE COLUMN id_v2 id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY COMMENT '用户唯一标识（v2字符串）';
ALTER TABLE llm_profiles DROP PRIMARY KEY, DROP COLUMN id, DROP COLUMN owner_id, CHANGE COLUMN id_v2 id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY COMMENT '模型配置唯一标识（v2字符串）', CHANGE COLUMN owner_id_v2 owner_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属用户标识';
ALTER TABLE resumes DROP PRIMARY KEY, DROP COLUMN id, DROP COLUMN owner_id, DROP COLUMN soft_deleted_by, CHANGE COLUMN id_v2 id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY COMMENT '简历唯一标识（v2字符串）', CHANGE COLUMN owner_id_v2 owner_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '简历所有者标识', CHANGE COLUMN soft_deleted_by_v2 soft_deleted_by VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '执行软删除的用户标识';
ALTER TABLE resume_recovery_audit DROP PRIMARY KEY, DROP COLUMN id, DROP COLUMN resume_id, DROP COLUMN actor_id, CHANGE COLUMN id_v2 id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY COMMENT '恢复审计记录唯一标识', CHANGE COLUMN resume_id_v2 resume_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '关联简历标识', CHANGE COLUMN actor_id_v2 actor_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '执行操作的用户标识，系统归档时为空';
ALTER TABLE analysis_tasks DROP PRIMARY KEY, DROP COLUMN id, DROP COLUMN resume_id, DROP COLUMN llm_profile_id, DROP COLUMN creator_id, CHANGE COLUMN id_v2 id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY COMMENT '分析任务唯一标识', CHANGE COLUMN resume_id_v2 resume_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '关联简历标识', CHANGE COLUMN llm_profile_id_v2 llm_profile_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '使用的模型配置标识', CHANGE COLUMN creator_id_v2 creator_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '任务创建者标识';
ALTER TABLE analysis_evidence DROP PRIMARY KEY, DROP COLUMN id, DROP COLUMN task_id, CHANGE COLUMN id_v2 id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY COMMENT '证据唯一标识', CHANGE COLUMN task_id_v2 task_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '关联分析任务标识';
ALTER TABLE analysis_results DROP COLUMN id, DROP COLUMN task_id, DROP COLUMN resume_id, CHANGE COLUMN id_v2 id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY COMMENT '分析结果唯一标识', CHANGE COLUMN task_id_v2 task_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE COMMENT '关联分析任务标识', CHANGE COLUMN resume_id_v2 resume_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '关联简历标识';
ALTER TABLE analysis_callback_receipts DROP PRIMARY KEY, DROP COLUMN callback_id, CHANGE COLUMN callback_id_v2 callback_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY COMMENT '回调消息唯一标识';

ALTER TABLE llm_profiles ADD CONSTRAINT fk_llm_profiles_owner FOREIGN KEY (owner_id) REFERENCES users(id);
ALTER TABLE resumes ADD CONSTRAINT fk_resumes_owner FOREIGN KEY (owner_id) REFERENCES users(id);
ALTER TABLE resume_recovery_audit ADD CONSTRAINT fk_resume_audit_resume FOREIGN KEY (resume_id) REFERENCES resumes(id);
ALTER TABLE analysis_tasks ADD CONSTRAINT uq_analysis_task_owner_key UNIQUE (creator_id, idempotency_key), ADD CONSTRAINT fk_analysis_task_resume FOREIGN KEY (resume_id) REFERENCES resumes(id), ADD CONSTRAINT fk_analysis_task_profile FOREIGN KEY (llm_profile_id) REFERENCES llm_profiles(id), ADD CONSTRAINT fk_analysis_task_creator FOREIGN KEY (creator_id) REFERENCES users(id);
ALTER TABLE analysis_evidence ADD CONSTRAINT fk_analysis_evidence_task FOREIGN KEY (task_id) REFERENCES analysis_tasks(id);
ALTER TABLE analysis_results ADD CONSTRAINT fk_analysis_result_task FOREIGN KEY (task_id) REFERENCES analysis_tasks(id), ADD CONSTRAINT fk_analysis_result_resume FOREIGN KEY (resume_id) REFERENCES resumes(id);
CREATE INDEX ix_llm_profiles_owner ON llm_profiles(owner_id);
CREATE INDEX ix_resumes_owner_visibility ON resumes(owner_id, visibility_state, id);
CREATE INDEX ix_resumes_due ON resumes(visibility_state, visible_until);
CREATE INDEX ix_resume_audit_resume_occurred ON resume_recovery_audit(resume_id, occurred_at);
CREATE INDEX ix_analysis_tasks_resume ON analysis_tasks(resume_id, state);

INSERT INTO id_sequences(sequence_name, next_value)
SELECT 'user', COALESCE(MAX(CAST(SUBSTRING(id, 5) AS UNSIGNED)), 0) + 1 FROM users
UNION ALL SELECT 'profile', COALESCE(MAX(CAST(SUBSTRING(id, 8) AS UNSIGNED)), 0) + 1 FROM llm_profiles
UNION ALL SELECT 'resume', COALESCE(MAX(CAST(SUBSTRING(id, 7) AS UNSIGNED)), 0) + 1 FROM resumes
UNION ALL SELECT 'task', COALESCE(MAX(CAST(SUBSTRING(id, 5) AS UNSIGNED)), 0) + 1 FROM analysis_tasks
UNION ALL SELECT 'evidence', COALESCE(MAX(CAST(SUBSTRING(id, 8) AS UNSIGNED)), 0) + 1 FROM analysis_evidence
UNION ALL SELECT 'result', COALESCE(MAX(CAST(SUBSTRING(id, 7) AS UNSIGNED)), 0) + 1 FROM analysis_results
UNION ALL SELECT 'callback', COALESCE(MAX(CAST(SUBSTRING(callback_id, 9) AS UNSIGNED)), 0) + 1 FROM analysis_callback_receipts
UNION ALL SELECT 'audit', COALESCE(MAX(CAST(SUBSTRING(id, 6) AS UNSIGNED)), 0) + 1 FROM resume_recovery_audit;

DROP TEMPORARY TABLE v8_mapping_check;
DROP TEMPORARY TABLE user_id_map, profile_id_map, resume_id_map, task_id_map, evidence_id_map, result_id_map, callback_id_map, audit_id_map;
