from __future__ import annotations

import hashlib
from typing import Any

import rfc8785

from .interview_models import InterviewCallback, InterviewFeedbackPayload, InterviewJob, InterviewQuestionSet
from .openai_compatible import ModelEndpointRejected, ModelOutputInvalid, ModelUnavailable, OpenAICompatibleClient
from .redaction import redact_text

QUESTION_INSTRUCTION = """Return exactly one JSON object with this exact shape and no extra fields:
{"questions":[{"questionId":"question001","sequence":1,"questionType":"BASIC_CONFIRMATION","difficulty":"BASIC","questionText":"...","requirementId":"requirement001","requirementText":"...","evidenceIds":["evidence001"],"generationReason":"...","confidence":0.8}]}

Return exactly four questions with sequence values 1, 2, 3, and 4, and exactly one of each questionType: BASIC_CONFIRMATION, PROJECT_DEEP_DIVE, JOB_SCENARIO, and SYNTHESIS_FOLLOW_UP. Use only the supplied questionGeneration.requirements. Each requirementId must exactly match a supplied requirementId, and you must copy requirementText exactly from that requirement. Each evidenceIds array must contain only evidence IDs attached to the selected requirement; do not cite evidence from another requirement and do not invent IDs. Every question must be directly about the selected job requirement and its evidence or explicitly stated gap. generationReason must explain why this question follows from that requirement. Use BASIC for confirmation, INTERMEDIATE for project deep dive, and ADVANCED for scenario and synthesis questions. Confidence must be a JSON number between 0 and 1. Do not invent work experience, metrics, titles, employers, responsibilities, or outcomes. Return JSON only, without markdown or commentary."""
FEEDBACK_INSTRUCTION = """Return exactly one JSON object with this exact shape and no extra fields:
{"feedbackId":"feedback001","answerId":"answer001","state":"FEEDBACK_READY","relevance":"HIGH","completeness":"MEDIUM","technicalAccuracy":"HIGH","factualConsistency":"HIGH","clarity":"MEDIUM","evidenceIds":["evidence001"],"riskFlags":[{"code":"UNVERIFIED_CLAIM","message":"需要用户确认","level":"MEDIUM","claimState":"NEEDS_USER_CONFIRMATION"}],"claims":[{"id":"claim001","claimText":"...","state":"NEEDS_USER_CONFIRMATION","evidenceIds":[],"applied":false}],"improvementSuggestion":"...","suggestedAnswer":"...","answerComparison":"...","version":1}

Copy answerId exactly from answerAnalysis.answerId. Set feedbackId to feedback001, state to FEEDBACK_READY, and version to a non-negative JSON integer. Score relevance, completeness, technicalAccuracy, factualConsistency, and clarity only as HIGH, MEDIUM, LOW, or INSUFFICIENT_EVIDENCE. Cite only supplied evidence IDs in evidenceIds and claims.evidenceIds. Every risk flag must include code, message, level, and claimState. Every claim must include id, claimText, state, evidenceIds, and applied:false. Any new or unverifiable metric, responsibility, title, achievement, or outcome must use NEEDS_USER_CONFIRMATION or RISKY_OR_UNSUPPORTED, and applied must always be false. suggestedAnswer and answerComparison must both be non-empty. suggestedAnswer must be a concise, evidence-bound answer the user could give, without inventing facts. answerComparison must explicitly compare the supplied answer with the requirement and evidence, naming what was covered and what is missing. When evidence is insufficient, state that limitation plainly instead of omitting either field. Never claim that feedback changes or updates a resume. Return JSON only, without markdown or commentary."""


def _hash_payload(payload: dict[str, Any]) -> str:
    return hashlib.sha256(rfc8785.dumps({key: value for key, value in payload.items() if key != "payloadHash"})).hexdigest()


def _redact_value(value: Any) -> Any:
    if isinstance(value, str):
        return redact_text(value).redacted_text
    if isinstance(value, list):
        return [_redact_value(item) for item in value]
    if isinstance(value, dict):
        return {key: _redact_value(item) for key, item in value.items()}
    return value


def _base(job: InterviewJob) -> dict[str, Any]:
    return {
        "contractVersion": "4.0",
        "workType": job.work_type,
        "sessionId": job.session_id,
        "revisionId": job.revision_id,
        "matchTaskId": job.match_task_id,
        "sessionVersion": job.session_version,
        "attempt": job.attempt,
        "callbackId": job.callback_id,
        "callbackToken": job.callback_token,
        "correlationId": str(job.correlation_id),
    }


def _finalize(payload: dict[str, Any]) -> dict[str, Any]:
    shaped = {**payload, "payloadHash": "0" * 64}
    validated = InterviewCallback.model_validate(shaped).model_dump(by_alias=True, mode="json", exclude_none=True)
    validated["payloadHash"] = _hash_payload(validated)
    return InterviewCallback.model_validate(validated).model_dump(by_alias=True, mode="json", exclude_none=True)


def _validate_question_evidence(result: InterviewQuestionSet, job: InterviewJob) -> None:
    requirements = {
        item.requirement_id: item
        for item in job.question_generation.requirements
    }
    for question in result.questions:
        requirement = requirements.get(question.requirement_id)
        if requirement is None or question.requirement_text != requirement.requirement_text:
            raise ModelOutputInvalid("question requirement is not bound to the job context")
        allowed = {item.evidence_id for item in requirement.evidence}
        if any(evidence_id not in allowed for evidence_id in question.evidence_ids):
            raise ModelOutputInvalid("question evidence is not bound to the selected requirement")


async def analyze_interview_job(job: InterviewJob | dict[str, Any]) -> dict[str, Any]:
    parsed = job if isinstance(job, InterviewJob) else InterviewJob.model_validate(job)
    base = _base(parsed)
    try:
        if parsed.work_type == "QUESTION_GENERATION":
            request = _redact_value(parsed.question_generation.model_dump(by_alias=True, mode="json"))
            result = await OpenAICompatibleClient(parsed.provider, blocked_secrets=(parsed.callback_token,)).complete_interview_structured(
                QUESTION_INSTRUCTION, request, InterviewQuestionSet)
            _validate_question_evidence(result, parsed)
            payload = {**base, "outcome": "SUCCEEDED", "questions": result.model_dump(by_alias=True, mode="json")["questions"]}
        else:
            request = _redact_value(parsed.answer_analysis.model_dump(by_alias=True, mode="json"))
            result = await OpenAICompatibleClient(parsed.provider, blocked_secrets=(parsed.callback_token,)).complete_interview_structured(
                FEEDBACK_INSTRUCTION, request, InterviewFeedbackPayload)
            payload = {**base, "outcome": "SUCCEEDED", "feedback": result.model_dump(by_alias=True, mode="json")}
    except ModelUnavailable:
        payload = {**base, "outcome": "TIMED_OUT", "errorCode": "INTERVIEW_MODEL_UNAVAILABLE"}
    except ModelEndpointRejected:
        payload = {**base, "outcome": "FAILED", "errorCode": "MODEL_ENDPOINT_REJECTED"}
    except (ModelOutputInvalid, ValueError, AttributeError):
        payload = {**base, "outcome": "FAILED", "errorCode": "INTERVIEW_MODEL_OUTPUT_INVALID"}
    except Exception:
        # Keep an unexpected provider/client failure from leaving Java in an
        # in-flight state forever. The public callback exposes only a stable
        # terminal code and never the provider exception text.
        payload = {**base, "outcome": "FAILED", "errorCode": "INTERVIEW_MODEL_OUTPUT_INVALID"}
    return _finalize(payload)
