package com.resumethinking.platform.matching;

import com.resumethinking.platform.TestIds;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MatchTaskTimeoutServiceTest {
    @Test
    void marks_stale_processing_tasks_as_timed_out_after_callback_delivery_exhaustion() {
        var tasks = new MatchTaskRepository.InMemory();
        var task = processingTask(tasks);
        var now = task.getUpdatedAt().plus(Duration.ofMinutes(6));
        var service = new MatchTaskTimeoutService(tasks, Clock.fixed(now, ZoneOffset.UTC), Duration.ofMinutes(5));

        assertThat(service.timeoutStaleTasks()).isEqualTo(1);
        assertThat(task.getState()).isEqualTo(MatchTask.State.TIMED_OUT);
        assertThat(task.getFailureCode()).isEqualTo("CALLBACK_DELIVERY_FAILED");
    }

    @Test
    void leaves_recent_processing_tasks_available_for_the_callback() {
        var tasks = new MatchTaskRepository.InMemory();
        var task = processingTask(tasks);
        var now = task.getUpdatedAt().plus(Duration.ofMinutes(4));
        var service = new MatchTaskTimeoutService(tasks, Clock.fixed(now, ZoneOffset.UTC), Duration.ofMinutes(5));

        assertThat(service.timeoutStaleTasks()).isZero();
        assertThat(task.getState()).isEqualTo(MatchTask.State.PROCESSING);
    }

    private static MatchTask processingTask(MatchTaskRepository tasks) {
        var task = new MatchTask(
                TestIds.task(),
                TestIds.callback(),
                TestIds.resume(),
                TestIds.profile(),
                TestIds.user(),
                0,
                JobFamily.JAVA_BACKEND,
                "Build reliable software with clear communication and practical testing.",
                "callback-timeout-test-key",
                "t".repeat(64),
                Set.of(),
                java.time.Instant.now());
        task.markProcessing();
        tasks.save(task);
        return task;
    }
}
