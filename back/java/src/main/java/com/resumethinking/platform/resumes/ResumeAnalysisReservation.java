package com.resumethinking.platform.resumes;


public record ResumeAnalysisReservation(String resumeId, String revisionId, long resumeVersion,
                                        Resume.SourceType sourceType) {}
