-- V10: split immutable document revisions from logical resume lifecycle rows.
-- Run while application writes are quiesced so task/result backfills have a
-- stable source set and publication winners remain deterministic.

CREATE TABLE resume_revisions (
    id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY COMMENT '简历修订版本唯一标识',
    resume_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属逻辑简历标识',
    revision_no BIGINT NOT NULL COMMENT '逻辑简历内单调递增的修订序号',
    title VARCHAR(200) NOT NULL COMMENT '该修订版本的简历标题',
    title_key VARCHAR(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '应用规范化后的标题唯一键',
    source_type VARCHAR(8) NOT NULL COMMENT '该修订版本的文件类型：TXT或DOCX',
    parser_version VARCHAR(64) NOT NULL COMMENT '该修订版本使用的解析器版本',
    raw_content_ciphertext MEDIUMBLOB NOT NULL COMMENT 'AES-GCM加密后的修订版本原文',
    raw_content_nonce VARBINARY(12) NULL COMMENT '修订版本原文加密随机数（Nonce）',
    state VARCHAR(16) NOT NULL COMMENT '修订状态：PENDING、EFFECTIVE、FAILED或SUPERSEDED',
    created_at TIMESTAMP(6) NOT NULL COMMENT '修订版本创建时间',
    UNIQUE KEY uq_resume_revisions_resume_revision_no (resume_id, revision_no),
    UNIQUE KEY uq_resume_revisions_resume_id_id (resume_id, id),
    CONSTRAINT fk_resume_revision_resume FOREIGN KEY (resume_id) REFERENCES resumes(id),
    CONSTRAINT chk_resume_revision_state CHECK (state IN ('PENDING', 'EFFECTIVE', 'FAILED', 'SUPERSEDED')),
    CONSTRAINT chk_resume_revision_source_type CHECK (source_type IN ('TXT', 'DOCX'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Each legacy logical row owns one immutable document. IDs are deterministic
-- and reveal no resume content. Rows above 999 keep their complete suffix.
INSERT INTO resume_revisions (
    id, resume_id, revision_no, title, title_key, source_type, parser_version,
    raw_content_ciphertext, raw_content_nonce, state, created_at
)
SELECT CONCAT('revision', IF(legacy_no < 1000, LPAD(legacy_no, 3, '0'), CAST(legacy_no AS CHAR))),
       id, 1, title, LOWER(TRIM(title)), source_type, parser_version,
       raw_content_ciphertext, raw_content_nonce, 'SUPERSEDED', created_at
FROM (
    SELECT r.*, ROW_NUMBER() OVER (ORDER BY r.created_at, r.id) AS legacy_no
    FROM resumes r
) legacy_resumes;

INSERT INTO id_sequences (sequence_name, next_value)
SELECT 'revision', COALESCE(MAX(CAST(SUBSTRING(id, 9) AS UNSIGNED)), 0) + 1
FROM resume_revisions
ON DUPLICATE KEY UPDATE next_value = GREATEST(next_value, VALUES(next_value));

ALTER TABLE resumes
    ADD COLUMN effective_revision_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '当前已发布并可展示的简历修订版本标识',
    ADD COLUMN pending_revision_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '等待匹配验证的候选简历修订版本标识';

ALTER TABLE analysis_tasks
    ADD COLUMN revision_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '任务绑定的不可变简历修订版本标识' AFTER resume_id;

ALTER TABLE analysis_results
    ADD COLUMN revision_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '结果绑定的不可变简历修订版本标识' AFTER resume_id;

-- Legacy tasks all refer to the one document formerly stored on their logical
-- resume row. Results inherit that exact task binding rather than guessing
-- from a current logical-resume version.
UPDATE analysis_tasks t
JOIN resume_revisions revision
  ON revision.resume_id = t.resume_id AND revision.revision_no = 1
SET t.revision_id = revision.id;

UPDATE analysis_results result_row
JOIN analysis_tasks task_row ON task_row.id = result_row.task_id
SET result_row.revision_id = task_row.revision_id;

ALTER TABLE analysis_tasks
    MODIFY COLUMN revision_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '任务绑定的不可变简历修订版本标识',
    ADD UNIQUE KEY uq_analysis_tasks_id_resume_revision (id, resume_id, revision_id),
    ADD CONSTRAINT fk_analysis_task_revision FOREIGN KEY (resume_id, revision_id) REFERENCES resume_revisions(resume_id, id);

ALTER TABLE analysis_results
    MODIFY COLUMN revision_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '结果绑定的不可变简历修订版本标识',
    ADD CONSTRAINT fk_analysis_result_revision FOREIGN KEY (resume_id, revision_id) REFERENCES resume_revisions(resume_id, id),
    ADD CONSTRAINT fk_analysis_result_task_revision FOREIGN KEY (task_id, resume_id, revision_id) REFERENCES analysis_tasks(id, resume_id, revision_id);

CREATE INDEX ix_analysis_tasks_revision ON analysis_tasks(revision_id, state);
CREATE INDEX ix_analysis_results_resume_revision_completed
    ON analysis_results(resume_id, revision_id, completed_at);

-- Only active legacy rows with a succeeded task, persisted result, and at
-- least one persisted evidence row can become effective. Duplicate titles
-- are resolved by newest resume creation time and then readable ID.
CREATE TEMPORARY TABLE v10_effective_resume_winners (
    resume_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    revision_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
) ENGINE=InnoDB;

INSERT INTO v10_effective_resume_winners (resume_id, revision_id)
SELECT ranked.resume_id, ranked.revision_id
FROM (
    SELECT r.id AS resume_id,
           revision.id AS revision_id,
           ROW_NUMBER() OVER (
               PARTITION BY r.owner_id, revision.title_key
               ORDER BY r.created_at DESC, r.id DESC
           ) AS title_rank
    FROM resumes r
    JOIN resume_revisions revision
      ON revision.resume_id = r.id AND revision.revision_no = 1
    WHERE r.status = 0
      AND r.visibility_state = 'ACTIVE'
      AND EXISTS (
          SELECT 1
          FROM analysis_tasks task_row
          JOIN analysis_results result_row ON result_row.task_id = task_row.id
          JOIN analysis_evidence evidence_row ON evidence_row.task_id = task_row.id
          WHERE task_row.resume_id = r.id
            AND task_row.revision_id = revision.id
            AND result_row.revision_id = revision.id
            AND task_row.state = 'SUCCEEDED'
      )
) ranked
WHERE ranked.title_rank = 1;

UPDATE resumes
SET effective_revision_id = NULL,
    pending_revision_id = NULL;

UPDATE resumes r
JOIN v10_effective_resume_winners winner ON winner.resume_id = r.id
SET r.effective_revision_id = winner.revision_id;

UPDATE resume_revisions
SET state = 'SUPERSEDED';

UPDATE resume_revisions revision
JOIN v10_effective_resume_winners winner ON winner.revision_id = revision.id
SET revision.state = 'EFFECTIVE';

DROP TEMPORARY TABLE v10_effective_resume_winners;

ALTER TABLE resumes
    ADD CONSTRAINT fk_resume_effective_revision FOREIGN KEY (id, effective_revision_id) REFERENCES resume_revisions(resume_id, id),
    ADD CONSTRAINT fk_resume_pending_revision FOREIGN KEY (id, pending_revision_id) REFERENCES resume_revisions(resume_id, id);

-- MySQL unique indexes permit multiple NULL values. Lifecycle state therefore
-- releases a title on deletion/archive without deleting either logical rows or
-- immutable revisions.
ALTER TABLE resumes
    ADD COLUMN effective_title_key VARCHAR(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin GENERATED ALWAYS AS (CASE WHEN effective_revision_id IS NOT NULL AND status = 0 AND visibility_state = 'ACTIVE' THEN LOWER(TRIM(title)) ELSE NULL END) STORED COMMENT '仅有效且可见简历使用的归一化标题唯一键',
    ADD UNIQUE KEY uq_resumes_owner_effective_title (owner_id, effective_title_key);

DROP VIEW IF EXISTS v_resumes_readable;
CREATE OR REPLACE VIEW v_resumes_readable AS
SELECT r.id,
       r.owner_id,
       r.title,
       r.source_type,
       r.parser_version,
       r.effective_revision_id,
       effective_revision.revision_no AS effective_revision_no,
       effective_revision.state AS effective_revision_state,
       r.pending_revision_id,
       pending_revision.revision_no AS pending_revision_no,
       pending_revision.state AS pending_revision_state,
       r.effective_title_key,
       (SELECT OCTET_LENGTH(raw_content_ciphertext)
        FROM resume_revisions content_revision
        WHERE content_revision.id = r.effective_revision_id) AS encrypted_content_bytes,
       r.creator_role,
       r.status,
       r.visibility_state,
       r.visible_until,
       r.soft_deleted_by,
       r.soft_deleted_at,
       r.archived_at,
       r.restored_at,
       r.created_at,
       r.updated_at,
       r.version
FROM resumes r
LEFT JOIN resume_revisions effective_revision ON effective_revision.id = r.effective_revision_id
LEFT JOIN resume_revisions pending_revision ON pending_revision.id = r.pending_revision_id;
