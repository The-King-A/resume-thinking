package com.resumethinking.platform.resumes;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import jakarta.persistence.Column;
import com.resumethinking.platform.matching.MatchTask;
import static org.assertj.core.api.Assertions.assertThat;

class ResumeSchemaTest {
 @Test void v2ContainsParserAndCompleteAuditLifecycleColumns() throws Exception {
  String v2=Files.readString(Path.of("src/main/resources/db/migration/V2__resumes_and_lifecycle.sql"));
  String delta=Files.readString(Path.of("src/main/resources/db/migration/V2_1__resume_lifecycle_completion.sql"));
  assertThat(v2).doesNotContain("parser_version", "prior_visibility_state", "new_visibility_state", "correlation_id");
 assertThat(delta).contains("parser_version", "prior_visibility_state", "new_visibility_state", "correlation_id", "DEFAULT 'v1'", "UUID_TO_BIN");
 }

 @Test void matchingTaskStorageUsesSafeTextTypeAndRoleFamilyMigration() throws Exception {
  String v3 = Files.readString(Path.of("src/main/resources/db/migration/V3__matching_tasks_results_and_evidence.sql"));
  String v5 = Files.readString(Path.of("src/main/resources/db/migration/V5__matching_task_job_family_and_text_width.sql"));
  String v6 = Files.readString(Path.of("src/main/resources/db/migration/V6__resume_ciphertext_mediumblob.sql"));
   assertThat(v3).contains("job_description_text VARCHAR(20000) NOT NULL")
     .doesNotContain("CHAR_LENGTH(job_description_text) <= 20000", "MEDIUMTEXT");
  assertThat(v5).contains("ADD COLUMN job_family", "DEFAULT 'JAVA_BACKEND'", "MODIFY COLUMN job_description_text MEDIUMTEXT", "chk_analysis_task_job_description_length", "CHAR_LENGTH(job_description_text) <= 20000", "chk_analysis_task_job_family");
  assertThat(v6).contains("MODIFY COLUMN raw_content_ciphertext MEDIUMBLOB");
  Column column = MatchTask.class.getDeclaredField("jobDescriptionText").getAnnotation(Column.class);
  assertThat(column).isNotNull();
  assertThat(column.columnDefinition()).isEqualTo("MEDIUMTEXT");
 }

 @Test void resumeCiphertextUsesUploadSizedStorage() throws Exception {
  String v2 = Files.readString(Path.of("src/main/resources/db/migration/V2__resumes_and_lifecycle.sql"));
  String v6 = Files.readString(Path.of("src/main/resources/db/migration/V6__resume_ciphertext_mediumblob.sql"));
  assertThat(v2).contains("raw_content_ciphertext BLOB NOT NULL");
  assertThat(v6).contains("MODIFY COLUMN raw_content_ciphertext MEDIUMBLOB NOT NULL");
  Column column = Resume.class.getDeclaredField("encryptedRawContent").getAnnotation(Column.class);
  assertThat(column).isNotNull();
  assertThat(column.columnDefinition()).isEqualTo("MEDIUMBLOB");
 }
}
