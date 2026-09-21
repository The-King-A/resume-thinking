-- V9: repair the evidence sequence after the historical V8 migration.
-- V8 is checksum-locked in existing installations, so this correction lives
-- in a new migration and never edits the already-applied V8 script.

-- Keep the row present if an operator restored an incomplete sequence table;
-- the duplicate-key branch leaves the current allocation unchanged.
INSERT INTO id_sequences (sequence_name, next_value)
VALUES ('evidence', 1)
ON DUPLICATE KEY UPDATE sequence_name = 'evidence';

-- V8 used SUBSTRING(id, 8), which treats the final "e" of "evidence" as
-- part of the numeric suffix. Preserve a sequence that has already advanced,
-- but raise it above every existing evidence ID using the correct offset.
UPDATE id_sequences s
JOIN (
    SELECT COALESCE(MAX(CAST(SUBSTRING(id, 9) AS UNSIGNED)), 0) + 1 AS required_next
    FROM analysis_evidence
    WHERE id REGEXP '^evidence[0-9]{3,}$'
) e ON s.sequence_name = 'evidence'
SET s.next_value = GREATEST(s.next_value, e.required_next);

ALTER TABLE id_sequences
    MODIFY COLUMN sequence_name VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
        COMMENT '业务 ID 序列前缀',
    MODIFY COLUMN next_value BIGINT NOT NULL
        COMMENT '下一个可分配的数字后缀';
