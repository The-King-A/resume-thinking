ALTER TABLE analysis_evidence ADD COLUMN source_type VARCHAR(8) NOT NULL DEFAULT 'TXT' AFTER source_location;
ALTER TABLE analysis_evidence ADD COLUMN source_excerpt TEXT NULL AFTER source_end;
