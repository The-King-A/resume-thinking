package com.resumethinking.platform.resumes;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import org.mockito.ArgumentCaptor;

class ResumeAuditRepositoryTest {
 @Test void jpaAdapterWritesAllAuditFields(){
  var delegate=mock(ResumeAuditJpaRepository.class);
  var adapter=new JpaResumeAuditRepository(delegate);
  UUID resumeId=UUID.randomUUID(), actorId=UUID.randomUUID(), correlationId=UUID.randomUUID();
  Instant at=Instant.parse("2026-01-01T00:00:00Z");
  adapter.save(new ResumeAuditRepository.ResumeLifecycleAudit(resumeId,actorId,"RESTORED",
      VisibilityState.USER_SOFT_DELETED,VisibilityState.ACTIVE,at,correlationId));
  var captor=ArgumentCaptor.forClass(ResumeAudit.class);
  verify(delegate).save(captor.capture());
  ResumeAudit saved=captor.getValue();
  assertThat(saved.getResumeId()).isEqualTo(resumeId);
  assertThat(saved.getActorId()).isEqualTo(actorId);
  assertThat(saved.getAction()).isEqualTo("RESTORED");
  assertThat(saved.getPriorVisibilityState()).isEqualTo(VisibilityState.USER_SOFT_DELETED);
  assertThat(saved.getNewVisibilityState()).isEqualTo(VisibilityState.ACTIVE);
  assertThat(saved.getOccurredAt()).isEqualTo(at);
  assertThat(saved.getCorrelationId()).isEqualTo(correlationId);
 }
}
