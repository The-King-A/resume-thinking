from uuid import uuid4

import pytest
from pydantic import ValidationError

from app.interview_models import InterviewFeedbackPayload, InterviewJob, InterviewQuestionSet


def question_job() -> dict:
    return {
        "contractVersion": "4.0",
        "workType": "QUESTION_GENERATION",
        "sessionId": "session001",
        "revisionId": "revision001",
        "matchTaskId": "task001",
        "sessionVersion": 1,
        "attempt": 1,
        "callbackId": "callback001",
        "callbackUrl": "http://127.0.0.1:8080/internal/v4/interview-results",
        "callbackToken": "t" * 32,
        "redactionRequired": True,
        "provider": {"baseUrl": "http://127.0.0.1:9000", "model": "fixture", "apiKey": "secret"},
        "correlationId": str(uuid4()),
        "questionGeneration": {
            "requirements": [{
                "requirementId": "requirement001", "requirementText": "Java 服务开发",
                "requirementType": "MANDATORY", "matchStatus": "SATISFIED", "gap": None,
                "evidence": [{"evidenceId": "evidence001", "sourceLocation": "txt:0", "sourceStart": 0, "sourceEnd": 12, "excerpt": "Java services", "strength": "HIGH"}],
            }],
            "questionTypes": ["BASIC_CONFIRMATION", "PROJECT_DEEP_DIVE", "JOB_SCENARIO", "SYNTHESIS_FOLLOW_UP"],
            "maxQuestions": 4,
        },
    }


def test_v4_question_job_uses_aliases_and_rejects_authority_fields():
    parsed = InterviewJob.model_validate(question_job())

    assert parsed.work_type == "QUESTION_GENERATION"
    assert parsed.session_id == "session001"
    with pytest.raises(ValidationError):
        InterviewJob.model_validate({**question_job(), "ownerId": "user001"})


def test_question_set_requires_one_of_each_documented_type():
    valid = {
        "questions": [
            {"questionId": "question001", "sequence": 1, "questionType": "BASIC_CONFIRMATION", "difficulty": "BASIC", "questionText": "职责", "requirementId": "requirement001", "requirementText": "Java", "evidenceIds": ["evidence001"], "generationReason": "核对", "confidence": .9},
            {"questionId": "question002", "sequence": 2, "questionType": "PROJECT_DEEP_DIVE", "difficulty": "INTERMEDIATE", "questionText": "取舍", "requirementId": "requirement001", "requirementText": "Java", "evidenceIds": ["evidence001"], "generationReason": "追问", "confidence": .9},
            {"questionId": "question003", "sequence": 3, "questionType": "JOB_SCENARIO", "difficulty": "ADVANCED", "questionText": "场景", "requirementId": "requirement001", "requirementText": "Java", "evidenceIds": ["evidence001"], "generationReason": "场景", "confidence": .9},
            {"questionId": "question004", "sequence": 4, "questionType": "SYNTHESIS_FOLLOW_UP", "difficulty": "ADVANCED", "questionText": "综合", "requirementId": "requirement001", "requirementText": "Java", "evidenceIds": ["evidence001"], "generationReason": "综合", "confidence": .9},
        ]
    }
    assert len(InterviewQuestionSet.model_validate(valid).questions) == 4
    valid["questions"][3]["questionType"] = "JOB_SCENARIO"
    with pytest.raises(ValidationError):
        InterviewQuestionSet.model_validate(valid)


def test_feedback_payload_supplies_safe_display_fallbacks_when_model_omits_new_fields():
    feedback = InterviewFeedbackPayload.model_validate({
        "feedbackId": "feedback001", "answerId": "answer001", "state": "FEEDBACK_READY",
        "relevance": "HIGH", "completeness": "MEDIUM", "technicalAccuracy": "HIGH",
        "factualConsistency": "HIGH", "clarity": "MEDIUM", "evidenceIds": ["evidence001"],
        "riskFlags": [], "claims": [], "improvementSuggestion": "补充真实的技术取舍。", "version": 1,
    })

    assert feedback.suggested_answer == "补充真实的技术取舍。"
    assert "相关性" in feedback.answer_comparison
    assert "补充真实的技术取舍。" in feedback.answer_comparison
    serialized = feedback.model_dump(by_alias=True, mode="json")
    assert serialized["suggestedAnswer"] == "补充真实的技术取舍。"
    assert serialized["answerComparison"] == feedback.answer_comparison
