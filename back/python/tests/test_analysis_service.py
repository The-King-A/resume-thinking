import base64
import json
import subprocess
from pathlib import Path
from uuid import uuid4

import pytest
from pydantic import ValidationError

from app.analysis_service import analyze_job
from app.models import AnalysisJob, AnalysisResult
from app.analysis_service import _hash_payload
from app.openai_compatible import ModelOutputInvalid


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


class FailingClient(CapturingClient):
    async def complete_structured(self, request):
        type(self).request = request
        raise ModelOutputInvalid("provider output invalid")


def _job(text: str, start: int, end: int, location: str = "txt:0"):
    return {
        "taskId": "task001", "callbackId": "callback001", "attempt": 1, "resumeVersion": 0, "sourceType": "TXT",
        "jobFamily": "JAVA_BACKEND",
        "document": {"contentBase64": base64.b64encode(text.encode()).decode(), "originalFilename": "resume.txt"},
        "allowedEvidence": [{"evidenceId": "evidence001", "sourceLocation": location, "sourceStart": start, "sourceEnd": end}],
        "jobDescriptionText": "Build reliable software with clear communication.", "redactionRequired": True,
        "callbackUrl": "http://127.0.0.1:8080/callback", "callbackToken": "x" * 32,
        "provider": {"baseUrl": "http://127.0.0.1:8080", "model": "model", "apiKey": "secret"}, "correlationId": str(uuid4()),
    }


