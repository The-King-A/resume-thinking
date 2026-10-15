package com.resumethinking.platform.resumes;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.nio.file.Files;
import java.nio.file.Path;
import jakarta.persistence.Column;
import com.resumethinking.platform.matching.MatchTask;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.util.Map;
import java.util.regex.Pattern;

class ResumeSchemaTest {
 @Test void v3SubmissionFingerprintUsesAnAdditiveDurableMigration() throws Exception {
  Path migration=Path.of("src/main/resources/db/migration/V12__match_task_submission_fingerprint.sql");
  assertThat(migration).exists();
  String v12=Files.readString(migration);
  String snapshot=Files.readString(Path.of("../../database/resume_thinking_schema.sql"));

  assertThat(v12).contains("ADD COLUMN submission_fingerprint", "CHAR(64)")
    .containsPattern("(?is)submission_fingerprint\\b[^;]*COMMENT\\s+'[^']*[\\u4e00-\\u9fff][^']*'");
  assertThat(snapshot).contains("submission_fingerprint CHAR(64)");
  Column column=MatchTask.class.getDeclaredField("submissionFingerprint").getAnnotation(Column.class);
  assertThat(column.name()).isEqualTo("submission_fingerprint");
  assertThat(column.length()).isEqualTo(64);
 }
 @Test void publicationStateUsesAdditiveV11AndKeepsV10Frozen() throws Exception {
  String v10=Files.readString(Path.of("src/main/resources/db/migration/V10__effective_resume_revisions.sql"));
  String v11=Files.readString(Path.of("src/main/resources/db/migration/V11__match_task_publication_state.sql"));
  String snapshot=Files.readString(Path.of("../../database/resume_thinking_schema.sql"));

  assertThat(v10).doesNotContain("publication_state");
  assertThat(v11).contains("ADD COLUMN publication_state", "DEFAULT 'NOT_REQUESTED'",
      "COMMENT '").contains("NOT_REQUESTED", "PENDING", "PUBLISHED", "REJECTED_DUPLICATE_TITLE");
  assertThat(snapshot).contains("publication_state VARCHAR(32) NOT NULL");
  Column column=MatchTask.class.getDeclaredField("publicationState").getAnnotation(Column.class);
  assertThat(column.name()).isEqualTo("publication_state");
  assertThat(column.nullable()).isFalse();
 }
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

 @Test void pendingReplacementDoesNotChangeTheV2CompatibilityProjection() {
  byte[] legacyCiphertext = {1, 2, 3};
  byte[] legacyNonce = {4, 5, 6};
  Resume resume = new Resume("resume001", "user001", "Old", Resume.SourceType.TXT,
    com.resumethinking.platform.auth.UserRole.USER, legacyCiphertext, legacyNonce,
    java.time.Instant.parse("2026-09-02T00:00:00Z"), "v1");
  ResumeRevision revision = new ResumeRevision("revision002", "resume001", 2,
    "New", "new", Resume.SourceType.DOCX, "v2", new byte[]{7, 8, 9},
    new byte[]{10, 11, 12}, ResumeRevision.State.PENDING);

  resume.stageRevision(revision, java.time.Instant.parse("2026-09-02T01:00:00Z"));

  assertThat(resume.getTitle()).isEqualTo("Old");
  assertThat(resume.getSourceType()).isEqualTo(Resume.SourceType.TXT);
  assertThat(resume.getParserVersion()).isEqualTo("v1");
  assertThat(resume.getEncryptedRawContent()).containsExactly(1, 2, 3);
  assertThat(resume.getRawContentNonce()).containsExactly(4, 5, 6);
 }

 @Test void publishingReplacementAtomicallyUpdatesTheV2CompatibilityProjection() throws Exception {
  Column ciphertext = Resume.class.getDeclaredField("encryptedRawContent").getAnnotation(Column.class);
  Column nonce = Resume.class.getDeclaredField("rawContentNonce").getAnnotation(Column.class);
  assertThat(ciphertext.updatable()).isTrue();
  assertThat(nonce.updatable()).isTrue();

  byte[] legacyCiphertext = {1, 2, 3};
  byte[] legacyNonce = {4, 5, 6};
  Resume resume = new Resume("resume001", "user001", "Old", Resume.SourceType.TXT,
    com.resumethinking.platform.auth.UserRole.USER, legacyCiphertext, legacyNonce,
    java.time.Instant.parse("2026-09-02T00:00:00Z"), "v1");
  byte[] replacementCiphertext = {7, 8, 9};
  byte[] replacementNonce = {10, 11, 12};
  ResumeRevision revision = new ResumeRevision("revision002", "resume001", 2,
    "New", "new", Resume.SourceType.DOCX, "v2", replacementCiphertext,
    replacementNonce, ResumeRevision.State.PENDING);

  resume.stageRevision(revision, java.time.Instant.parse("2026-09-02T00:30:00Z"));
  revision.markEffective();
  resume.publish(revision, java.time.Instant.parse("2026-09-02T01:00:00Z"));
  replacementCiphertext[0] = 99;
  replacementNonce[0] = 99;
  byte[] returnedCiphertext = resume.getEncryptedRawContent();
  byte[] returnedNonce = resume.getRawContentNonce();
  returnedCiphertext[1] = 99;
  returnedNonce[1] = 99;

  assertThat(resume.getTitle()).isEqualTo("New");
  assertThat(resume.getSourceType()).isEqualTo(Resume.SourceType.DOCX);
  assertThat(resume.getParserVersion()).isEqualTo("v2");
  assertThat(resume.getEncryptedRawContent()).containsExactly(7, 8, 9);
  assertThat(resume.getRawContentNonce()).containsExactly(10, 11, 12);
 }

