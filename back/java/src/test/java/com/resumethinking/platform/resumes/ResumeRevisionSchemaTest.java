package com.resumethinking.platform.resumes;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class ResumeRevisionSchemaTest {
    @Test
    void freshSnapshotAndV10DefineImmutableRevisionPersistence() throws Exception {
        String snapshot = read("../../database/resume_thinking_schema.sql");
        String migration = read("src/main/resources/db/migration/V10__effective_resume_revisions.sql");

        for (String source : new String[]{snapshot, migration}) {
            assertThat(source).contains(
                    "CREATE TABLE resume_revisions",
                    "UNIQUE KEY uq_resume_revisions_resume_revision_no",
                    "CONSTRAINT fk_resume_revision_resume",
                    "revision_id",
                    "CONSTRAINT fk_analysis_task_revision",
                    "CONSTRAINT fk_analysis_result_revision",
                    "CREATE INDEX ix_analysis_results_resume_revision_completed",
                    "UNIQUE KEY uq_resumes_owner_effective_title");
        }
        assertThat(snapshot).contains("CREATE TABLE resume_revisions");
        assertThat(snapshot).contains("UNIQUE KEY uq_resumes_owner_effective_title");
        assertThat(migration).contains("ADD COLUMN effective_revision_id");
        assertThat(migration).contains("ADD COLUMN revision_id");
        assertThat(migration).contains(
                "SELECT 'revision'",
                "ADD COLUMN effective_title_key",
                "GENERATED ALWAYS AS",
                "CASE WHEN effective_revision_id IS NOT NULL AND status = 0 AND visibility_state = 'ACTIVE'",
                "DROP VIEW IF EXISTS v_resumes_readable",
                "CREATE OR REPLACE VIEW v_resumes_readable");
        assertThat(migration).doesNotContain("raw_content_ciphertext AS", "raw_content_nonce AS");
    }

    @Test
    void everyNewColumnHasAChineseMaintenanceComment() throws Exception {
        String snapshot = read("../../database/resume_thinking_schema.sql");
        String migration = read("src/main/resources/db/migration/V10__effective_resume_revisions.sql");

        assertChineseComments(snapshot, "resume_revisions", new String[]{
                "id", "resume_id", "revision_no", "title", "title_key", "source_type",
                "parser_version", "raw_content_ciphertext", "raw_content_nonce", "state", "created_at"
        });
        assertChineseColumnComment(snapshot, "effective_revision_id");
        assertChineseColumnComment(snapshot, "pending_revision_id");
        assertChineseColumnComment(snapshot, "effective_title_key");
        assertChineseComments(migration, "resume_revisions", new String[]{
                "id", "resume_id", "revision_no", "title", "title_key", "source_type",
                "parser_version", "raw_content_ciphertext", "raw_content_nonce", "state", "created_at"
        });
        assertChineseColumnComment(migration, "effective_revision_id");
        assertChineseColumnComment(migration, "pending_revision_id");
        assertChineseColumnComment(migration, "effective_title_key");
        assertThat(migration).containsPattern(
                "(?is)ALTER TABLE analysis_tasks.*?ADD COLUMN revision_id\\b[^;]*?COMMENT\\s+'[^']*[\\u4e00-\\u9fff][^']*'");
        assertThat(migration).containsPattern(
                "(?is)ALTER TABLE analysis_results.*?ADD COLUMN revision_id\\b[^;]*?COMMENT\\s+'[^']*[\\u4e00-\\u9fff][^']*'");
    }

    @Test
    void migrationBackfillsBeforeMakingRevisionBindingsRequired() throws Exception {
        String migration = read("src/main/resources/db/migration/V10__effective_resume_revisions.sql");

        int createRevisions = migration.indexOf("CREATE TABLE resume_revisions");
        int insertRevisions = migration.indexOf("INSERT INTO resume_revisions");
        int backfillTasks = migration.indexOf("UPDATE analysis_tasks");
        int backfillResults = migration.indexOf("UPDATE analysis_results");
        int requireTaskRevision = migration.indexOf("MODIFY COLUMN revision_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL");
        int requireResultRevision = migration.indexOf(
                "MODIFY COLUMN revision_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL",
                requireTaskRevision + 1);
        int winnerTable = migration.indexOf("CREATE TEMPORARY TABLE v10_effective_resume_winners");
        int resolveWinners = migration.indexOf("ROW_NUMBER() OVER", winnerTable);
        int addEffectiveTitleKey = migration.indexOf("ADD COLUMN effective_title_key");

        assertThat(createRevisions).isGreaterThanOrEqualTo(0).isLessThan(insertRevisions);
        assertThat(insertRevisions).isLessThan(backfillTasks);
        assertThat(backfillTasks).isLessThan(requireTaskRevision);
        assertThat(backfillResults).isLessThan(requireResultRevision);
        assertThat(winnerTable).isGreaterThanOrEqualTo(0).isLessThan(resolveWinners);
        assertThat(resolveWinners).isLessThan(addEffectiveTitleKey);
    }

    @Test
    void revisionBindingsCannotCrossLogicalResumeOrTaskRevisionBoundaries() throws Exception {
        String snapshot = read("../../database/resume_thinking_schema.sql");
        String migration = read("src/main/resources/db/migration/V10__effective_resume_revisions.sql");

        for (String source : new String[]{snapshot, migration}) {
            assertThat(source).contains(
                    "UNIQUE KEY uq_resume_revisions_resume_id_id (resume_id, id)",
                    "FOREIGN KEY (id, effective_revision_id) REFERENCES resume_revisions(resume_id, id)",
                    "FOREIGN KEY (id, pending_revision_id) REFERENCES resume_revisions(resume_id, id)",
                    "UNIQUE KEY uq_analysis_tasks_id_resume_revision (id, resume_id, revision_id)",
                    "FOREIGN KEY (resume_id, revision_id) REFERENCES resume_revisions(resume_id, id)",
                    "FOREIGN KEY (task_id, resume_id, revision_id) REFERENCES analysis_tasks(id, resume_id, revision_id)");
        }
    }

    @Test
    void exactTitleKeyCollationKeepsAccentedTitlesDistinctAndUsesTheStoredWinnerKey() throws Exception {
        String snapshot = read("../../database/resume_thinking_schema.sql");
        String migration = read("src/main/resources/db/migration/V10__effective_resume_revisions.sql");

        assertThat("Resume".trim().toLowerCase(Locale.ROOT))
                .isNotEqualTo("R\u00e9sum\u00e9".trim().toLowerCase(Locale.ROOT));
        for (String source : new String[]{snapshot, migration}) {
            assertThat(source).contains(
                    "title_key VARCHAR(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL",
                    "effective_title_key VARCHAR(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin");
        }
        assertThat(migration).contains("PARTITION BY r.owner_id, revision.title_key");
        assertThat(migration).doesNotContain("PARTITION BY r.owner_id, LOWER(TRIM(r.title))");
    }

    @Test
    void v13MigratesToAnApplicationMaintainedNullableUnicodeTitleKey() throws Exception {
        String snapshot = read("../../database/resume_thinking_schema.sql");
        String migration = read("src/main/resources/db/migration/V13__unicode_effective_title_keys.sql");

        assertThat(migration).isNotBlank();
        assertThat(snapshot).contains(
                "effective_title_key VARCHAR(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL",
                "UNIQUE KEY uq_resumes_owner_effective_title (owner_id, effective_title_key)");
        assertThat(snapshot).doesNotContain("effective_title_key VARCHAR(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin GENERATED ALWAYS AS");
        assertThat(migration).contains(
                "REGEXP_REPLACE",
                "v13_effective_title_conflict_guard",
                "effective_title_key_v13",
                "RENAME INDEX uq_resumes_owner_effective_title_v13 TO uq_resumes_owner_effective_title");
        assertChineseColumnComment(migration, "effective_title_key_v13");
    }

    @Test
    void v13AbortsOnHistoricalUnicodeTitleConflictsBeforeChangingVisibleData() throws Exception {
        String migration = read("src/main/resources/db/migration/V13__unicode_effective_title_keys.sql");

        int guard = migration.indexOf("CREATE TEMPORARY TABLE v13_effective_title_conflict_guard");
        int validate = migration.indexOf("INSERT INTO v13_effective_title_conflict_guard");
        int alterBaseTable = migration.indexOf("ALTER TABLE resumes");
        int updateRevisions = migration.indexOf("UPDATE resume_revisions");
        assertThat(guard).isGreaterThanOrEqualTo(0).isLessThan(validate);
        assertThat(validate).isLessThan(alterBaseTable).isLessThan(updateRevisions);
        assertThat(migration).contains(
                "PRIMARY KEY (owner_id, title_key)",
                "r.effective_revision_id IS NOT NULL",
                "r.status = 0",
                "r.visibility_state = 'ACTIVE'");
        assertThat(migration).doesNotContain("SET r.status = 1", "SET r.visibility_state", "markSuperseded");
    }

    @Test
    void revisionDefensivelyOwnsSourceBytesAndHasNoSourceOrTitleMutators() {
        byte[] ciphertext = {1, 2, 3};
        byte[] nonce = {4, 5, 6};
        ResumeRevision revision = new ResumeRevision(
                "revision001", "resume001", 1, "Java Resume", "java resume",
                Resume.SourceType.DOCX, "parser-v1", ciphertext, nonce, ResumeRevision.State.PENDING);

        ciphertext[0] = 9;
        nonce[0] = 9;
        byte[] returnedCiphertext = revision.getCiphertext();
        byte[] returnedNonce = revision.getNonce();
        returnedCiphertext[1] = 9;
        returnedNonce[1] = 9;

        assertThat(revision.getTitle()).isEqualTo("Java Resume");
        assertThat(revision.getTitleKey()).isEqualTo("java resume");
        assertThat(revision.getCiphertext()).containsExactly(1, 2, 3);
        assertThat(revision.getNonce()).containsExactly(4, 5, 6);
        assertThat(Arrays.stream(ResumeRevision.class.getMethods()).map(Method::getName))
                .doesNotContain("setTitle", "setTitleKey", "setSourceType", "setParserVersion",
                        "setCiphertext", "setNonce");
    }

    private static void assertChineseComments(String schema, String table, String[] columns) {
        int tableStart = schema.indexOf("CREATE TABLE " + table);
        int tableEnd = schema.indexOf(';', tableStart);
        assertThat(tableStart).isGreaterThanOrEqualTo(0).isLessThan(tableEnd);
        String definition = schema.substring(tableStart, tableEnd);
        for (String column : columns) {
            assertThat(definition).as("%s.%s has a Chinese comment", table, column)
                    .containsPattern("(?im)(?:^|,)\\s*" + column
                            + "\\b(?=[^\\r\\n]*[\\u4e00-\\u9fff])[^\\r\\n]*\\bCOMMENT\\s+'[^']+'");
        }
    }

    private static void assertChineseColumnComment(String schema, String column) {
        assertThat(schema).as("%s has a Chinese comment", column)
                .containsPattern("(?im)(?:^|,)\\s*(?:ADD COLUMN\\s+)?" + column
                        + "\\b(?=[^\\r\\n]*[\\u4e00-\\u9fff])[^\\r\\n]*\\bCOMMENT\\s+'[^']+'");
    }

    private static String read(String relativePath) throws Exception {
        Path path = Path.of(relativePath);
        return Files.exists(path) ? Files.readString(path) : "";
    }
}