@pytest.mark.asyncio
async def test_malformed_provider_or_document_returns_failure():
    job = {
        "taskId": "task001", "callbackId": "callback001", "attempt": 1, "resumeVersion": 0, "sourceType": "TXT",
        "jobFamily": "JAVA_BACKEND",
        "document": {"contentBase64": base64.b64encode(b"resume text").decode(), "originalFilename": "resume.txt"},
        "allowedEvidence": [{"evidenceId": "evidence001", "sourceLocation": "txt:0", "sourceStart": 0, "sourceEnd": 11}],
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
async def test_analysis_request_preserves_the_java_backend_job_family(monkeypatch):
    monkeypatch.setattr("app.analysis_service.OpenAICompatibleClient", CapturingClient)
    job = _job("Java\nEngineer", 0, 4)
    job["jobFamily"] = "JAVA_BACKEND"

    callback = await analyze_job(job)

    assert callback["outcome"] == "SUCCEEDED"
    assert CapturingClient.request.model_dump(by_alias=True)["jobFamily"] == "JAVA_BACKEND"


@pytest.mark.asyncio
async def test_failed_callback_does_not_echo_labeled_names(monkeypatch):
    monkeypatch.setattr("app.analysis_service.OpenAICompatibleClient", FailingClient)
    text = "\u59d3\u540d\uff1a\u5f20\u4e09\nName: Alice Zhang"

    callback = await analyze_job(_job(text, 0, len(text)))

    assert callback["outcome"] == "FAILED"
    assert callback["errorCode"] == "MODEL_OUTPUT_INVALID"
    assert "\u5f20\u4e09" not in FailingClient.request.resume_text
    assert "Alice Zhang" not in FailingClient.request.resume_text
    assert "\u5f20\u4e09" not in json.dumps(callback, ensure_ascii=False)
    assert "Alice Zhang" not in json.dumps(callback, ensure_ascii=False)


@pytest.mark.asyncio
async def test_success_callback_redacts_provider_output_before_boundary(monkeypatch):
    requirement_id = "requirement001"

    class LeakyClient(CapturingClient):
        async def complete_structured(self, request):
            return AnalysisResult.model_validate({
                "score": {"skills": 0, "projectExperience": 0, "workContent": 0, "educationExperience": 0, "softSkills": 0, "composite": 0},
                "requirements": [{
                    "requirementId": str(requirement_id),
                    "jobRequirementText": "Name: Alice Zhang",
                    "requirementType": "MANDATORY",
                    "matchStatus": "UNMET",
                    "matchType": "NO_MATCH",
                    "component": "SKILLS",
                    "componentScore": 0,
                    "evidence": [],
                    "evidenceStrength": "NONE",
                    "gap": "Contact alice@example.com",
                    "suggestionState": "RISKY_OR_UNSUPPORTED",
                }],
                "suggestions": [{
                    "suggestionId": "suggestion001",
                    "requirementId": str(requirement_id),
                    "state": "NEEDS_USER_CONFIRMATION",
                    "proposedText": "\u59d3\u540d\uff1a\u5f20\u4e09",
                    "evidenceIds": [],
                }],
            })

    monkeypatch.setattr("app.analysis_service.OpenAICompatibleClient", LeakyClient)
    callback = await analyze_job(_job("alpha", 0, 5))

    serialized = json.dumps(callback, ensure_ascii=False)
    assert callback["outcome"] == "SUCCEEDED"
    assert "Alice Zhang" not in serialized
    assert "alice@example.com" not in serialized
    assert "\u5f20\u4e09" not in serialized
    assert "[REDACTED_NAME]" in serialized
    assert "[REDACTED_EMAIL]" in serialized


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


@pytest.mark.asyncio
async def test_callback_hash_covers_exact_schema_payload(monkeypatch):
    monkeypatch.setattr("app.analysis_service.OpenAICompatibleClient", CapturingClient)
    callback = await analyze_job(_job("alpha", 0, 5))
    assert "errorCode" not in callback
    assert "result" in callback
    assert callback["payloadHash"] == _hash_payload(callback)


@pytest.mark.asyncio
async def test_success_callback_keeps_required_nested_null_and_passes_ajv(monkeypatch):
    requirement_id = "requirement001"

    class RequirementClient(CapturingClient):
        async def complete_structured(self, request):
            return AnalysisResult.model_validate({
                "score": {"skills": 0, "projectExperience": 0, "workContent": 0, "educationExperience": 0, "softSkills": 0, "composite": 0},
                "requirements": [{
                    "requirementId": str(requirement_id), "jobRequirementText": "Java", "requirementType": "MANDATORY",
                    "matchStatus": "UNMET", "matchType": "NO_MATCH", "component": "SKILLS", "componentScore": 0,
                    "evidence": [], "evidenceStrength": "NONE", "gap": None, "suggestionState": "RISKY_OR_UNSUPPORTED",
                }], "suggestions": [],
            })

    monkeypatch.setattr("app.analysis_service.OpenAICompatibleClient", RequirementClient)
    callback = await analyze_job(_job("alpha", 0, 5))
    assert callback["result"]["requirements"][0]["gap"] is None
    assert callback["payloadHash"] == _hash_payload(callback)

    schema_path = Path(__file__).resolve().parents[3] / "contracts/internal/v2/analysis-callback.schema.json"
    script = """
const fs = require('fs');
const Ajv = require('ajv/dist/2020');
const addFormats = require('ajv-formats');
const schema = JSON.parse(fs.readFileSync(process.argv[1], 'utf8'));
const payload = JSON.parse(fs.readFileSync(0, 'utf8'));
const ajv = new Ajv({allErrors: true, strict: false});
addFormats(ajv);
if (!ajv.validate(schema, payload)) {
  console.error(JSON.stringify(ajv.errors));
  process.exit(1);
}
"""
    result = subprocess.run(["node", "-e", script, str(schema_path)], input=json.dumps(callback), text=True, capture_output=True, cwd=schema_path.parents[2])
    assert result.returncode == 0, result.stderr or result.stdout


@pytest.mark.asyncio
async def test_model_evidence_must_have_non_empty_range_and_rebuilt_excerpt(monkeypatch):
    class InvalidEvidenceClient(CapturingClient):
        async def complete_structured(self, request):
            return AnalysisResult.model_validate({
                "score": {"skills": 0, "projectExperience": 0, "workContent": 0, "educationExperience": 0, "softSkills": 0, "composite": 0},
                "requirements": [{
                    "requirementId": "requirement001", "jobRequirementText": "x", "requirementType": "MANDATORY",
                    "matchStatus": "SATISFIED", "matchType": "EXACT", "component": "SKILLS", "componentScore": 0,
                    "evidence": [{"evidenceId": request.evidence[0].evidence_id, "sourceStart": 0, "sourceEnd": 0, "excerpt": "forged", "confidence": 1}],
                    "evidenceStrength": "HIGH", "gap": None, "suggestionState": "NEEDS_USER_CONFIRMATION",
                }], "suggestions": [],
            })
    monkeypatch.setattr("app.analysis_service.OpenAICompatibleClient", InvalidEvidenceClient)
    callback = await analyze_job(_job("alpha", 0, 5))
    assert callback["outcome"] == "FAILED"
    assert callback["errorCode"] == "MODEL_OUTPUT_INVALID"


def test_callback_url_rejects_out_of_range_port():
    with pytest.raises(ValidationError):
        AnalysisJob.model_validate({**_job("alpha", 0, 5), "callbackUrl": "http://127.0.0.1:99999/callback"})
