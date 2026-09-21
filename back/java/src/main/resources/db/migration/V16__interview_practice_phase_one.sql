-- V16: evidence-bound interview preparation and one-answer feedback.
-- Existing v1-v15 data is retained; this migration only adds new tables and ID sequences.
INSERT INTO id_sequences(sequence_name, next_value)
VALUES ('session', 1), ('question', 1), ('answer', 1), ('feedback', 1), ('confirmation', 1)
ON DUPLICATE KEY UPDATE sequence_name = VALUES(sequence_name);
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
