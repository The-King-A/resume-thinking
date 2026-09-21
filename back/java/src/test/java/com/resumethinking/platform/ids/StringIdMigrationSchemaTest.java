package com.resumethinking.platform.ids;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.zip.CRC32;

import static org.assertj.core.api.Assertions.assertThat;

class StringIdMigrationSchemaTest {
    private static final String[] PREFIXES = {
            "user", "profile", "resume", "task", "evidence", "result", "callback", "audit"
    };

    @Test
    void migrationDefinesDeterministicMappingsForEveryBusinessId() throws Exception {
        String migration = read("src/main/resources/db/migration/V8__string_business_ids.sql");
        assertThat(migration).contains("ROW_NUMBER() OVER", "ORDER BY created_at", "ORDER BY received_at");
        for (String prefix : PREFIXES) {
            assertThat(migration).as("mapping prefix %s", prefix).contains(prefix + "_id_map", prefix);
        }
    }

    @Test
    void migrationRebuildsKnownConstraintsAndSequencesUsingAsciiBinaryIds() throws Exception {
        String migration = read("src/main/resources/db/migration/V8__string_business_ids.sql");
        String snapshot = read("../../database/resume_thinking_schema.sql");
        for (String source : new String[]{migration, snapshot}) {
            assertThat(source).contains("id_sequences", "CHARACTER SET ascii", "COLLATE ascii_bin");
        }
        assertThat(migration).contains(
                "DROP FOREIGN KEY fk_llm_profiles_owner",
                "DROP FOREIGN KEY fk_resumes_owner",
                "DROP FOREIGN KEY fk_resume_audit_resume",
                "DROP FOREIGN KEY fk_analysis_task_resume",
                "DROP FOREIGN KEY fk_analysis_task_profile",
                "DROP FOREIGN KEY fk_analysis_task_creator",
                "DROP FOREIGN KEY fk_analysis_evidence_task",
                "DROP FOREIGN KEY fk_analysis_result_task",
                "DROP FOREIGN KEY fk_analysis_result_resume",
                "DROP INDEX ix_llm_profiles_owner",
                "DROP INDEX ix_resumes_owner_visibility",
                "DROP INDEX ix_resumes_due",
                "DROP INDEX ix_resume_audit_resume_occurred",
                "DROP INDEX ix_analysis_tasks_resume",
                "uq_analysis_task_owner_key",
                "soft_deleted_by",
                "ADD CONSTRAINT fk_llm_profiles_owner",
                "ADD CONSTRAINT fk_resumes_soft_deleted_by",
                "ADD CONSTRAINT fk_analysis_result_resume");
        int dropPrimary = migration.indexOf("ALTER TABLE analysis_results DROP PRIMARY KEY");
        int dropId = migration.indexOf("DROP COLUMN id", dropPrimary);
        assertThat(dropPrimary).isGreaterThanOrEqualTo(0).isLessThan(dropId);
        assertThat(migration).contains("soft_deleted_by IS NOT NULL", "LEFT JOIN user_id_map");
        assertThat(migration).contains("state IN ('QUEUED', 'PROCESSING')");
        // V1-V7 never had analysis_tasks.callback_id. V8 creates the v2
        // callback value from the task mapping and must not try to drop a
        // non-existent legacy column.
        assertThat(migration).doesNotContain("DROP COLUMN callback_id, DROP COLUMN resume_id");
        int taskAlter = migration.indexOf("ALTER TABLE analysis_tasks DROP PRIMARY KEY");
        int taskAlterEnd = migration.indexOf(';', taskAlter);
        assertThat(taskAlter).isGreaterThanOrEqualTo(0).isLessThan(taskAlterEnd);
        assertThat(migration.substring(taskAlter, taskAlterEnd)).doesNotContain("DROP COLUMN callback_id");
        assertThat(migration).contains("CHANGE COLUMN callback_id_v2 callback_id");
        assertThat(migration).contains(
                "SELECT 'callback', GREATEST(",
                "COALESCE(MAX(CAST(SUBSTRING(callback_id, 9) AS UNSIGNED)), 0),",
                "COALESCE((SELECT MAX(CAST(SUBSTRING(callback_id, 9) AS UNSIGNED)) FROM analysis_tasks), 0)");
        assertThat(migration).contains("DROP VIEW IF EXISTS v_resumes_readable", "CREATE OR REPLACE VIEW v_resumes_readable");
        // Existing databases already recorded this exact V8 checksum. Keep
        // the historical SQL unchanged; the offset correction is in V9.
        assertThat(flywayChecksum(migration)).isEqualTo(1750256818);
        assertThat(migration).contains("SELECT 'evidence', COALESCE(MAX(CAST(SUBSTRING(id, 8) AS UNSIGNED))");
        String repair = read("src/main/resources/db/migration/V9__repair_evidence_sequence_and_comments.sql");
        assertThat(repair).contains(
                "SUBSTRING(id, 9)",
                "GREATEST(s.next_value, e.required_next)",
                "sequence_name = 'evidence'",
                "COMMENT '业务 ID 序列前缀'",
                "COMMENT '下一个可分配的数字后缀'");
        int drainGuard = migration.indexOf("state IN ('QUEUED', 'PROCESSING')");
        int sequenceTable = migration.indexOf("CREATE TABLE id_sequences");
        int firstAlter = migration.indexOf("ALTER TABLE users ADD COLUMN");
        assertThat(drainGuard).isGreaterThanOrEqualTo(0).isLessThan(sequenceTable);
        assertThat(sequenceTable).isLessThan(firstAlter);
    }

