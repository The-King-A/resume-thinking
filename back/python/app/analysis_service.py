from __future__ import annotations

import base64
import hashlib
import json
import math
from uuid import uuid4

from .extraction import UnsupportedFile, extract_resume
from .models import AnalysisJob, AnalysisRequest, Callback
from .openai_compatible import OpenAICompatibleClient, ModelEndpointRejected, ModelOutputInvalid, ModelUnavailable
from .redaction import redact_text
from .matching import composite_score


def _hash_payload(payload: dict) -> str:
    body = {k: v for k, v in payload.items() if k != "payloadHash"}
    return hashlib.sha256(json.dumps(body, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode("utf-8")).hexdigest()


async def analyze_job(job: AnalysisJob | dict) -> dict:
    job = job if isinstance(job, AnalysisJob) else AnalysisJob.model_validate(job)
    callback_base = {"taskId": str(job.task_id), "attempt": job.attempt, "callbackId": str(uuid4()), "callbackToken": job.callback_token, "correlationId": str(job.correlation_id)}
    try:
        content = base64.b64decode(job.document.content_base64, validate=True)
        extracted = extract_resume(job.source_type, content)
        redacted = redact_text(extracted.text)
        redacted_job = redact_text(job.job_description_text)
        evidence_payload = []
        for allowed in job.allowed_evidence:
            matching = [e for e in extracted.evidence if allowed.source_start >= e["sourceStart"] and allowed.source_end <= e["sourceEnd"] and allowed.source_end >= allowed.source_start]
            if not matching:
                raise ModelOutputInvalid("evidence range invalid")
            source = matching[0]
            relative_start = allowed.source_start - source["sourceStart"]
            relative_end = allowed.source_end - source["sourceStart"]
            safe_excerpt = redact_text(source["excerpt"][relative_start:relative_end]).redacted_text
            evidence_payload.append({"evidenceId": str(allowed.evidence_id), "sourceLocation": allowed.source_location, "sourceStart": allowed.source_start, "sourceEnd": allowed.source_end, "excerpt": safe_excerpt})
        request = AnalysisRequest(resumeText=redacted.redacted_text, jobDescriptionText=redacted_job.redacted_text, evidence=evidence_payload)
        result = await OpenAICompatibleClient(job.provider).complete_structured(request)
        allowed_ids = {e.evidence_id for e in job.allowed_evidence}
        requirement_ids = {req.requirement_id for req in result.requirements}
        for req in result.requirements:
            for evidence in req.evidence:
                if evidence.evidence_id not in allowed_ids:
                    raise ModelOutputInvalid("model output invalid")
        for suggestion in result.suggestions:
            if suggestion.requirement_id not in requirement_ids or any(eid not in allowed_ids for eid in suggestion.evidence_ids):
                raise ModelOutputInvalid("model output invalid")
        expected = composite_score(skills=result.score.skills, projects=result.score.project_experience, work_content=result.score.work_content, education_experience=result.score.education_experience, soft_skills=result.score.soft_skills)
        if not math.isclose(result.score.composite, expected, rel_tol=0, abs_tol=1e-4):
            raise ModelOutputInvalid("composite score invalid")
        callback = {**callback_base, "outcome": "SUCCEEDED", "result": result.model_dump(by_alias=True, mode="json")}
    except UnsupportedFile:
        callback = {**callback_base, "outcome": "FAILED", "errorCode": "UNSUPPORTED_FILE"}
    except ModelUnavailable:
        callback = {**callback_base, "outcome": "TIMED_OUT", "errorCode": "MODEL_UNAVAILABLE"}
    except ModelEndpointRejected:
        callback = {**callback_base, "outcome": "FAILED", "errorCode": "MODEL_ENDPOINT_REJECTED"}
    except (ModelOutputInvalid, ValueError, AttributeError, json.JSONDecodeError):
        callback = {**callback_base, "outcome": "FAILED", "errorCode": "MODEL_OUTPUT_INVALID"}
    callback["payloadHash"] = _hash_payload(callback)
    return Callback.model_validate(callback).model_dump(by_alias=True, mode="json")
