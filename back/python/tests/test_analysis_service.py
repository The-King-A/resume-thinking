import base64
from uuid import uuid4

import pytest
from pydantic import ValidationError

from app.analysis_service import analyze_job
from app.models import AnalysisJob, AnalysisResult


class CapturingClient:
    request = None

    def __init__(self, *_args, **_kwargs):
        pass

    async def complete_structured(self, request):
        type(self).request = request
        return AnalysisResult.model_validate({
            "score": {"skills": 0, "projectExperience": 0, "workContent": 0, "educationExperience": 0, "softSkills": 0, "composite": 0},
            "requirements": [],
            "suggestions": [],
        })


class WrongCompositeClient(CapturingClient):
    async def complete_structured(self, request):
        type(self).request = request
        return AnalysisResult.model_validate({
            "score": {"skills": 0, "projectExperience": 0, "workContent": 0, "educationExperience": 0, "softSkills": 0, "composite": 0.00009},
            "requirements": [],
            "suggestions": [],
        })


def _job(text: str, start: int, end: int, location: str = "txt:0"):
    return {
        "taskId": str(uuid4()), "attempt": 1, "resumeVersion": 0, "sourceType": "TXT",
        "document": {"contentBase64": base64.b64encode(text.encode()).decode(), "originalFilename": "resume.txt"},
        "allowedEvidence": [{"evidenceId": str(uuid4()), "sourceLocation": location, "sourceStart": start, "sourceEnd": end}],
        "jobDescriptionText": "Build reliable software with clear communication.", "redactionRequired": True,
        "callbackUrl": "http://127.0.0.1:8080/callback", "callbackToken": "x" * 32,
        "provider": {"baseUrl": "http://127.0.0.1:8080", "model": "model", "apiKey": "secret"}, "correlationId": str(uuid4()),
    }


@pytest.mark.asyncio
async def test_malformed_provider_or_document_returns_failure():
    job = {
        "taskId": str(uuid4()), "attempt": 1, "resumeVersion": 0, "sourceType": "TXT",
        "document": {"contentBase64": base64.b64encode(b"resume text").decode(), "originalFilename": "resume.txt"},
        "allowedEvidence": [{"evidenceId": str(uuid4()), "sourceLocation": "txt:0", "sourceStart": 0, "sourceEnd": 11}],
        "jobDescriptionText": "Build reliable software with clear communication.", "redactionRequired": True,
        "callbackUrl": "http://127.0.0.1:8080/callback", "callbackToken": "x" * 32,
        "provider": {"baseUrl": "https://api.example.test/v1", "model": "model", "apiKey": "secret"}, "correlationId": str(uuid4()),
    }
    callback = await analyze_job(job)
    assert callback["outcome"] in {"FAILED", "TIMED_OUT"}
    assert callback.get("errorCode") in {"MODEL_OUTPUT_INVALID", "MODEL_UNAVAILABLE", "MODEL_ENDPOINT_REJECTED"}


@pytest.mark.asyncio
async def test_evidence_slice_redacts_pii_at_range_boundary(monkeypatch):
    monkeypatch.setattr("app.analysis_service.OpenAICompatibleClient", CapturingClient)
    callback = await analyze_job(_job("alice@example.com\nEngineer", 0, 7))
    assert callback["outcome"] == "SUCCEEDED"
    assert "alice@" not in CapturingClient.request.evidence[0].excerpt


@pytest.mark.asyncio
async def test_evidence_range_can_span_paragraphs(monkeypatch):
    monkeypatch.setattr("app.analysis_service.OpenAICompatibleClient", CapturingClient)
    callback = await analyze_job(_job("alpha\nbeta", 0, 10))
    assert callback["outcome"] == "SUCCEEDED"
    assert CapturingClient.request.evidence[0].excerpt == "alpha\nbeta"


@pytest.mark.asyncio
async def test_evidence_source_location_must_match(monkeypatch):
    monkeypatch.setattr("app.analysis_service.OpenAICompatibleClient", CapturingClient)
    callback = await analyze_job(_job("alpha\nbeta", 0, 5, "paragraph:9"))
    assert callback["outcome"] == "FAILED"
    assert callback["errorCode"] == "MODEL_OUTPUT_INVALID"


@pytest.mark.asyncio
async def test_composite_score_must_match_frozen_weights(monkeypatch):
    monkeypatch.setattr("app.analysis_service.OpenAICompatibleClient", WrongCompositeClient)
    callback = await analyze_job(_job("alpha", 0, 5))
    assert callback["outcome"] == "FAILED"
    assert callback["errorCode"] == "MODEL_OUTPUT_INVALID"


def test_callback_url_rejects_out_of_range_port():
    with pytest.raises(ValidationError):
        AnalysisJob.model_validate({**_job("alpha", 0, 5), "callbackUrl": "http://127.0.0.1:99999/callback"})
