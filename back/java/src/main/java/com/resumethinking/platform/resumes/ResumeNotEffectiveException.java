package com.resumethinking.platform.resumes;

import com.resumethinking.platform.profiles.ResourceNotFoundException;

/**
 * The logical resume exists, but it has never had a validated effective
 * revision.  Recovery must not report success for such a row because it
 * cannot be rendered in the effective-resume list.
 */
public final class ResumeNotEffectiveException extends ResourceNotFoundException {
    public ResumeNotEffectiveException() {
        super();
    }
}
