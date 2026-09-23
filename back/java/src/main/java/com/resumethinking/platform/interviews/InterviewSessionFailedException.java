package com.resumethinking.platform.interviews;

public final class InterviewSessionFailedException extends RuntimeException {
    public InterviewSessionFailedException(String code) {
        super(code == null || code.isBlank() ? "INTERVIEW_MODEL_OUTPUT_INVALID" : code);
    }
}
