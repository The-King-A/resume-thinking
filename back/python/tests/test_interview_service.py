import json

import pytest

from app.interview_service import analyze_interview_job
from app.interview_models import InterviewQuestionSet
from test_interview_contracts import question_job


class QuestionClient:
    instruction = None
    payload = None

    def __init__(self, *_args, **_kwargs):
        pass

    async def complete_interview_structured(self, _instruction, _payload, _model):
        type(self).instruction = _instruction
        type(self).payload = _payload
        return InterviewQuestionSet.model_validate({
            "questions": [
                {"questionId": "question001", "sequence": 1, "questionType": "BASIC_CONFIRMATION", "difficulty": "BASIC", "questionText": "职责", "requirementId": "requirement001", "requirementText": "Java 服务开发", "evidenceIds": ["evidence001"], "generationReason": "核对", "confidence": .9},
                {"questionId": "question002", "sequence": 2, "questionType": "PROJECT_DEEP_DIVE", "difficulty": "INTERMEDIATE", "questionText": "取舍", "requirementId": "requirement001", "requirementText": "Java 服务开发", "evidenceIds": ["evidence001"], "generationReason": "追问", "confidence": .9},
                {"questionId": "question003", "sequence": 3, "questionType": "JOB_SCENARIO", "difficulty": "ADVANCED", "questionText": "场景", "requirementId": "requirement001", "requirementText": "Java 服务开发", "evidenceIds": ["evidence001"], "generationReason": "场景", "confidence": .9},
                {"questionId": "question004", "sequence": 4, "questionType": "SYNTHESIS_FOLLOW_UP", "difficulty": "ADVANCED", "questionText": "综合", "requirementId": "requirement001", "requirementText": "Java 服务开发", "evidenceIds": ["evidence001"], "generationReason": "综合", "confidence": .9},
            ]
        })


@pytest.mark.asyncio
async def test_question_generation_returns_four_types_and_hashes_the_complete_callback(monkeypatch):
    monkeypatch.setattr("app.interview_service.OpenAICompatibleClient", QuestionClient)

    callback = await analyze_interview_job(question_job())

    assert callback["outcome"] == "SUCCEEDED"
    assert {item["questionType"] for item in callback["questions"]} == {
        "BASIC_CONFIRMATION", "PROJECT_DEEP_DIVE", "JOB_SCENARIO", "SYNTHESIS_FOLLOW_UP"
    }
    assert len(callback["payloadHash"]) == 64
    assert "secret" not in json.dumps(callback, ensure_ascii=False)
    assert '"questionId"' in QuestionClient.instruction
    assert '"generationReason"' in QuestionClient.instruction
    assert "must copy requirementText" in QuestionClient.instruction
    assert QuestionClient.payload["requirements"][0]["evidence"][0]["evidenceId"] == "evidence001"


@pytest.mark.asyncio
async def test_unexpected_model_failure_returns_a_terminal_callback(monkeypatch):
    class BrokenClient:
        def __init__(self, *_args, **_kwargs):
            pass

        async def complete_interview_structured(self, *_args, **_kwargs):
            raise RuntimeError("provider implementation failure")

    monkeypatch.setattr("app.interview_service.OpenAICompatibleClient", BrokenClient)

    callback = await analyze_interview_job(question_job())

    assert callback["outcome"] == "FAILED"
    assert callback["errorCode"] == "INTERVIEW_MODEL_OUTPUT_INVALID"
    assert len(callback["payloadHash"]) == 64


