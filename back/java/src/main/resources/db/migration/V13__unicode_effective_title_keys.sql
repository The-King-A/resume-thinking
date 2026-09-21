-- V13: move effective title keys to the Unicode-safe application normalizer.
-- MySQL 8 REGEXP_REPLACE uses ICU; [[:space:]] includes ordinary whitespace,
-- U+00A0 no-break space, and U+3000 ideographic space. Existing title_key
-- values already carry the frozen Locale.ROOT-compatible application casing.
CREATE TEMPORARY TABLE v13_revision_title_keys (
    revision_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    title_key VARCHAR(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    CONSTRAINT chk_v13_title_key_nonblank CHECK (CHAR_LENGTH(title_key) > 0)
) ENGINE=InnoDB;

INSERT INTO v13_revision_title_keys (revision_id, title_key)
SELECT id, REGEXP_REPLACE(title_key, '^[[:space:]]+|[[:space:]]+$', '')
FROM resume_revisions
ORDER BY id;

-- The composite primary key is an intentional migration guard. If Unicode
-- normalization merges two visible same-owner titles, this insert fails
-- before any durable row or schema is changed. Operators must resolve the
-- conflicting visible titles explicitly; the migration never hides a winner.
CREATE TEMPORARY TABLE v13_effective_title_conflict_guard (
    owner_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    title_key VARCHAR(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    PRIMARY KEY (owner_id, title_key)
) ENGINE=InnoDB;

INSERT INTO v13_effective_title_conflict_guard (owner_id, title_key)
SELECT r.owner_id, normalized.title_key
FROM resumes r
JOIN v13_revision_title_keys normalized ON normalized.revision_id = r.effective_revision_id
WHERE r.effective_revision_id IS NOT NULL
  AND r.status = 0
  AND r.visibility_state = 'ACTIVE'
ORDER BY r.owner_id, normalized.title_key, r.created_at, r.id;

ALTER TABLE resumes
    ADD COLUMN effective_title_key_v13 VARCHAR(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL COMMENT '仅有效且可见简历使用的Unicode归一化标题唯一键'
        AFTER effective_title_key;

UPDATE resume_revisions revision
JOIN v13_revision_title_keys normalized ON normalized.revision_id = revision.id
SET revision.title_key = normalized.title_key;

UPDATE resumes r
LEFT JOIN v13_revision_title_keys normalized ON normalized.revision_id = r.effective_revision_id
SET r.effective_title_key_v13 = CASE
    WHEN r.effective_revision_id IS NOT NULL AND r.status = 0 AND r.visibility_state = 'ACTIVE'
        THEN normalized.title_key
    ELSE NULL
END;

ALTER TABLE resumes
    ADD UNIQUE KEY uq_resumes_owner_effective_title_v13 (owner_id, effective_title_key_v13);

DROP VIEW IF EXISTS v_resumes_readable;

ALTER TABLE resumes
    DROP INDEX uq_resumes_owner_effective_title,
    DROP COLUMN effective_title_key;

ALTER TABLE resumes
    CHANGE COLUMN effective_title_key_v13 effective_title_key
        VARCHAR(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL
        COMMENT '仅有效且可见简历使用的Unicode归一化标题唯一键';

ALTER TABLE resumes
    RENAME INDEX uq_resumes_owner_effective_title_v13 TO uq_resumes_owner_effective_title;

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

DROP TEMPORARY TABLE v13_effective_title_conflict_guard;
DROP TEMPORARY TABLE v13_revision_title_keys;
