from __future__ import annotations

import base64
import hashlib
from urllib.parse import urlsplit

import rfc8785

from .extraction import UnsupportedFile, extract_resume
from .models import AnalysisJob, AnalysisRequest, AnalysisResult, Callback, V3AnalysisJob, V3Callback
from .openai_compatible import (
    OpenAICompatibleClient,
    ModelEndpointRejected,
    ModelOutputInvalid,
    ModelUnavailable,
    _sanitize_model_value,
)
from .redaction import redact_text
from .matching import composite_score


_QWEN_FLASH_EVIDENCE_CORRECTION = """The previous candidate was rejected by local evidence validation. Return one new JSON object. For every evidence reference, copy an exact evidenceId from the supplied evidence and keep sourceStart/sourceEnd strictly inside that same evidence item's supplied range. Do not cite unknown evidence. Every suggestion must reference an existing requirementId and only supplied evidenceIds; if a suggestion cannot satisfy those rules, return no suggestion. Preserve the exact contract and return JSON only."""


def _is_qwen38_flash_provider(provider) -> bool:
    return (
        (urlsplit(provider.base_url).hostname or "").strip().lower().rstrip(".") in {"maas.qianwenaiapi.com", "dashscope.aliyuncs.com"}
        and provider.model.strip().lower().startswith("qwen3.8-flash")
    )


def _hash_payload(payload: dict) -> str:
    body = {k: v for k, v in payload.items() if k != "payloadHash"}
    return hashlib.sha256(rfc8785.dumps(body)).hexdigest()


def _finalize_callback(callback: dict, callback_base: dict, callback_model: type[Callback] = Callback) -> dict:
    """Hash and return the exact schema-shaped object that will be sent."""
    try:
        payload = callback_model.model_validate({**callback, "payloadHash": "0" * 64}).model_dump(by_alias=True, mode="json")
        if payload.get("outcome") == "SUCCEEDED":
            payload.pop("errorCode", None)
        else:
            payload.pop("result", None)
        payload["payloadHash"] = _hash_payload(payload)
        callback_model.model_validate(payload)
        return payload
    except (ValueError, TypeError, UnicodeError):
        payload = {
            **callback_base,
            "outcome": "FAILED",
            "errorCode": "MODEL_OUTPUT_INVALID",
            "payloadHash": "0" * 64,
        }
        payload = callback_model.model_validate(payload).model_dump(by_alias=True, mode="json")
        payload.pop("result", None)
        payload["payloadHash"] = _hash_payload(payload)
        return payload


def _redacted_range(text: str, redaction, start: int, end: int) -> str:
    chunks: list[str] = []
    cursor = start
    for replacement in redaction.replacements:
        if replacement.end <= start:
            continue
        if replacement.start >= end:
            break
        literal_end = min(replacement.start, end)
        if cursor < literal_end:
            chunks.append(text[cursor:literal_end])
        chunks.append(replacement.replacement)
        cursor = max(cursor, replacement.end)
        if cursor >= end:
            break
    if cursor < end:
        chunks.append(text[cursor:end])
    return "".join(chunks)


def _renumber_result_ids(result: AnalysisResult) -> AnalysisResult:
    """Assign deterministic task-local IDs while preserving associations.

    Provider output IDs are only labels.  Java persists the callback result,
    so Python replaces them with stable, collision-free IDs in result order.
    Duplicate requirement labels and suggestions pointing at an unknown
    requirement are rejected rather than silently associating facts with the
    wrong requirement.
    """
    payload = result.model_dump(by_alias=True, mode="json")
    requirement_map: dict[str, str] = {}
    for index, requirement in enumerate(result.requirements, start=1):
        old_id = requirement.requirement_id
        if old_id in requirement_map:
            raise ModelOutputInvalid("duplicate requirement id")
        requirement_map[old_id] = f"requirement{index:03d}"
        payload["requirements"][index - 1]["requirementId"] = requirement_map[old_id]

    for index, suggestion in enumerate(result.suggestions, start=1):
        mapped_requirement = requirement_map.get(suggestion.requirement_id)
        if mapped_requirement is None:
            raise ModelOutputInvalid("suggestion references unknown requirement")
        payload["suggestions"][index - 1]["suggestionId"] = f"suggestion{index:03d}"
        payload["suggestions"][index - 1]["requirementId"] = mapped_requirement

    return AnalysisResult.model_validate(payload)


def _canonicalize_evidence_excerpts(
    result: AnalysisResult,
    extracted_text: str,
    redacted_text,
    allowed_evidence,
) -> AnalysisResult:
    """Rebuild evidence text from a validated source range.

    Providers frequently add ellipses, normalize whitespace, or return the
    complete paragraph instead of the requested slice.  The range and
    evidence id remain model-selected inputs and are checked strictly; the
    excerpt itself is derived locally so the callback can never carry text
    that differs from the redacted resume source.
    """
    payload = result.model_dump(by_alias=True, mode="json")
    for requirement_index, requirement in enumerate(result.requirements):
        for evidence_index, evidence in enumerate(requirement.evidence):
            allowed = next(
                (item for item in allowed_evidence if item.evidence_id == evidence.evidence_id),
                None,
            )
            if (
                allowed is None
                or not (
                    allowed.source_start <= evidence.source_start < evidence.source_end <= allowed.source_end
                )
                or not (0 <= evidence.source_start < evidence.source_end <= len(extracted_text))
            ):
                raise ModelOutputInvalid("model output invalid")
            canonical_excerpt = _redacted_range(
                extracted_text,
                redacted_text,
                evidence.source_start,
                evidence.source_end,
            )
            if not canonical_excerpt:
                raise ModelOutputInvalid("model output invalid")
            payload["requirements"][requirement_index]["evidence"][evidence_index]["excerpt"] = canonical_excerpt
    return AnalysisResult.model_validate(payload)


