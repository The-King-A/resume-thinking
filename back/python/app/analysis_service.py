from __future__ import annotations

import base64
import hashlib
import json
from uuid import uuid4

from .extraction import UnsupportedFile, extract_resume
from .models import AnalysisJob, AnalysisRequest, Callback
from .openai_compatible import OpenAICompatibleClient, ModelEndpointRejected, ModelOutputInvalid, ModelUnavailable
from .redaction import redact_text


def _hash_payload(payload: dict) -> str:
    body = {k: v for k, v in payload.items() if k != "payloadHash"}
    return hashlib.sha256(json.dumps(body, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


async def analyze_job(job: AnalysisJob | dict) -> dict:
    job = job if isinstance(job, AnalysisJob) else AnalysisJob.model_validate(job)
    callback_base = {"taskId": str(job.task_id), "attempt": job.attempt, "callbackId": str(uuid4()), "callbackToken": job.callback_token, "correlationId": str(job.correlation_id)}
    try:
        content = base64.b64decode(job.document.content_base64, validate=True)
        extracted = extract_resume(job.source_type, content)
        redacted = redact_text(extracted.text)
        redacted_job = redact_text(job.job_description_text)
        request = AnalysisRequest(resumeText=redacted.redacted_text, jobDescriptionText=redacted_job.redacted_text, evidence=[{**e, "evidenceId": str(job.allowed_evidence[i].evidence_id)} for i, e in enumerate(extracted.evidence[: len(job.allowed_evidence)])])
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
        callback = {**callback_base, "outcome": "SUCCEEDED", "result": result.model_dump(by_alias=True, mode="json")}
    except UnsupportedFile:
        callback = {**callback_base, "outcome": "FAILED", "errorCode": "UNSUPPORTED_FILE"}
    except ModelUnavailable:
        callback = {**callback_base, "outcome": "TIMED_OUT", "errorCode": "MODEL_UNAVAILABLE"}
    except ModelEndpointRejected:
        callback = {**callback_base, "outcome": "FAILED", "errorCode": "MODEL_ENDPOINT_REJECTED"}
    except (ModelOutputInvalid, ValueError, json.JSONDecodeError):
        callback = {**callback_base, "outcome": "FAILED", "errorCode": "MODEL_OUTPUT_INVALID"}
    callback["payloadHash"] = _hash_payload(callback)
    return Callback.model_validate(callback).model_dump(by_alias=True, mode="json")
