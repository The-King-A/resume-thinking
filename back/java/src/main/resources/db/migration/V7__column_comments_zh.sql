-- 为现有 V1-V6 数据库补齐字段级中文备注。
-- 本迁移只修改列定义中的 COMMENT，不修改字段值、索引、外键或加密数据。

ALTER TABLE users
    MODIFY COLUMN id BINARY(16) NOT NULL COMMENT '用户唯一标识（UUID二进制）',
    MODIFY COLUMN username VARCHAR(64) NOT NULL COMMENT '登录用户名',
    MODIFY COLUMN email VARCHAR(254) NOT NULL COMMENT '用户邮箱地址',
    MODIFY COLUMN password_hash VARCHAR(100) NOT NULL COMMENT 'BCrypt密码哈希，不可逆',
    MODIFY COLUMN role VARCHAR(16) NOT NULL COMMENT '用户角色：USER普通用户或ADMIN管理员',
    MODIFY COLUMN created_at TIMESTAMP(6) NOT NULL COMMENT '账号创建时间';

ALTER TABLE llm_profiles
    MODIFY COLUMN id BINARY(16) NOT NULL COMMENT '模型配置唯一标识（UUID二进制）',
    MODIFY COLUMN owner_id BINARY(16) NOT NULL COMMENT '所属用户标识',
    MODIFY COLUMN display_name VARCHAR(100) NOT NULL COMMENT '配置显示名称',
    MODIFY COLUMN endpoint_url VARCHAR(2048) NOT NULL COMMENT 'OpenAI兼容接口地址',
    MODIFY COLUMN model_name VARCHAR(200) NOT NULL COMMENT '模型名称',
    MODIFY COLUMN api_key_ciphertext BLOB NOT NULL COMMENT 'AES-GCM加密后的API密钥',
    MODIFY COLUMN api_key_nonce VARBINARY(12) NOT NULL COMMENT 'API密钥加密随机数（Nonce）',
    MODIFY COLUMN key_version INT NOT NULL COMMENT '加密密钥版本',
    MODIFY COLUMN selected BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否为默认模型配置',
    MODIFY COLUMN last_test_status VARCHAR(16) NULL COMMENT '最近一次连接测试状态',
    MODIFY COLUMN last_tested_at TIMESTAMP(6) NULL COMMENT '最近一次连接测试时间',
    MODIFY COLUMN created_at TIMESTAMP(6) NOT NULL COMMENT '配置创建时间',
    MODIFY COLUMN updated_at TIMESTAMP(6) NOT NULL COMMENT '配置最后更新时间';

ALTER TABLE resumes
    MODIFY COLUMN id BINARY(16) NOT NULL COMMENT '简历唯一标识（UUID二进制）',
    MODIFY COLUMN owner_id BINARY(16) NOT NULL COMMENT '简历所有者标识',
    MODIFY COLUMN title VARCHAR(200) NOT NULL COMMENT '简历名称',
    MODIFY COLUMN source_type VARCHAR(8) NOT NULL COMMENT '文件类型：TXT或DOCX',
    MODIFY COLUMN parser_version VARCHAR(64) NOT NULL DEFAULT 'v1' COMMENT '解析器版本',
    MODIFY COLUMN raw_content_ciphertext MEDIUMBLOB NOT NULL COMMENT 'AES-GCM加密后的简历原文',
    MODIFY COLUMN raw_content_nonce VARBINARY(12) NULL COMMENT '简历原文加密随机数（Nonce）',
    MODIFY COLUMN creator_role VARCHAR(16) NOT NULL COMMENT '创建者角色，决定默认留存期限',
    MODIFY COLUMN status TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记：0未删除，1已软删除',
    MODIFY COLUMN visibility_state VARCHAR(32) NOT NULL COMMENT '页面可见性状态',
    MODIFY COLUMN visible_until TIMESTAMP(6) NULL COMMENT '页面可见截止时间',
    MODIFY COLUMN soft_deleted_by BINARY(16) NULL COMMENT '执行软删除的用户标识',
    MODIFY COLUMN soft_deleted_at TIMESTAMP(6) NULL COMMENT '软删除时间',
    MODIFY COLUMN archived_at TIMESTAMP(6) NULL COMMENT '缓存过期归档时间',
    MODIFY COLUMN restored_at TIMESTAMP(6) NULL COMMENT '最近一次恢复时间',
    MODIFY COLUMN created_at TIMESTAMP(6) NOT NULL COMMENT '简历创建时间',
    MODIFY COLUMN updated_at TIMESTAMP(6) NOT NULL COMMENT '简历最后更新时间',
    MODIFY COLUMN version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号';

