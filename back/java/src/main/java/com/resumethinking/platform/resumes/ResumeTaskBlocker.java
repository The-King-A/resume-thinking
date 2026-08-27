package com.resumethinking.platform.resumes;

import java.util.UUID;

/**
 * Coordinates the resume lifecycle with in-flight analysis work.
 *
 * <p>The implementation lives in the matching module so the resume module
 * does not depend on matching entities or repositories.  Implementations must
 * run in the caller's transaction and block only QUEUED/PROCESSING work for
 * the supplied resume.</p>
 */
@FunctionalInterface
public interface ResumeTaskBlocker {
    ResumeTaskBlocker NOOP = resumeId -> { };

    void blockPendingTasks(UUID resumeId);
}