 @ParameterizedTest
 @EnumSource(value = ResumeRevision.State.class, names = {"PENDING", "FAILED", "SUPERSEDED"})
 void nonEffectiveRevisionCannotChangeAnyPublishedField(ResumeRevision.State state) {
  Resume resume = resumeWithPublishedRevision();
  ResumeRevision candidate = revision("revision002", state, Resume.SourceType.DOCX,
    "Candidate", "candidate", "v2", new byte[]{7, 8, 9}, new byte[]{10, 11, 12});
  resume.stageRevision(candidate, java.time.Instant.parse("2026-09-02T01:00:00Z"));
  java.time.Instant stagedAt = resume.getUpdatedAt();

  assertThatThrownBy(() -> resume.publish(candidate, java.time.Instant.parse("2026-09-02T02:00:00Z")))
    .isInstanceOf(IllegalStateException.class)
    .hasMessage("only an effective revision can be published");

  assertThat(resume.getEffectiveRevisionId()).isEqualTo("revision001");
  assertThat(resume.getPendingRevisionId()).isEqualTo("revision002");
  assertThat(resume.getEffectiveTitleKey()).isEqualTo("published");
  assertThat(resume.getTitle()).isEqualTo("Published");
  assertThat(resume.getSourceType()).isEqualTo(Resume.SourceType.TXT);
  assertThat(resume.getParserVersion()).isEqualTo("v1");
  assertThat(resume.getEncryptedRawContent()).containsExactly(1, 2, 3);
  assertThat(resume.getRawContentNonce()).containsExactly(4, 5, 6);
  assertThat(resume.getUpdatedAt()).isEqualTo(stagedAt);
 }

 @Test void effectiveReplacementMustMatchThePendingPointer() {
  Resume resume = resumeWithPublishedRevision();
  ResumeRevision staged = revision("revision002", ResumeRevision.State.PENDING,
    Resume.SourceType.DOCX, "Staged", "staged", "v2", new byte[]{7}, new byte[]{8});
  ResumeRevision other = revision("revision003", ResumeRevision.State.EFFECTIVE,
    Resume.SourceType.DOCX, "Other", "other", "v3", new byte[]{9}, new byte[]{10});
  resume.stageRevision(staged, java.time.Instant.parse("2026-09-02T01:00:00Z"));

  assertThatThrownBy(() -> resume.publish(other, java.time.Instant.parse("2026-09-02T02:00:00Z")))
    .isInstanceOf(IllegalStateException.class)
    .hasMessage("revision is not the staged publication candidate");

  assertThat(resume.getEffectiveRevisionId()).isEqualTo("revision001");
  assertThat(resume.getPendingRevisionId()).isEqualTo("revision002");
  assertThat(resume.getTitle()).isEqualTo("Published");
  assertThat(resume.getEncryptedRawContent()).containsExactly(1, 2, 3);
 }

 @Test void initialAndUnchangedEffectivePublicationDoNotRequireAPendingPointer() {
  Resume resume = new Resume("resume001", "user001", "Legacy", Resume.SourceType.TXT,
    com.resumethinking.platform.auth.UserRole.USER, new byte[]{0}, new byte[]{0},
    java.time.Instant.parse("2026-09-02T00:00:00Z"), "v0");
  ResumeRevision effective = revision("revision001", ResumeRevision.State.EFFECTIVE,
    Resume.SourceType.TXT, "Published", "published", "v1", new byte[]{1, 2, 3}, new byte[]{4, 5, 6});

  resume.publish(effective, java.time.Instant.parse("2026-09-02T01:00:00Z"));
  resume.publish(effective, java.time.Instant.parse("2026-09-02T02:00:00Z"));

  assertThat(resume.getEffectiveRevisionId()).isEqualTo("revision001");
  assertThat(resume.getPendingRevisionId()).isNull();
  assertThat(resume.getEncryptedRawContent()).containsExactly(1, 2, 3);
 }

