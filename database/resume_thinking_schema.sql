-- v2 手工建表快照：仅用于空数据库。已有 V1-V7 数据必须执行 Flyway V8。
-- 执行前请备份；不要手工创建 flyway_schema_history。
CREATE DATABASE IF NOT EXISTS `resume_thinking` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
USE `resume_thinking`;

CREATE TABLE id_sequences (
    sequence_name VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY COMMENT '业务 ID 序列前缀',
    next_value BIGINT NOT NULL COMMENT '下一个可分配的数字后缀'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
INSERT INTO id_sequences(sequence_name, next_value) VALUES ('user',1),('profile',1),('resume',1),('task',1),('evidence',1),('result',1),('callback',1),('audit',1);

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
 id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY COMMENT '简历唯一标识（v2字符串）', owner_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '简历所有者标识', title VARCHAR(200) NOT NULL COMMENT '简历名称', source_type VARCHAR(8) NOT NULL COMMENT '文件类型：TXT或DOCX', parser_version VARCHAR(64) NOT NULL DEFAULT 'v1' COMMENT '解析器版本', raw_content_ciphertext MEDIUMBLOB NOT NULL COMMENT 'AES-GCM加密后的简历原文', raw_content_nonce VARBINARY(12) NULL COMMENT '简历原文加密随机数（Nonce）', creator_role VARCHAR(16) NOT NULL COMMENT '创建者角色，决定默认留存期限', status TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记：0未删除，1已软删除', visibility_state VARCHAR(32) NOT NULL COMMENT '页面可见性状态', visible_until TIMESTAMP(6) NULL COMMENT '页面可见截止时间', soft_deleted_by VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '执行软删除的用户标识', soft_deleted_at TIMESTAMP(6) NULL COMMENT '软删除时间', archived_at TIMESTAMP(6) NULL COMMENT '缓存过期归档时间', restored_at TIMESTAMP(6) NULL COMMENT '最近一次恢复时间', created_at TIMESTAMP(6) NOT NULL COMMENT '简历创建时间', updated_at TIMESTAMP(6) NOT NULL COMMENT '简历最后更新时间', version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
 CONSTRAINT fk_resumes_owner FOREIGN KEY (owner_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE INDEX ix_resumes_owner_visibility ON resumes(owner_id, visibility_state, id);
CREATE INDEX ix_resumes_due ON resumes(visibility_state, visible_until);

CREATE TABLE resume_recovery_audit (
 id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY COMMENT '恢复审计记录唯一标识', resume_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '关联简历标识', actor_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '执行操作的用户标识，系统归档时为空', action VARCHAR(32) NOT NULL COMMENT '执行的生命周期操作', prior_visibility_state VARCHAR(32) NOT NULL COMMENT '操作前可见性状态', new_visibility_state VARCHAR(32) NOT NULL COMMENT '操作后可见性状态', visibility_state VARCHAR(32) NULL COMMENT '兼容旧数据的历史状态字段', occurred_at TIMESTAMP(6) NOT NULL COMMENT '审计事件发生时间', correlation_id BINARY(16) NOT NULL COMMENT '关联请求标识（UUID二进制）',
 CONSTRAINT fk_resume_audit_resume FOREIGN KEY (resume_id) REFERENCES resumes(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE INDEX ix_resume_audit_resume_occurred ON resume_recovery_audit(resume_id, occurred_at);

CREATE TABLE analysis_tasks (
 id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY COMMENT '分析任务唯一标识', resume_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '关联简历标识', llm_profile_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '使用的模型配置标识', creator_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '任务创建者标识', resume_version BIGINT NOT NULL COMMENT '提交任务时的简历版本', job_family VARCHAR(32) NOT NULL DEFAULT 'JAVA_BACKEND' COMMENT '岗位族，当前为JAVA_BACKEND', job_description_text MEDIUMTEXT NOT NULL COMMENT '岗位描述原文', idempotency_key VARCHAR(128) NOT NULL COMMENT '创建任务幂等键', attempt INT NOT NULL DEFAULT 1 COMMENT '当前处理尝试次数', callback_token_hash CHAR(64) NOT NULL COMMENT '内部回调令牌哈希', state VARCHAR(16) NOT NULL COMMENT '任务状态', failure_code VARCHAR(64) NULL COMMENT '失败原因编码', result_available BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否已有匹配结果', created_at TIMESTAMP(6) NOT NULL COMMENT '任务创建时间', updated_at TIMESTAMP(6) NOT NULL COMMENT '任务最后更新时间', version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
 CONSTRAINT uq_analysis_task_owner_key UNIQUE (creator_id, idempotency_key), CONSTRAINT fk_analysis_task_resume FOREIGN KEY (resume_id) REFERENCES resumes(id), CONSTRAINT fk_analysis_task_profile FOREIGN KEY (llm_profile_id) REFERENCES llm_profiles(id), CONSTRAINT fk_analysis_task_creator FOREIGN KEY (creator_id) REFERENCES users(id), CONSTRAINT chk_analysis_task_job_description_length CHECK (CHAR_LENGTH(job_description_text) <= 20000), CONSTRAINT chk_analysis_task_job_family CHECK (job_family IN ('JAVA_BACKEND'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE INDEX ix_analysis_tasks_resume ON analysis_tasks(resume_id, state);

CREATE TABLE analysis_evidence (
 id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY COMMENT '证据唯一标识', task_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '关联分析任务标识', source_location VARCHAR(500) NOT NULL COMMENT '证据在简历中的来源位置', source_start INT NOT NULL COMMENT '证据起始字符位置', source_end INT NOT NULL COMMENT '证据结束字符位置', source_excerpt TEXT NULL COMMENT '证据原文摘录', source_type VARCHAR(8) NOT NULL DEFAULT 'TXT' COMMENT '证据来源类型：TXT或DOCX', CONSTRAINT fk_analysis_evidence_task FOREIGN KEY (task_id) REFERENCES analysis_tasks(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE analysis_results (
 id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY COMMENT '分析结果唯一标识', task_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE COMMENT '关联分析任务标识', resume_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '关联简历标识', resume_version BIGINT NOT NULL COMMENT '生成结果时的简历版本', payload_json JSON NOT NULL COMMENT '经校验的结构化匹配结果', completed_at TIMESTAMP(6) NOT NULL COMMENT '分析完成时间', CONSTRAINT fk_analysis_result_task FOREIGN KEY (task_id) REFERENCES analysis_tasks(id), CONSTRAINT fk_analysis_result_resume FOREIGN KEY (resume_id) REFERENCES resumes(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE analysis_callback_receipts (
 callback_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY COMMENT '回调消息唯一标识', payload_hash CHAR(64) NOT NULL COMMENT '回调负载哈希，用于幂等校验', received_at TIMESTAMP(6) NOT NULL COMMENT '回调接收时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
