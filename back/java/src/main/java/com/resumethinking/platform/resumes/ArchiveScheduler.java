package com.resumethinking.platform.resumes;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import java.time.Clock;

@Component
public class ArchiveScheduler { private final ResumeLifecycleService service; private final Clock clock; @Autowired public ArchiveScheduler(ResumeLifecycleService service, Clock clock){this.service=service;this.clock=clock;} public ArchiveScheduler(ResumeLifecycleService service){this(service,Clock.systemUTC());} @Scheduled(fixedDelay=60000) public void archiveDue(){service.archiveDue(clock.instant());} }