@pytest.mark.asyncio
async def test_question_evidence_must_belong_to_the_selected_requirement(monkeypatch):
    class WrongEvidenceClient(QuestionClient):
        async def complete_interview_structured(self, *_args, **_kwargs):
            return InterviewQuestionSet.model_validate({
                "questions": [
                    {"questionId": f"question00{index}", "sequence": index, "questionType": question_type,
                     "difficulty": "BASIC" if index == 1 else "INTERMEDIATE", "questionText": "问题",
                     "requirementId": "requirement001", "requirementText": "Java 服务开发",
                     "evidenceIds": ["evidence002"], "generationReason": "核对", "confidence": .9}
                    for index, question_type in enumerate((
                        "BASIC_CONFIRMATION", "PROJECT_DEEP_DIVE", "JOB_SCENARIO", "SYNTHESIS_FOLLOW_UP"), 1)
                ]
            })

    job = question_job()
    job["questionGeneration"]["requirements"].append({
        "requirementId": "requirement002", "requirementText": "测试与质量保障",
        "requirementType": "PREFERRED", "matchStatus": "PARTIALLY_SATISFIED", "gap": "缺少测试细节",
        "evidence": [{"evidenceId": "evidence002", "sourceLocation": "txt:20", "sourceStart": 20,
                      "sourceEnd": 34, "excerpt": "clear tests", "strength": "MEDIUM"}],
    })
    monkeypatch.setattr("app.interview_service.OpenAICompatibleClient", WrongEvidenceClient)

    callback = await analyze_interview_job(job)

    assert callback["outcome"] == "FAILED"
    assert callback["errorCode"] == "INTERVIEW_MODEL_OUTPUT_INVALID"


@pytest.mark.asyncio
async def test_feedback_generation_prompt_requires_the_complete_feedback_contract(monkeypatch):
    class FeedbackClient:
        instruction = None

        def __init__(self, *_args, **_kwargs):
            pass

        async def complete_interview_structured(self, instruction, _payload, _model):
            type(self).instruction = instruction
            from app.interview_models import InterviewFeedbackPayload
            return InterviewFeedbackPayload.model_validate({
                "feedbackId": "feedback001", "answerId": "answer001", "state": "FEEDBACK_READY",
                "relevance": "HIGH", "completeness": "MEDIUM", "technicalAccuracy": "HIGH",
                "factualConsistency": "HIGH", "clarity": "MEDIUM", "evidenceIds": ["evidence001"],
                "riskFlags": [], "claims": [{"id": "claim001", "claimText": "补充职责细节",
                    "state": "NEEDS_USER_CONFIRMATION", "evidenceIds": [], "applied": False}],
                "improvementSuggestion": "补充一个真实的技术取舍。",
                "suggestedAnswer": "我会从职责、技术取舍和结果三个方面说明。",
                "answerComparison": "当前回答已覆盖职责，但缺少具体取舍和结果证据。",
                "version": 1,
            })

    job = question_job()
    job["workType"] = "ANSWER_ANALYSIS"
    job.pop("questionGeneration")
    job["answerAnalysis"] = {
        "answerId": "answer001", "answerText": "我负责 Java 服务开发。",
        "question": {"questionId": "question001", "questionType": "BASIC_CONFIRMATION", "questionText": "说明职责。", "requirementId": "requirement001", "requirementText": "Java 服务开发", "evidenceIds": ["evidence001"]},
        "requirement": {"requirementId": "requirement001", "requirementText": "Java 服务开发", "requirementType": "MANDATORY", "matchStatus": "SATISFIED", "gap": None, "evidence": [{"evidenceId": "evidence001", "sourceLocation": "txt:0", "sourceStart": 0, "sourceEnd": 12, "excerpt": "Java services", "strength": "HIGH"}]},
        "evidence": [{"evidenceId": "evidence001", "sourceLocation": "txt:0", "sourceStart": 0, "sourceEnd": 12, "excerpt": "Java services", "strength": "HIGH"}],
    }
    monkeypatch.setattr("app.interview_service.OpenAICompatibleClient", FeedbackClient)

    callback = await analyze_interview_job(job)

    assert callback["outcome"] == "SUCCEEDED"
    assert '"feedbackId"' in FeedbackClient.instruction
    assert '"technicalAccuracy"' in FeedbackClient.instruction
    assert "applied must always be false" in FeedbackClient.instruction
    assert '"suggestedAnswer"' in FeedbackClient.instruction
    assert '"answerComparison"' in FeedbackClient.instruction
