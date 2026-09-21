from __future__ import annotations

from typing import Annotated, Literal
from uuid import UUID

from pydantic import ConfigDict, Field, StrictFloat, StrictInt, StringConstraints, model_validator

from .models import Provider, StrictModel

SessionId = Annotated[str, StringConstraints(pattern=r"^session[0-9]{3,}$", max_length=64)]
QuestionId = Annotated[str, StringConstraints(pattern=r"^question[0-9]{3,}$", max_length=64)]
AnswerId = Annotated[str, StringConstraints(pattern=r"^answer[0-9]{3,}$", max_length=64)]
FeedbackId = Annotated[str, StringConstraints(pattern=r"^feedback[0-9]{3,}$", max_length=64)]
ClaimId = Annotated[str, StringConstraints(pattern=r"^claim[0-9]{3,}$", max_length=64)]
TaskId = Annotated[str, StringConstraints(pattern=r"^task[0-9]{3,}$", max_length=64)]
RevisionId = Annotated[str, StringConstraints(pattern=r"^revision[0-9]{3,}$", max_length=64)]
CallbackId = Annotated[str, StringConstraints(pattern=r"^callback[0-9]{3,}$", max_length=64)]
EvidenceId = Annotated[str, StringConstraints(pattern=r"^evidence[0-9]{3,}$", max_length=64)]
RequirementId = Annotated[str, StringConstraints(pattern=r"^requirement[0-9]{3,}$", max_length=64)]

QuestionType = Literal["BASIC_CONFIRMATION", "PROJECT_DEEP_DIVE", "JOB_SCENARIO", "SYNTHESIS_FOLLOW_UP"]
FeedbackLevel = Literal["HIGH", "MEDIUM", "LOW", "INSUFFICIENT_EVIDENCE"]
ClaimState = Literal["SUPPORTED_FACT", "WORDING_ONLY_REWRITE", "NEEDS_USER_CONFIRMATION", "RISKY_OR_UNSUPPORTED"]
_FEEDBACK_LEVEL_LABELS = {
    "HIGH": "较好",
    "MEDIUM": "可继续完善",
    "LOW": "需要加强",
    "INSUFFICIENT_EVIDENCE": "证据不足",
}


class InterviewEvidence(StrictModel):
    evidence_id: EvidenceId = Field(alias="evidenceId")
    source_location: str = Field(alias="sourceLocation", min_length=1, max_length=500)
    source_start: StrictInt = Field(alias="sourceStart", ge=0)
    source_end: StrictInt = Field(alias="sourceEnd", ge=1)
    excerpt: str = Field(min_length=1, max_length=5000)
    strength: Literal["NONE", "LOW", "MEDIUM", "HIGH"]

    @model_validator(mode="after")
    def valid_range(self) -> "InterviewEvidence":
        if self.source_start >= self.source_end:
            raise ValueError("evidence range must be non-empty")
        return self


class InterviewRequirement(StrictModel):
    requirement_id: RequirementId = Field(alias="requirementId")
    requirement_text: str = Field(alias="requirementText", min_length=1, max_length=5000)
    requirement_type: Literal["MANDATORY", "PREFERRED"] = Field(alias="requirementType")
    match_status: Literal["SATISFIED", "PARTIALLY_SATISFIED", "RELATED_BUT_EVIDENCE_INSUFFICIENT", "UNMET"] = Field(alias="matchStatus")
    gap: str | None = Field(default=None, max_length=2000)
    evidence: list[InterviewEvidence]


class QuestionGeneration(StrictModel):
    requirements: list[InterviewRequirement] = Field(min_length=1)
    question_types: list[QuestionType] = Field(alias="questionTypes", min_length=4, max_length=4)
    max_questions: Literal[4] = Field(alias="maxQuestions")

    @model_validator(mode="after")
    def all_types_once(self) -> "QuestionGeneration":
        if set(self.question_types) != {"BASIC_CONFIRMATION", "PROJECT_DEEP_DIVE", "JOB_SCENARIO", "SYNTHESIS_FOLLOW_UP"}:
            raise ValueError("question types must be the documented four types")
        return self


