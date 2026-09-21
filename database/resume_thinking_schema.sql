-- v3 手工建表快照：仅用于空数据库。已有数据必须按顺序执行 Flyway 迁移。
-- 执行前请备份；不要手工创建 flyway_schema_history。
CREATE DATABASE IF NOT EXISTS `resume_thinking` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
USE `resume_thinking`;

CREATE TABLE id_sequences (
    sequence_name VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY COMMENT '业务 ID 序列前缀',
    next_value BIGINT NOT NULL COMMENT '下一个可分配的数字后缀'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
INSERT INTO id_sequences(sequence_name, next_value) VALUES ('user',1),('profile',1),('resume',1),('revision',1),('task',1),('evidence',1),('result',1),('callback',1),('audit',1),('session',1),('question',1),('answer',1),('feedback',1),('confirmation',1);

CREATE TABLE users (
 id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY COMMENT '用户唯一标识（v2字符串）',
 username VARCHAR(64) NOT NULL UNIQUE COMMENT '登录用户名', email VARCHAR(254) NOT NULL UNIQUE COMMENT '用户邮箱地址',
 password_hash VARCHAR(100) NOT NULL COMMENT 'BCrypt密码哈希，不可逆', role VARCHAR(16) NOT NULL COMMENT '用户角色：USER普通用户或ADMIN管理员', created_at TIMESTAMP(6) NOT NULL COMMENT '账号创建时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE llm_profiles (
 id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY COMMENT '模型配置唯一标识（v2字符串）', owner_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属用户标识',
 display_name VARCHAR(100) NOT NULL COMMENT '配置显示名称', endpoint_url VARCHAR(2048) NOT NULL COMMENT 'OpenAI兼容接口地址', model_name VARCHAR(200) NOT NULL COMMENT '模型名称', api_key_ciphertext BLOB NOT NULL COMMENT 'AES-GCM加密后的API密钥', api_key_nonce VARBINARY(12) NOT NULL COMMENT 'API密钥加密随机数（Nonce）', key_version INT NOT NULL COMMENT '加密密钥版本', selected BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否为默认模型配置', last_test_status VARCHAR(16) NULL COMMENT '最近一次连接测试状态', last_tested_at TIMESTAMP(6) NULL COMMENT '最近一次连接测试时间', created_at TIMESTAMP(6) NOT NULL COMMENT '配置创建时间', updated_at TIMESTAMP(6) NOT NULL COMMENT '配置最后更新时间',
 CONSTRAINT fk_llm_profiles_owner FOREIGN KEY (owner_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE INDEX ix_llm_profiles_owner ON llm_profiles(owner_id);

CREATE TABLE resumes (
 id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY COMMENT '简历唯一标识（v2字符串）', owner_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '简历所有者标识', effective_revision_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '当前已发布并可展示的简历修订版本标识', pending_revision_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '等待匹配验证的候选简历修订版本标识', effective_title_key VARCHAR(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL COMMENT '仅有效且可见简历使用的Unicode归一化标题唯一键', title VARCHAR(200) NOT NULL COMMENT '简历名称', source_type VARCHAR(8) NOT NULL COMMENT '文件类型：TXT或DOCX', parser_version VARCHAR(64) NOT NULL DEFAULT 'v1' COMMENT '解析器版本', raw_content_ciphertext MEDIUMBLOB NOT NULL COMMENT 'AES-GCM加密后的简历原文', raw_content_nonce VARBINARY(12) NULL COMMENT '简历原文加密随机数（Nonce）', creator_role VARCHAR(16) NOT NULL COMMENT '创建者角色，决定默认留存期限', status TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记：0未删除，1已软删除', visibility_state VARCHAR(32) NOT NULL COMMENT '页面可见性状态', visible_until TIMESTAMP(6) NULL COMMENT '页面可见截止时间', soft_deleted_by VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '执行软删除的用户标识', soft_deleted_at TIMESTAMP(6) NULL COMMENT '软删除时间', archived_at TIMESTAMP(6) NULL COMMENT '缓存过期归档时间', restored_at TIMESTAMP(6) NULL COMMENT '最近一次恢复时间', created_at TIMESTAMP(6) NOT NULL COMMENT '简历创建时间', updated_at TIMESTAMP(6) NOT NULL COMMENT '简历最后更新时间', version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
 UNIQUE KEY uq_resumes_owner_effective_title (owner_id, effective_title_key), CONSTRAINT fk_resumes_owner FOREIGN KEY (owner_id) REFERENCES users(id), CONSTRAINT fk_resumes_soft_deleted_by FOREIGN KEY (soft_deleted_by) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE INDEX ix_resumes_owner_visibility ON resumes(owner_id, visibility_state, id);
CREATE INDEX ix_resumes_due ON resumes(visibility_state, visible_until);

CREATE TABLE resume_revisions (
 id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY COMMENT '简历修订版本唯一标识', resume_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属逻辑简历标识', revision_no BIGINT NOT NULL COMMENT '逻辑简历内单调递增的修订序号', title VARCHAR(200) NOT NULL COMMENT '该修订版本的简历标题', title_key VARCHAR(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '应用规范化后的标题唯一键', source_type VARCHAR(8) NOT NULL COMMENT '该修订版本的文件类型：TXT或DOCX', parser_version VARCHAR(64) NOT NULL COMMENT '该修订版本使用的解析器版本', raw_content_ciphertext MEDIUMBLOB NOT NULL COMMENT 'AES-GCM加密后的修订版本原文', raw_content_nonce VARBINARY(12) NULL COMMENT '修订版本原文加密随机数（Nonce）', state VARCHAR(16) NOT NULL COMMENT '修订状态：PENDING、EFFECTIVE、FAILED或SUPERSEDED', created_at TIMESTAMP(6) NOT NULL COMMENT '修订版本创建时间',
 UNIQUE KEY uq_resume_revisions_resume_revision_no (resume_id, revision_no), UNIQUE KEY uq_resume_revisions_resume_id_id (resume_id, id), CONSTRAINT fk_resume_revision_resume FOREIGN KEY (resume_id) REFERENCES resumes(id), CONSTRAINT chk_resume_revision_state CHECK (state IN ('PENDING', 'EFFECTIVE', 'FAILED', 'SUPERSEDED')), CONSTRAINT chk_resume_revision_source_type CHECK (source_type IN ('TXT', 'DOCX'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
ALTER TABLE resumes ADD CONSTRAINT fk_resume_effective_revision FOREIGN KEY (id, effective_revision_id) REFERENCES resume_revisions(resume_id, id), ADD CONSTRAINT fk_resume_pending_revision FOREIGN KEY (id, pending_revision_id) REFERENCES resume_revisions(resume_id, id);

CREATE TABLE resume_recovery_audit (
 id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY COMMENT '恢复审计记录唯一标识', resume_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '关联简历标识', actor_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '执行操作的用户标识，系统归档时为空', action VARCHAR(32) NOT NULL COMMENT '执行的生命周期操作', prior_visibility_state VARCHAR(32) NOT NULL COMMENT '操作前可见性状态', new_visibility_state VARCHAR(32) NOT NULL COMMENT '操作后可见性状态', visibility_state VARCHAR(32) NULL COMMENT '兼容旧数据的历史状态字段', occurred_at TIMESTAMP(6) NOT NULL COMMENT '审计事件发生时间', correlation_id BINARY(16) NOT NULL COMMENT '关联请求标识（UUID二进制）',
 CONSTRAINT fk_resume_audit_resume FOREIGN KEY (resume_id) REFERENCES resumes(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE INDEX ix_resume_audit_resume_occurred ON resume_recovery_audit(resume_id, occurred_at);

CREATE TABLE analysis_tasks (
 id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY COMMENT '分析任务唯一标识', callback_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Java签发的回调消息标识', resume_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '关联简历标识', revision_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '任务绑定的不可变简历修订版本标识', llm_profile_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '使用的模型配置标识', creator_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '任务创建者标识', resume_version BIGINT NOT NULL COMMENT '提交任务时的简历版本', job_family VARCHAR(32) NOT NULL DEFAULT 'JAVA_BACKEND' COMMENT '岗位族，当前为JAVA_BACKEND', job_description_text MEDIUMTEXT NOT NULL COMMENT '岗位描述原文', idempotency_key VARCHAR(128) NOT NULL COMMENT '创建任务幂等键', submission_fingerprint CHAR(64) NULL COMMENT 'v3匹配提交规范化请求的SHA-256指纹', attempt INT NOT NULL DEFAULT 1 COMMENT '当前处理尝试次数', callback_token_hash CHAR(64) NOT NULL COMMENT '内部回调令牌哈希', state VARCHAR(16) NOT NULL COMMENT '任务状态', publication_state VARCHAR(32) NOT NULL DEFAULT 'NOT_REQUESTED' COMMENT '发布状态：NOT_REQUESTED、PENDING、PUBLISHED或REJECTED_DUPLICATE_TITLE', failure_code VARCHAR(64) NULL COMMENT '失败原因编码', result_available BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否已有匹配结果', created_at TIMESTAMP(6) NOT NULL COMMENT '任务创建时间', updated_at TIMESTAMP(6) NOT NULL COMMENT '任务最后更新时间', version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
 CONSTRAINT uq_analysis_task_callback_id UNIQUE (callback_id), CONSTRAINT uq_analysis_task_owner_key UNIQUE (creator_id, idempotency_key), UNIQUE KEY uq_analysis_tasks_id_resume_revision (id, resume_id, revision_id), CONSTRAINT fk_analysis_task_resume FOREIGN KEY (resume_id) REFERENCES resumes(id), CONSTRAINT fk_analysis_task_revision FOREIGN KEY (resume_id, revision_id) REFERENCES resume_revisions(resume_id, id), CONSTRAINT fk_analysis_task_profile FOREIGN KEY (llm_profile_id) REFERENCES llm_profiles(id), CONSTRAINT fk_analysis_task_creator FOREIGN KEY (creator_id) REFERENCES users(id), CONSTRAINT chk_analysis_task_job_description_length CHECK (CHAR_LENGTH(job_description_text) <= 20000), CONSTRAINT chk_analysis_task_job_family CHECK (job_family IN ('JAVA_BACKEND')), CONSTRAINT chk_analysis_task_publication_state CHECK (publication_state IN ('NOT_REQUESTED', 'PENDING', 'PUBLISHED', 'REJECTED_DUPLICATE_TITLE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE INDEX ix_analysis_tasks_resume ON analysis_tasks(resume_id, state);
CREATE INDEX ix_analysis_tasks_revision ON analysis_tasks(revision_id, state);

CREATE TABLE analysis_evidence (
 id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY COMMENT '证据唯一标识', task_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '关联分析任务标识', source_location VARCHAR(500) NOT NULL COMMENT '证据在简历中的来源位置', source_start INT NOT NULL COMMENT '证据起始字符位置', source_end INT NOT NULL COMMENT '证据结束字符位置', source_excerpt TEXT NULL COMMENT '证据原文摘录', source_type VARCHAR(8) NOT NULL DEFAULT 'TXT' COMMENT '证据来源类型：TXT或DOCX', CONSTRAINT fk_analysis_evidence_task FOREIGN KEY (task_id) REFERENCES analysis_tasks(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE analysis_results (
 id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY COMMENT '分析结果唯一标识', task_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE COMMENT '关联分析任务标识', resume_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '关联简历标识', revision_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '结果绑定的不可变简历修订版本标识', resume_version BIGINT NOT NULL COMMENT '生成结果时的简历版本', payload_json JSON NOT NULL COMMENT '经校验的结构化匹配结果', completed_at TIMESTAMP(6) NOT NULL COMMENT '分析完成时间', CONSTRAINT fk_analysis_result_task FOREIGN KEY (task_id) REFERENCES analysis_tasks(id), CONSTRAINT fk_analysis_result_resume FOREIGN KEY (resume_id) REFERENCES resumes(id), CONSTRAINT fk_analysis_result_revision FOREIGN KEY (resume_id, revision_id) REFERENCES resume_revisions(resume_id, id), CONSTRAINT fk_analysis_result_task_revision FOREIGN KEY (task_id, resume_id, revision_id) REFERENCES analysis_tasks(id, resume_id, revision_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE INDEX ix_analysis_results_resume_revision_completed ON analysis_results(resume_id, revision_id, completed_at);

CREATE TABLE analysis_callback_receipts (
 callback_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY COMMENT '回调消息唯一标识', payload_hash CHAR(64) NOT NULL COMMENT '回调负载哈希，用于幂等校验', received_at TIMESTAMP(6) NOT NULL COMMENT '回调接收时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE interview_sessions (
 id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY COMMENT '面试会话唯一标识',
 owner_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '面试会话所属用户标识',
 resume_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '关联的有效简历标识',
 revision_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '关联的有效简历修订版本标识',
 match_task_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '产生面试依据的匹配任务标识',
 llm_profile_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '面试分析使用的模型配置标识',
 job_family VARCHAR(32) NOT NULL COMMENT '岗位族，当前为JAVA_BACKEND',
 state VARCHAR(32) NOT NULL COMMENT '面试会话状态',
 work_type VARCHAR(32) NULL COMMENT '当前异步工作类型',
 idempotency_key VARCHAR(128) NOT NULL COMMENT '创建会话幂等键',
 callback_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '当前异步回调标识',
 callback_token_hash CHAR(64) NULL COMMENT '当前回调令牌哈希，不保存令牌原文',
 attempt INT NOT NULL DEFAULT 0 COMMENT '当前异步尝试次数',
 session_version BIGINT NOT NULL DEFAULT 0 COMMENT '面向客户端的会话版本',
 question_count INT NOT NULL DEFAULT 0 COMMENT '已生成题目数量',
 current_question_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '当前回答题目标识',
 current_answer_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '当前回答标识',
 feedback_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '当前反馈标识',
 failure_code VARCHAR(64) NULL COMMENT '面试失败原因编码',
 created_at TIMESTAMP(6) NOT NULL COMMENT '会话创建时间',
 updated_at TIMESTAMP(6) NOT NULL COMMENT '会话最后更新时间',
 deleted_at TIMESTAMP(6) NULL COMMENT '会话内容清理时间',
 persistence_version BIGINT NOT NULL DEFAULT 0 COMMENT '数据库乐观锁版本',
 UNIQUE KEY uq_interview_sessions_owner_key (owner_id, idempotency_key),
 UNIQUE KEY uq_interview_sessions_callback_id (callback_id),
 CONSTRAINT fk_interview_session_owner FOREIGN KEY (owner_id) REFERENCES users(id),
 CONSTRAINT fk_interview_session_resume FOREIGN KEY (resume_id) REFERENCES resumes(id),
 CONSTRAINT fk_interview_session_revision FOREIGN KEY (resume_id, revision_id) REFERENCES resume_revisions(resume_id, id),
 CONSTRAINT fk_interview_session_task FOREIGN KEY (match_task_id) REFERENCES analysis_tasks(id),
 CONSTRAINT fk_interview_session_profile FOREIGN KEY (llm_profile_id) REFERENCES llm_profiles(id),
 CONSTRAINT chk_interview_session_job_family CHECK (job_family IN ('JAVA_BACKEND')),
 CONSTRAINT chk_interview_session_state CHECK (state IN ('QUESTION_GENERATING', 'WAITING_FOR_ANSWER', 'ANSWER_ANALYZING', 'FEEDBACK_READY', 'COMPLETED', 'FAILED', 'DELETED')),
 CONSTRAINT chk_interview_session_work_type CHECK (work_type IS NULL OR work_type IN ('QUESTION_GENERATION', 'ANSWER_ANALYSIS'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE INDEX ix_interview_sessions_resume_state ON interview_sessions(resume_id, state);
CREATE INDEX ix_interview_sessions_owner_state ON interview_sessions(owner_id, state);

CREATE TABLE interview_questions (
 id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY COMMENT '面试题目唯一标识',
 session_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属面试会话标识',
 sequence_no INT NOT NULL COMMENT '会话内题目序号',
 question_type VARCHAR(32) NOT NULL COMMENT '题目类型',
 difficulty VARCHAR(16) NOT NULL COMMENT '题目难度',
 question_text VARCHAR(2000) NOT NULL COMMENT '题目文本',
 requirement_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '关联岗位要求标识',
 requirement_text VARCHAR(5000) NOT NULL COMMENT '关联岗位要求文本',
 evidence_ids_json JSON NOT NULL COMMENT '允许引用的证据标识数组',
 generation_reason VARCHAR(2000) NOT NULL COMMENT '题目生成原因',
 confidence DECIMAL(5,4) NOT NULL COMMENT '题目来源置信度',
 created_at TIMESTAMP(6) NOT NULL COMMENT '题目创建时间',
 UNIQUE KEY uq_interview_questions_session_sequence (session_id, sequence_no),
 CONSTRAINT fk_interview_question_session FOREIGN KEY (session_id) REFERENCES interview_sessions(id),
 CONSTRAINT chk_interview_question_type CHECK (question_type IN ('BASIC_CONFIRMATION', 'PROJECT_DEEP_DIVE', 'JOB_SCENARIO', 'SYNTHESIS_FOLLOW_UP')),
 CONSTRAINT chk_interview_question_difficulty CHECK (difficulty IN ('BASIC', 'INTERMEDIATE', 'ADVANCED')),
 CONSTRAINT chk_interview_question_confidence CHECK (confidence >= 0 AND confidence <= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE INDEX ix_interview_questions_session ON interview_questions(session_id, sequence_no);

CREATE TABLE interview_answers (
 id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY COMMENT '面试回答唯一标识',
 session_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属面试会话标识',
 question_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '回答对应的题目标识',
 idempotency_key VARCHAR(128) NOT NULL COMMENT '回答提交幂等键',
 answer_fingerprint CHAR(64) NOT NULL COMMENT '回答内容哈希，用于幂等冲突校验',
 answer_ciphertext MEDIUMBLOB NOT NULL COMMENT 'AES-GCM加密后的回答内容',
 answer_nonce VARBINARY(12) NOT NULL COMMENT '回答加密随机数（Nonce）',
 state VARCHAR(24) NOT NULL COMMENT '回答状态',
 created_at TIMESTAMP(6) NOT NULL COMMENT '回答提交时间',
 cleared_at TIMESTAMP(6) NULL COMMENT '回答内容清理时间',
 UNIQUE KEY uq_interview_answers_session_key (session_id, idempotency_key),
 UNIQUE KEY uq_interview_answers_session_question (session_id, question_id),
 CONSTRAINT fk_interview_answer_session FOREIGN KEY (session_id) REFERENCES interview_sessions(id),
 CONSTRAINT fk_interview_answer_question FOREIGN KEY (question_id) REFERENCES interview_questions(id),
 CONSTRAINT chk_interview_answer_state CHECK (state IN ('ANALYZING', 'FEEDBACK_READY', 'CLEARED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE INDEX ix_interview_answers_session ON interview_answers(session_id, created_at);

CREATE TABLE interview_feedback (
 id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY COMMENT '面试反馈唯一标识',
 session_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属面试会话标识',
 answer_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '关联回答标识',
 feedback_payload_json JSON NOT NULL COMMENT '经校验且已脱敏的反馈载荷',
 feedback_version BIGINT NOT NULL DEFAULT 1 COMMENT '反馈版本',
 created_at TIMESTAMP(6) NOT NULL COMMENT '反馈创建时间',
 UNIQUE KEY uq_interview_feedback_answer (answer_id),
 CONSTRAINT fk_interview_feedback_session FOREIGN KEY (session_id) REFERENCES interview_sessions(id),
 CONSTRAINT fk_interview_feedback_answer FOREIGN KEY (answer_id) REFERENCES interview_answers(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE INDEX ix_interview_feedback_session ON interview_feedback(session_id, created_at);

CREATE TABLE interview_confirmations (
 id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY COMMENT '面试声明确认记录唯一标识',
 session_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属面试会话标识',
 feedback_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '关联反馈标识',
 claim_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '待确认声明标识',
 decision VARCHAR(16) NOT NULL COMMENT '用户确认决定：CONFIRMED或REJECTED',
 actor_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '执行确认的用户标识',
 created_at TIMESTAMP(6) NOT NULL COMMENT '确认记录创建时间',
 UNIQUE KEY uq_interview_confirmations_claim (feedback_id, claim_id),
 CONSTRAINT fk_interview_confirmation_session FOREIGN KEY (session_id) REFERENCES interview_sessions(id),
 CONSTRAINT fk_interview_confirmation_feedback FOREIGN KEY (feedback_id) REFERENCES interview_feedback(id),
 CONSTRAINT fk_interview_confirmation_actor FOREIGN KEY (actor_id) REFERENCES users(id),
 CONSTRAINT chk_interview_confirmation_decision CHECK (decision IN ('CONFIRMED', 'REJECTED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE interview_callback_receipts (
 callback_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY COMMENT '面试回调消息唯一标识',
 payload_hash CHAR(64) NOT NULL COMMENT '面试回调负载哈希，用于幂等校验',
 received_at TIMESTAMP(6) NOT NULL COMMENT '面试回调接收时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


-- 维护视图：只展示可读业务 ID、修订版本和发布元数据，不返回简历正文、密文或密钥。
CREATE OR REPLACE VIEW v_resumes_readable AS
SELECT r.id, r.owner_id, r.title, r.source_type, r.parser_version,
       r.effective_revision_id, effective_revision.revision_no AS effective_revision_no,
       effective_revision.state AS effective_revision_state,
       r.pending_revision_id, pending_revision.revision_no AS pending_revision_no,
       pending_revision.state AS pending_revision_state, r.effective_title_key,
       (SELECT OCTET_LENGTH(raw_content_ciphertext) FROM resume_revisions content_revision WHERE content_revision.id = r.effective_revision_id) AS encrypted_content_bytes,
       r.creator_role, r.status, r.visibility_state, r.visible_until,
       r.soft_deleted_by, r.soft_deleted_at, r.archived_at, r.restored_at,
       r.created_at, r.updated_at, r.version
FROM resumes r
LEFT JOIN resume_revisions effective_revision ON effective_revision.id = r.effective_revision_id
LEFT JOIN resume_revisions pending_revision ON pending_revision.id = r.pending_revision_id;
