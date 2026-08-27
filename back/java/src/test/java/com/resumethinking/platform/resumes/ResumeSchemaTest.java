package com.resumethinking.platform.resumes;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;

class ResumeSchemaTest {
 @Test void v2ContainsParserAndCompleteAuditLifecycleColumns() throws Exception {
  String sql=Files.readString(Path.of("src/main/resources/db/migration/V2__resumes_and_lifecycle.sql"));
  assertThat(sql).contains("parser_version", "prior_visibility_state", "new_visibility_state", "correlation_id");
 }
}