class InterviewQuestion(StrictModel):
    question_id: QuestionId = Field(alias="questionId")
    sequence: StrictInt = Field(ge=1, le=4)
    question_type: QuestionType = Field(alias="questionType")
    difficulty: Literal["BASIC", "INTERMEDIATE", "ADVANCED"]
    question_text: str = Field(alias="questionText", min_length=1, max_length=2000)
    requirement_id: RequirementId = Field(alias="requirementId")
    requirement_text: str = Field(alias="requirementText", min_length=1, max_length=5000)
    evidence_ids: list[EvidenceId] = Field(alias="evidenceIds")
    generation_reason: str = Field(alias="generationReason", min_length=1, max_length=2000)
    confidence: StrictFloat = Field(ge=0, le=1)


class InterviewQuestionSet(StrictModel):
    questions: list[InterviewQuestion] = Field(min_length=4, max_length=4)

    @model_validator(mode="after")
    def each_question_type_once(self) -> "InterviewQuestionSet":
        types = {question.question_type for question in self.questions}
        sequences = {question.sequence for question in self.questions}
        if types != {"BASIC_CONFIRMATION", "PROJECT_DEEP_DIVE", "JOB_SCENARIO", "SYNTHESIS_FOLLOW_UP"} or sequences != {1, 2, 3, 4}:
            raise ValueError("question set must contain each documented type and sequence exactly once")
        return self


class AnswerQuestionContext(StrictModel):
    question_id: QuestionId = Field(alias="questionId")
    question_type: QuestionType = Field(alias="questionType")
    question_text: str = Field(alias="questionText", min_length=1, max_length=2000)
    requirement_id: RequirementId = Field(alias="requirementId")
    requirement_text: str = Field(alias="requirementText", min_length=1, max_length=5000)
    evidence_ids: list[EvidenceId] = Field(alias="evidenceIds")


class AnswerAnalysis(StrictModel):
    answer_id: AnswerId = Field(alias="answerId")
    answer_text: str = Field(alias="answerText", min_length=1, max_length=8000)
    question: AnswerQuestionContext
    requirement: InterviewRequirement
    evidence: list[InterviewEvidence]


class InterviewRiskFlag(StrictModel):
    code: str = Field(min_length=1, max_length=80)
    message: str = Field(min_length=1, max_length=1000)
    level: FeedbackLevel
    claim_state: ClaimState = Field(alias="claimState")


class InterviewClaim(StrictModel):
    id: ClaimId
    claim_text: str = Field(alias="claimText", min_length=1, max_length=2000)
    state: ClaimState
    evidence_ids: list[EvidenceId] = Field(alias="evidenceIds")
    applied: Literal[False]

    @model_validator(mode="after")
    def evidence_for_supported_claims(self) -> "InterviewClaim":
        if self.state in {"SUPPORTED_FACT", "WORDING_ONLY_REWRITE"} and not self.evidence_ids:
            raise ValueError("supported claim requires evidence")
        return self


class InterviewFeedbackPayload(StrictModel):
    feedback_id: FeedbackId = Field(alias="feedbackId")
    answer_id: AnswerId = Field(alias="answerId")
    state: Literal["FEEDBACK_READY"]
    relevance: FeedbackLevel
    completeness: FeedbackLevel
    technical_accuracy: FeedbackLevel = Field(alias="technicalAccuracy")
    factual_consistency: FeedbackLevel = Field(alias="factualConsistency")
    clarity: FeedbackLevel
    evidence_ids: list[EvidenceId] = Field(alias="evidenceIds")
    risk_flags: list[InterviewRiskFlag] = Field(alias="riskFlags")
    claims: list[InterviewClaim]
    improvement_suggestion: str = Field(alias="improvementSuggestion", min_length=1, max_length=3000)
    suggested_answer: str | None = Field(default=None, alias="suggestedAnswer", max_length=8000)
    answer_comparison: str | None = Field(default=None, alias="answerComparison", max_length=5000)
    version: StrictInt = Field(ge=0)

    @model_validator(mode="after")
    def populate_display_fields(self) -> "InterviewFeedbackPayload":
        improvement = self.improvement_suggestion.strip()
        if not improvement:
            raise ValueError("improvementSuggestion must not be blank")
        suggested = self.suggested_answer.strip() if isinstance(self.suggested_answer, str) else ""
        comparison = self.answer_comparison.strip() if isinstance(self.answer_comparison, str) else ""
        if not suggested:
            suggested = improvement
        if not comparison:
            comparison = (
                f"本次回答的相关性为{_FEEDBACK_LEVEL_LABELS[self.relevance]}，"
                f"完整性为{_FEEDBACK_LEVEL_LABELS[self.completeness]}，"
                f"技术准确性为{_FEEDBACK_LEVEL_LABELS[self.technical_accuracy]}，"
                f"事实一致性为{_FEEDBACK_LEVEL_LABELS[self.factual_consistency]}，"
                f"表达清晰度为{_FEEDBACK_LEVEL_LABELS[self.clarity]}。"
                f"请结合建议回答继续完善：{improvement}"
            )
        return self.model_copy(update={
            "improvement_suggestion": improvement,
            "suggested_answer": suggested,
            "answer_comparison": comparison,
        })


