package com.resumethinking.platform.resumes;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;

class ResumeSchemaTest {
 @Test void v2ContainsParserAndCompleteAuditLifecycleColumns() throws Exception {
  String v2=Files.readString(Path.of("src/main/resources/db/migration/V2__resumes_and_lifecycle.sql"));
  String delta=Files.readString(Path.of("src/main/resources/db/migration/V2_1__resume_lifecycle_completion.sql"));
  assertThat(v2).doesNotContain("parser_version", "prior_visibility_state", "new_visibility_state", "correlation_id");
  assertThat(delta).contains("parser_version", "prior_visibility_state", "new_visibility_state", "correlation_id", "DEFAULT 'v1'", "UUID_TO_BIN");
 }
}
