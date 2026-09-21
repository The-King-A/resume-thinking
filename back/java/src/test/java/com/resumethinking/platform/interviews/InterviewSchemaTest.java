package com.resumethinking.platform.interviews;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class InterviewSchemaTest {
    @Test
    void freshSnapshotAndV16DefineInterviewPersistenceAndReadableSequences() throws Exception {
        String snapshot = read("../../database/resume_thinking_schema.sql");
        String migration = read("src/main/resources/db/migration/V16__interview_practice_phase_one.sql");

        for (String source : new String[]{snapshot, migration}) {
            assertThat(source).contains(
                    "CREATE TABLE interview_sessions",
                    "CREATE TABLE interview_questions",
                    "CREATE TABLE interview_answers",
                    "CREATE TABLE interview_feedback",
                    "CREATE TABLE interview_confirmations",
                    "CREATE TABLE interview_callback_receipts",
                    "answer_ciphertext",
                    "answer_nonce",
                    "callback_id");
            assertThat(source).containsPattern("(?s).*session[ '\"]?[^\n]*COMMENT.*");
        }
        assertThat(snapshot).contains("('session'", "('question'", "('answer'",
                "('feedback'", "('confirmation'");
        assertThat(migration).contains(
                "UNIQUE KEY uq_interview_sessions_owner_key",
                "UNIQUE KEY uq_interview_answers_session_key",
                "FOREIGN KEY (resume_id, revision_id)",
                "INDEX ix_interview_sessions_resume_state");
    }

    @Test
    void newInterviewColumnsCarryChineseMaintenanceComments() throws Exception {
        String snapshot = read("../../database/resume_thinking_schema.sql");
        String migration = read("src/main/resources/db/migration/V16__interview_practice_phase_one.sql");
        for (String source : new String[]{snapshot, migration}) {
            assertThat(source).containsPattern("(?s).*session_id[^\\n]*[\\u4e00-\\u9fff].*");
            assertThat(source).containsPattern("(?s).*answer_ciphertext[^\\n]*[\\u4e00-\\u9fff].*");
            assertThat(source).containsPattern("(?s).*feedback_payload_json[^\\n]*[\\u4e00-\\u9fff].*");
        }
    }

    private static String read(String relativePath) throws Exception {
        Path path = Path.of(relativePath);
        return Files.exists(path) ? Files.readString(path) : "";
    }
}