async def analyze_job(job: AnalysisJob | V3AnalysisJob | dict) -> dict:
    job = job if isinstance(job, AnalysisJob) else (V3AnalysisJob if "revisionId" in job else AnalysisJob).model_validate(job)
    # callbackId is generated by Java and is stable across all transport
    # retries.  Python must never replace it with a locally generated value.
    callback_base = {"taskId": job.task_id, "attempt": job.attempt, "callbackId": job.callback_id, "callbackToken": job.callback_token, "correlationId": str(job.correlation_id)}
    callback_model = V3Callback if isinstance(job, V3AnalysisJob) else Callback
    if isinstance(job, V3AnalysisJob):
        callback_base["revisionId"] = job.revision_id
    try:
        content = base64.b64decode(job.document.content_base64, validate=True)
        extracted = extract_resume(job.source_type, content)
        redacted = redact_text(extracted.text)
        redacted_job = redact_text(job.job_description_text)
        evidence_payload = []
        for allowed in job.allowed_evidence:
            if not (0 <= allowed.source_start < allowed.source_end <= len(extracted.text)):
                raise ModelOutputInvalid("evidence range invalid")
            touched = [e for e in extracted.evidence if e["sourceStart"] < allowed.source_end and e["sourceEnd"] > allowed.source_start]
            if not touched or allowed.source_location != touched[0]["sourceLocation"]:
                raise ModelOutputInvalid("evidence range invalid")
            safe_excerpt = _redacted_range(extracted.text, redacted, allowed.source_start, allowed.source_end)
            evidence_payload.append({"evidenceId": str(allowed.evidence_id), "sourceLocation": allowed.source_location, "sourceStart": allowed.source_start, "sourceEnd": allowed.source_end, "excerpt": safe_excerpt})
        request = AnalysisRequest(jobFamily=job.job_family, resumeText=redacted.redacted_text, jobDescriptionText=redacted_job.redacted_text, evidence=evidence_payload)
        client = OpenAICompatibleClient(job.provider, blocked_secrets=(job.callback_token,))

        async def complete_and_validate(correction_instruction: str | None = None) -> AnalysisResult:
            if correction_instruction is None:
                result = await client.complete_structured(request)
            else:
                result = await client.complete_structured(request, correction_instruction)
            # Keep the callback boundary safe even when the client implementation
            # is replaced or a test double returns an unredacted model object.
            result = AnalysisResult.model_validate(
                _sanitize_model_value(
                    result.model_dump(by_alias=True, mode="json"),
                    (job.provider.api_key, job.callback_token),
                )
            )
            result = _canonicalize_evidence_excerpts(
                result,
                extracted.text,
                redacted,
                job.allowed_evidence,
            )
            allowed_ids = {e.evidence_id for e in job.allowed_evidence}
            requirement_ids = {req.requirement_id for req in result.requirements}
            for suggestion in result.suggestions:
                if suggestion.requirement_id not in requirement_ids or any(eid not in allowed_ids for eid in suggestion.evidence_ids):
                    raise ModelOutputInvalid("model output invalid")
            expected = composite_score(skills=result.score.skills, projects=result.score.project_experience, work_content=result.score.work_content, education_experience=result.score.education_experience, soft_skills=result.score.soft_skills)
            # Composite is a deterministic projection of the component scores.
            result = result.model_copy(
                update={"score": result.score.model_copy(update={"composite": expected})}
            )
            return _renumber_result_ids(result)

        try:
            result = await complete_and_validate()
        except ModelOutputInvalid:
            if not _is_qwen38_flash_provider(job.provider):
                raise
            result = await complete_and_validate(_QWEN_FLASH_EVIDENCE_CORRECTION)
        callback = {**callback_base, "outcome": "SUCCEEDED", "result": result.model_dump(by_alias=True, mode="json")}
    except UnsupportedFile:
        callback = {**callback_base, "outcome": "FAILED", "errorCode": "UNSUPPORTED_FILE"}
    except ModelUnavailable:
        callback = {**callback_base, "outcome": "TIMED_OUT", "errorCode": "MODEL_UNAVAILABLE"}
    except ModelEndpointRejected:
        callback = {**callback_base, "outcome": "FAILED", "errorCode": "MODEL_ENDPOINT_REJECTED"}
    except (ModelOutputInvalid, ValueError, AttributeError):
        callback = {**callback_base, "outcome": "FAILED", "errorCode": "MODEL_OUTPUT_INVALID"}
    return _finalize_callback(callback, callback_base, callback_model)
