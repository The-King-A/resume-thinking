package com.resumethinking.platform.interviews;

import java.time.Instant;
import java.util.List;

public final class InterviewResponse {
    private InterviewResponse() { }

    public record SessionResponse(String id, String resumeId, String revisionId, String matchTaskId,
                                  String jobFamily, InterviewSession.State state,
                                   InterviewSession.WorkType workType, int questionCount,
                                   String answeredQuestionId, String activeQuestionId, String feedbackId, String failureCode,
                                  long version, Instant createdAt, Instant updatedAt, Instant deletedAt) {
        public static SessionResponse from(InterviewSession value) {
            String answeredQuestionId = value.getCurrentAnswerId() == null ? null : value.getCurrentQuestionId();
            return new SessionResponse(value.getId(), value.getResumeId(), value.getRevisionId(), value.getMatchTaskId(),
                      value.getJobFamily().name(), value.getState(), value.getWorkType(), value.getQuestionCount(),
                      answeredQuestionId, value.getCurrentQuestionId(), value.getFeedbackId(), value.getFailureCode(), value.getVersion(),
                    value.getCreatedAt(), value.getUpdatedAt(), value.getDeletedAt());
        }
    }

    public record QuestionResponse(String id, int sequence, String questionType, String difficulty,
                                   String questionText, String requirementId, String requirementText,
                                   List<String> evidenceIds, String generationReason, double confidence, boolean answered) {
        public static QuestionResponse from(InterviewQuestion value, List<String> evidenceIds, boolean answered) {
            return new QuestionResponse(value.getId(), value.getSequenceNo(), value.getQuestionType(), value.getDifficulty(),
                    value.getQuestionText(), value.getRequirementId(), value.getRequirementText(), evidenceIds,
                    value.getGenerationReason(), value.getConfidence(), answered);
        }
    }

    public record AnswerSubmissionResponse(SessionResponse session, String answerId, String state, boolean feedbackAvailable) { }
    public record FeedbackResponse(String id, String sessionId, String answerId, String payloadJson,
                                   long version, Instant createdAt) {
        public static FeedbackResponse from(InterviewFeedback value) {
            return new FeedbackResponse(value.getId(), value.getSessionId(), value.getAnswerId(), value.getPayloadJson(),
                    value.getFeedbackVersion(), value.getCreatedAt());
        }
    }

    public record ConfirmationResponse(String id, String sessionId, String feedbackId, String claimId,
                                       String decision, Instant createdAt) {
        public static ConfirmationResponse from(InterviewConfirmation value) {
            return new ConfirmationResponse(value.getId(), value.getSessionId(), value.getFeedbackId(), value.getClaimId(),
                    value.getDecision(), value.getCreatedAt());
        }
    }
}
