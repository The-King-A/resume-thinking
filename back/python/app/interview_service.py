from __future__ import annotations

import hashlib
from typing import Any
from urllib.parse import urlsplit

import rfc8785

from .interview_models import InterviewCallback, InterviewFeedbackPayload, InterviewJob, InterviewQuestionSet
from .openai_compatible import ModelEndpointRejected, ModelOutputInvalid, ModelUnavailable, OpenAICompatibleClient
from .redaction import redact_text


_QWEN_FLASH_INTERVIEW_CORRECTION = """The previous interview JSON was rejected by local validation. Return a new JSON object. Copy each requirementId and requirementText exactly from the supplied requirement context. Every evidenceIds entry must be an evidence ID attached to that same requirement; if unsure, return an empty evidenceIds array. Preserve all four question types and sequences, and return JSON only."""


def _is_qwen38_flash_provider(provider) -> bool:
    return (
        (urlsplit(provider.base_url).hostname or "").strip().lower().rstrip(".") in {"maas.qianwenaiapi.com", "dashscope.aliyuncs.com"}
        and provider.model.strip().lower().startswith("qwen3.8-flash")
    )

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


def _bind_feedback_answer_id(result: InterviewFeedbackPayload, job: InterviewJob) -> InterviewFeedbackPayload:
    """Bind the callback to Java's authoritative submitted-answer identity.

    ``answerId`` is a correlation key, not model-generated content. Providers
    can copy the illustrative ``answer001`` from the contract example even
    when the request contains a different answer ID; Java correctly rejects
    that callback to prevent cross-answer writes. Replacing only this
    authoritative key keeps the validated feedback content intact.
    """
    expected = job.answer_analysis.answer_id
    if result.answer_id == expected:
        return result
    return result.model_copy(update={"answer_id": expected})


def _bind_feedback_evidence(result: InterviewFeedbackPayload, job: InterviewJob) -> InterviewFeedbackPayload:
    """Keep feedback citations inside the evidence supplied for this answer.

    The prompt contains a JSON example with ``evidence001``. Providers can
    copy that illustrative value even when the real job uses another stable
    evidence ID. Treat those values as untrusted model output: remove them
    from the callback and downgrade any claim that lost its only supporting
    evidence instead of letting Java reject the whole answer analysis.
    """
    if job.answer_analysis is None:
        raise ModelOutputInvalid("answer analysis payload is missing")
    allowed = {item.evidence_id for item in job.answer_analysis.evidence}

    def bound_ids(values: list[str]) -> list[str]:
        return list(dict.fromkeys(value for value in values if value in allowed))

    claims = []
    for claim in result.claims:
        evidence_ids = bound_ids(claim.evidence_ids)
        state = claim.state
        if state in {"SUPPORTED_FACT", "WORDING_ONLY_REWRITE"} and not evidence_ids:
            state = "NEEDS_USER_CONFIRMATION"
        claims.append(claim.model_copy(update={
            "state": state,
            "evidence_ids": evidence_ids,
            "applied": False,
        }))

    risk_flags = []
    for flag in result.risk_flags:
        claim_state = flag.claim_state
        if claim_state in {"SUPPORTED_FACT", "WORDING_ONLY_REWRITE"} and not allowed:
            claim_state = "NEEDS_USER_CONFIRMATION"
        risk_flags.append(flag.model_copy(update={"claim_state": claim_state}))

    return result.model_copy(update={
        "evidence_ids": bound_ids(result.evidence_ids),
        "claims": claims,
        "risk_flags": risk_flags,
    })


async def analyze_interview_job(job: InterviewJob | dict[str, Any]) -> dict[str, Any]:
    parsed = job if isinstance(job, InterviewJob) else InterviewJob.model_validate(job)
    base = _base(parsed)
    try:
        if parsed.work_type == "QUESTION_GENERATION":
            request = _redact_value(parsed.question_generation.model_dump(by_alias=True, mode="json"))
            client = OpenAICompatibleClient(parsed.provider, blocked_secrets=(parsed.callback_token,))
            async def complete_questions(correction_instruction: str | None = None):
                if correction_instruction is None:
                    return await client.complete_interview_structured(
                        QUESTION_INSTRUCTION, request, InterviewQuestionSet)
                return await client.complete_interview_structured(
                    QUESTION_INSTRUCTION, request, InterviewQuestionSet, correction_instruction)
            result = await complete_questions()
            try:
                _validate_question_evidence(result, parsed)
            except ModelOutputInvalid:
                if not _is_qwen38_flash_provider(parsed.provider):
                    raise
                result = await complete_questions(_QWEN_FLASH_INTERVIEW_CORRECTION)
                _validate_question_evidence(result, parsed)
            payload = {**base, "outcome": "SUCCEEDED", "questions": result.model_dump(by_alias=True, mode="json")["questions"]}
        else:
            request = _redact_value(parsed.answer_analysis.model_dump(by_alias=True, mode="json"))
            result = await OpenAICompatibleClient(parsed.provider, blocked_secrets=(parsed.callback_token,)).complete_interview_structured(
                FEEDBACK_INSTRUCTION, request, InterviewFeedbackPayload)
            result = _bind_feedback_answer_id(result, parsed)
            result = _bind_feedback_evidence(result, parsed)
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