ALTER TABLE resume_recovery_audit
    MODIFY COLUMN id BIGINT NOT NULL AUTO_INCREMENT COMMENT '恢复审计记录唯一标识',
    MODIFY COLUMN resume_id BINARY(16) NOT NULL COMMENT '关联简历标识',
    MODIFY COLUMN actor_id BINARY(16) NULL COMMENT '执行操作的用户标识，系统归档时为空',
    MODIFY COLUMN action VARCHAR(32) NOT NULL COMMENT '执行的生命周期操作',
    MODIFY COLUMN prior_visibility_state VARCHAR(32) NOT NULL COMMENT '操作前可见性状态',
    MODIFY COLUMN new_visibility_state VARCHAR(32) NOT NULL COMMENT '操作后可见性状态',
    MODIFY COLUMN visibility_state VARCHAR(32) NULL COMMENT '兼容旧数据的历史状态字段',
    MODIFY COLUMN occurred_at TIMESTAMP(6) NOT NULL COMMENT '审计事件发生时间',
    MODIFY COLUMN correlation_id BINARY(16) NOT NULL COMMENT '关联请求标识（UUID二进制）';

ALTER TABLE analysis_tasks
    MODIFY COLUMN id BINARY(16) NOT NULL COMMENT '分析任务唯一标识（UUID二进制）',
    MODIFY COLUMN resume_id BINARY(16) NOT NULL COMMENT '关联简历标识',
    MODIFY COLUMN llm_profile_id BINARY(16) NOT NULL COMMENT '使用的模型配置标识',
    MODIFY COLUMN creator_id BINARY(16) NOT NULL COMMENT '任务创建者标识',
    MODIFY COLUMN resume_version BIGINT NOT NULL COMMENT '提交任务时的简历版本',
    MODIFY COLUMN job_family VARCHAR(32) NOT NULL DEFAULT 'JAVA_BACKEND' COMMENT '岗位族，当前为JAVA_BACKEND',
    MODIFY COLUMN job_description_text MEDIUMTEXT NOT NULL COMMENT '岗位描述原文',
    MODIFY COLUMN idempotency_key VARCHAR(128) NOT NULL COMMENT '创建任务幂等键',
    MODIFY COLUMN attempt INT NOT NULL DEFAULT 1 COMMENT '当前处理尝试次数',
    MODIFY COLUMN callback_token_hash CHAR(64) NOT NULL COMMENT '内部回调令牌哈希',
    MODIFY COLUMN state VARCHAR(16) NOT NULL COMMENT '任务状态',
    MODIFY COLUMN failure_code VARCHAR(64) NULL COMMENT '失败原因编码',
    MODIFY COLUMN result_available BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否已有匹配结果',
    MODIFY COLUMN created_at TIMESTAMP(6) NOT NULL COMMENT '任务创建时间',
    MODIFY COLUMN updated_at TIMESTAMP(6) NOT NULL COMMENT '任务最后更新时间',
    MODIFY COLUMN version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号';

ALTER TABLE analysis_evidence
    MODIFY COLUMN id BINARY(16) NOT NULL COMMENT '证据唯一标识（UUID二进制）',
    MODIFY COLUMN task_id BINARY(16) NOT NULL COMMENT '关联分析任务标识',
    MODIFY COLUMN source_location VARCHAR(500) NOT NULL COMMENT '证据在简历中的来源位置',
    MODIFY COLUMN source_start INT NOT NULL COMMENT '证据起始字符位置',
    MODIFY COLUMN source_end INT NOT NULL COMMENT '证据结束字符位置',
    MODIFY COLUMN source_excerpt TEXT NULL COMMENT '证据原文摘录',
    MODIFY COLUMN source_type VARCHAR(8) NOT NULL DEFAULT 'TXT' COMMENT '证据来源类型：TXT或DOCX';

ALTER TABLE analysis_results
    MODIFY COLUMN id BIGINT NOT NULL AUTO_INCREMENT COMMENT '分析结果唯一标识',
    MODIFY COLUMN task_id BINARY(16) NOT NULL COMMENT '关联分析任务标识',
    MODIFY COLUMN resume_id BINARY(16) NOT NULL COMMENT '关联简历标识',
    MODIFY COLUMN resume_version BIGINT NOT NULL COMMENT '生成结果时的简历版本',
    MODIFY COLUMN payload_json JSON NOT NULL COMMENT '经校验的结构化匹配结果',
    MODIFY COLUMN completed_at TIMESTAMP(6) NOT NULL COMMENT '分析完成时间';

ALTER TABLE analysis_callback_receipts
    MODIFY COLUMN callback_id BINARY(16) NOT NULL COMMENT '回调消息唯一标识',
    MODIFY COLUMN payload_hash CHAR(64) NOT NULL COMMENT '回调负载哈希，用于幂等校验',
    MODIFY COLUMN received_at TIMESTAMP(6) NOT NULL COMMENT '回调接收时间';
