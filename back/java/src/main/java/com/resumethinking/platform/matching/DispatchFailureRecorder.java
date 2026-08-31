package com.resumethinking.platform.matching;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Persists dispatch failures after the create transaction has committed. */
@Component
public class DispatchFailureRecorder {
    private final MatchTaskRepository tasks;
    public DispatchFailureRecorder(MatchTaskRepository tasks) { this.tasks = tasks; }
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(String taskId, String code) {
        tasks.findByIdForUpdate(taskId).ifPresent(task -> {
            if (task.getState() == MatchTask.State.QUEUED || task.getState() == MatchTask.State.PROCESSING) {
                task.markFailed(code); tasks.save(task);
            }
        });
    }
}