    @Test
    void migrationKeepsCorrelationUuidAndExcludesSensitiveValuesFromMappingsAndLogs() throws Exception {
        String migration = read("src/main/resources/db/migration/V8__string_business_ids.sql");
        assertThat(migration).contains("correlation_id BINARY(16)");
        assertThat(migration).doesNotContain("INSERT INTO v8_id_mappings (password_hash")
                .doesNotContain("INSERT INTO v8_id_mappings (raw_content_ciphertext")
                .doesNotContain("INSERT INTO v8_id_mappings (payload_json")
                .doesNotContain("password_hash, raw_content_ciphertext, payload_json");
        assertThat(migration).doesNotContain("SELECT password_hash", "SELECT raw_content_ciphertext", "SELECT payload_json");
    }

    @Test
    void freshSnapshotUsesStringBusinessColumnsAndRetainsEncryptedAndLifecycleFields() throws Exception {
        String snapshot = read("../../database/resume_thinking_schema.sql");
        assertThat(snapshot).doesNotContain("UUID_TO_BIN", "BINARY(16) NOT NULL PRIMARY KEY");
        assertThat(snapshot).contains("correlation_id BINARY(16)", "password_hash", "raw_content_ciphertext", "payload_json");
        assertThat(snapshot).contains("CREATE OR REPLACE VIEW v_resumes_readable", "OCTET_LENGTH(raw_content_ciphertext)");
        assertThat(snapshot).contains(
                "sequence_name VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY COMMENT '业务 ID 序列前缀'",
                "next_value BIGINT NOT NULL COMMENT '下一个可分配的数字后缀'");
        for (String table : new String[]{"users", "llm_profiles", "resumes", "analysis_tasks", "analysis_evidence", "analysis_results", "analysis_callback_receipts", "resume_recovery_audit"}) {
            assertThat(snapshot).as("table %s", table).contains("CREATE TABLE " + table);
        }
    }

    @Test
    void documentationBaselinesFreshV2SnapshotAfterV8() throws Exception {
        String readme = read("../../README.md");
        assertThat(readme).contains("SPRING_FLYWAY_BASELINE_VERSION=8");
    }

    private static String read(String relativePath) throws Exception {
        Path path = Path.of(relativePath);
        return Files.exists(path) ? Files.readString(path) : "";
    }

    private static int flywayChecksum(String content) throws Exception {
        CRC32 crc = new CRC32();
        try (BufferedReader reader = new BufferedReader(new StringReader(content))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isEmpty() && line.charAt(0) == '\ufeff') {
                    line = line.substring(1);
                }
                crc.update(line.getBytes(StandardCharsets.UTF_8));
            }
        }
        return (int) crc.getValue();
    }
}
