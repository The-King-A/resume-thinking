package com.resumethinking.platform.resumes;

import java.util.UUID;

public record ResumeAnalysisReservation(UUID resumeId, long resumeVersion, Resume.SourceType sourceType) {}
