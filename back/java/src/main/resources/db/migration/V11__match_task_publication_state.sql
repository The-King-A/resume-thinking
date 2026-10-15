-- V11: persist v3 publication progress without changing legacy v2 behavior.
ALTER TABLE analysis_tasks
    ADD COLUMN publication_state VARCHAR(32) NOT NULL DEFAULT 'NOT_REQUESTED'
        COMMENT '发布状态：NOT_REQUESTED、PENDING、PUBLISHED或REJECTED_DUPLICATE_TITLE'
        AFTER state,
    ADD CONSTRAINT chk_analysis_task_publication_state
        CHECK (publication_state IN ('NOT_REQUESTED', 'PENDING', 'PUBLISHED', 'REJECTED_DUPLICATE_TITLE'));
