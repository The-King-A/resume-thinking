package com.resumethinking.platform.resumes;

import org.junit.jupiter.api.Test;
import java.time.*;
import static org.mockito.Mockito.*;

class ArchiveSchedulerTest {
 @Test void schedulerUsesInjectedClock(){
  var service=mock(ResumeLifecycleService.class); var instant=Instant.parse("2026-01-01T00:00:00Z");
  new ArchiveScheduler(service,Clock.fixed(instant,ZoneOffset.UTC)).archiveDue();
  verify(service).archiveDue(instant);
 }
}
