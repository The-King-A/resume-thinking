package com.resumethinking.platform.resumes;

public final class DuplicateResumeTitleException extends RuntimeException {
    public DuplicateResumeTitleException() {
        super("DUPLICATE_RESUME_TITLE");
    }
}
