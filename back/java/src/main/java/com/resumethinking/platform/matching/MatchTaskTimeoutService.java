package com.resumethinking.platform.matching;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Stops polling from leaving a task permanently in progress after delivery fails. */
@Component
public class MatchTaskTimeoutService {
    private static final String CALLBACK_DELIVERY_FAILED = "CALLBACK_DELIVERY_FAILED";

    private final MatchTaskRepository tasks;
    private final Clock clock;
    private final Duration lease;

    @Autowired
    public MatchTaskTimeoutService(MatchTaskRepository tasks, Clock clock,
                                   @Value("${app.match-task-processing-lease:PT5M}") Duration lease) {
        this.tasks = tasks;
        this.clock = clock;
        if (lease == null || lease.isNegative() || lease.isZero()) {
            throw new IllegalArgumentException("match task processing lease must be positive");
        }
        this.lease = lease;
    }

    @Scheduled(fixedDelayString = "${app.match-task-timeout-check-delay:60000}")
    @Transactional
    public int timeoutStaleTasks() {
        Instant cutoff = clock.instant().minus(lease);
        int timedOut = 0;
        for (MatchTask task : tasks.findProcessingUpdatedBeforeForUpdate(cutoff)) {
            // The JPA query holds a write lock.  Recheck state so an alternate
            // repository cannot change a task that was deleted/blocked while
            // the scheduler was waiting for its lock.
            if (task.getState() == MatchTask.State.PROCESSING && task.getUpdatedAt().isBefore(cutoff)) {
                task.markTimedOut(CALLBACK_DELIVERY_FAILED);
                tasks.saveAndFlush(task);
                timedOut++;
            }
        }
        return timedOut;
    }
}
