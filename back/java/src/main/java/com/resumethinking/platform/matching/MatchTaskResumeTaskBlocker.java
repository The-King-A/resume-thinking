package com.resumethinking.platform.matching;

import com.resumethinking.platform.resumes.ResumeTaskBlocker;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;

/**
 * Blocks work which can no longer publish a result for a hidden resume.
 *
 * <p>The resume lifecycle service acquires the resume write lock before
 * calling this adapter.  The callback path follows the same resume-then-task
 * lock order, so a delete/archive cannot deadlock with a late callback.</p>
 */
@Component
public final class MatchTaskResumeTaskBlocker implements ResumeTaskBlocker {
    private static final Set<MatchTask.State> IN_FLIGHT = Set.of(MatchTask.State.QUEUED, MatchTask.State.PROCESSING);
    private final MatchTaskRepository tasks;

    public MatchTaskResumeTaskBlocker(MatchTaskRepository tasks) {
        this.tasks = tasks;
    }

    @Override
    public void blockPendingTasks(UUID resumeId) {
        if (resumeId == null) return;
        for (MatchTask task : tasks.findByResumeIdAndStateInForUpdate(resumeId, IN_FLIGHT)) {
            // The locked query filters this already; retain the guard so an
            // alternate repository implementation cannot block terminal work.
            if (task.getState() == MatchTask.State.QUEUED || task.getState() == MatchTask.State.PROCESSING) {
                task.markBlocked();
                tasks.saveAndFlush(task);
            }
        }
    }
}