 @Test void logicalResumeMapsNullableRevisionPointersAndApplicationMaintainedEffectiveTitleKey() throws Exception {
  Column effectiveRevision = Resume.class.getDeclaredField("effectiveRevisionId").getAnnotation(Column.class);
  Column pendingRevision = Resume.class.getDeclaredField("pendingRevisionId").getAnnotation(Column.class);
  Column effectiveTitleKey = Resume.class.getDeclaredField("effectiveTitleKey").getAnnotation(Column.class);

  assertThat(effectiveRevision.name()).isEqualTo("effective_revision_id");
  assertThat(effectiveRevision.nullable()).isTrue();
  assertThat(pendingRevision.name()).isEqualTo("pending_revision_id");
  assertThat(pendingRevision.nullable()).isTrue();
  assertThat(effectiveTitleKey.name()).isEqualTo("effective_title_key");
  assertThat(effectiveTitleKey.insertable()).isTrue();
  assertThat(effectiveTitleKey.updatable()).isTrue();
 }

 private static Resume resumeWithPublishedRevision() {
  Resume resume = new Resume("resume001", "user001", "Legacy", Resume.SourceType.TXT,
    com.resumethinking.platform.auth.UserRole.USER, new byte[]{0}, new byte[]{0},
    java.time.Instant.parse("2026-09-02T00:00:00Z"), "v0");
  resume.publish(revision("revision001", ResumeRevision.State.EFFECTIVE,
    Resume.SourceType.TXT, "Published", "published", "v1",
    new byte[]{1, 2, 3}, new byte[]{4, 5, 6}), java.time.Instant.parse("2026-09-02T00:30:00Z"));
  return resume;
 }

 private static ResumeRevision revision(String id, ResumeRevision.State state,
                                         Resume.SourceType sourceType, String title, String titleKey,
                                         String parserVersion, byte[] ciphertext, byte[] nonce) {
  return new ResumeRevision(id, "resume001", Integer.parseInt(id.substring("revision".length())),
    title, titleKey, sourceType, parserVersion, ciphertext, nonce, state);
 }

 @Test void allBusinessColumnsHaveChineseCommentsInFreshAndMigratedSchemas() throws Exception {
  String snapshot = Files.readString(Path.of("../../database/resume_thinking_schema.sql"));
  String migration = Files.readString(Path.of("src/main/resources/db/migration/V7__column_comments_zh.sql"));
  String stringIdMigration = Files.readString(Path.of("src/main/resources/db/migration/V8__string_business_ids.sql"));
  Map<String, String[]> columns = Map.of(
    "users", new String[]{"id", "username", "email", "password_hash", "role", "created_at"},
    "llm_profiles", new String[]{"id", "owner_id", "display_name", "endpoint_url", "model_name", "api_key_ciphertext", "api_key_nonce", "key_version", "selected", "last_test_status", "last_tested_at", "created_at", "updated_at"},
    "resumes", new String[]{"id", "owner_id", "title", "source_type", "parser_version", "raw_content_ciphertext", "raw_content_nonce", "creator_role", "status", "visibility_state", "visible_until", "soft_deleted_by", "soft_deleted_at", "archived_at", "restored_at", "created_at", "updated_at", "version"},
    "resume_recovery_audit", new String[]{"id", "resume_id", "actor_id", "action", "prior_visibility_state", "new_visibility_state", "visibility_state", "occurred_at", "correlation_id"},
    "analysis_tasks", new String[]{"id", "resume_id", "llm_profile_id", "creator_id", "resume_version", "job_family", "job_description_text", "idempotency_key", "attempt", "callback_token_hash", "state", "failure_code", "result_available", "created_at", "updated_at", "version"},
    "analysis_evidence", new String[]{"id", "task_id", "source_location", "source_start", "source_end", "source_excerpt", "source_type"},
    "analysis_results", new String[]{"id", "task_id", "resume_id", "resume_version", "payload_json", "completed_at"},
    "analysis_callback_receipts", new String[]{"callback_id", "payload_hash", "received_at"}
  );
  for (String[] fields : columns.values()) {
   for (String field : fields) {
    assertThat(snapshot).as("fresh schema field %s", field)
      .containsPattern("(?im)(?:^|,)\\s*" + Pattern.quote(field) + "\\b(?=[^\\r\\n]*[\\u4e00-\\u9fff])[^\\r\\n]*\\bCOMMENT\\s+'[^']+'");
    assertThat(migration).as("V7 comment migration field %s", field)
      .containsPattern("(?is)MODIFY COLUMN\\s+" + Pattern.quote(field) + "\\b(?=[^;]*[\\u4e00-\\u9fff])[^;]*?COMMENT\\s+'[^']+'");
   }
  }
  assertThat(snapshot).as("fresh schema field callback_id")
    .containsPattern("(?im)(?:^|,)\\s*callback_id\\b(?=[^\\r\\n]*[\\u4e00-\\u9fff])[^\\r\\n]*\\bCOMMENT\\s+'[^']+'");
  assertThat(stringIdMigration).as("v8 string ID migration callback_id comment")
    .containsPattern("(?is)CHANGE COLUMN callback_id_v2 callback_id\\b[^;]*COMMENT\\s+'[^']+'");
 }
}
