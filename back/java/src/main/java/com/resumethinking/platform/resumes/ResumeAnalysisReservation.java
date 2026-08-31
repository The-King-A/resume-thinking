package com.resumethinking.platform.resumes;


public record ResumeAnalysisReservation(String resumeId, long resumeVersion, Resume.SourceType sourceType) {}