class InterviewJob(StrictModel):
    model_config = ConfigDict(extra="forbid", validate_by_alias=True, validate_by_name=False)
    contract_version: Literal["4.0"] = Field(alias="contractVersion")
    work_type: Literal["QUESTION_GENERATION", "ANSWER_ANALYSIS"] = Field(alias="workType")
    session_id: SessionId = Field(alias="sessionId")
    revision_id: RevisionId = Field(alias="revisionId")
    match_task_id: TaskId = Field(alias="matchTaskId")
    session_version: StrictInt = Field(alias="sessionVersion", ge=0)
    attempt: StrictInt = Field(ge=1)
    callback_id: CallbackId = Field(alias="callbackId")
    callback_url: str = Field(alias="callbackUrl", min_length=1, max_length=2048)
    callback_token: str = Field(alias="callbackToken", min_length=32, max_length=1024)
    redaction_required: Literal[True] = Field(alias="redactionRequired")
    provider: Provider
    question_generation: QuestionGeneration | None = Field(default=None, alias="questionGeneration")
    answer_analysis: AnswerAnalysis | None = Field(default=None, alias="answerAnalysis")
    correlation_id: UUID = Field(alias="correlationId")

    @model_validator(mode="after")
    def exactly_one_work_payload(self) -> "InterviewJob":
        if self.work_type == "QUESTION_GENERATION" and self.question_generation is not None and self.answer_analysis is None:
            return self
        if self.work_type == "ANSWER_ANALYSIS" and self.answer_analysis is not None and self.question_generation is None:
            return self
        raise ValueError("work type must carry exactly its matching payload")


class InterviewCallback(StrictModel):
    contract_version: Literal["4.0"] = Field(alias="contractVersion")
    work_type: Literal["QUESTION_GENERATION", "ANSWER_ANALYSIS"] = Field(alias="workType")
    session_id: SessionId = Field(alias="sessionId")
    revision_id: RevisionId = Field(alias="revisionId")
    match_task_id: TaskId = Field(alias="matchTaskId")
    session_version: StrictInt = Field(alias="sessionVersion", ge=0)
    attempt: StrictInt = Field(ge=1)
    callback_id: CallbackId = Field(alias="callbackId")
    callback_token: str = Field(alias="callbackToken", min_length=32, max_length=1024)
    payload_hash: str = Field(alias="payloadHash", pattern=r"^[a-f0-9]{64}$")
    outcome: Literal["SUCCEEDED", "FAILED", "TIMED_OUT"]
    correlation_id: UUID = Field(alias="correlationId")
    questions: list[InterviewQuestion] | None = None
    feedback: InterviewFeedbackPayload | None = None
    error_code: Literal["INTERVIEW_MODEL_UNAVAILABLE", "INTERVIEW_MODEL_OUTPUT_INVALID", "MODEL_ENDPOINT_REJECTED", "INTERVIEW_SESSION_GONE", "INTERVIEW_CALLBACK_STALE"] | None = Field(default=None, alias="errorCode")

    @model_validator(mode="after")
    def valid_outcome(self) -> "InterviewCallback":
        if self.outcome == "SUCCEEDED":
            if self.error_code is not None:
                raise ValueError("successful callback cannot include error code")
            if self.work_type == "QUESTION_GENERATION" and self.questions is not None and self.feedback is None:
                InterviewQuestionSet(questions=self.questions)
                return self
            if self.work_type == "ANSWER_ANALYSIS" and self.feedback is not None and self.questions is None:
                return self
        elif self.error_code is not None and self.questions is None and self.feedback is None:
            return self
        raise ValueError("callback result does not match work type or outcome")
