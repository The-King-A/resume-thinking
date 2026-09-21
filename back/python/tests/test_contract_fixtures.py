import json
import base64
from pathlib import Path
from uuid import uuid4

import pytest
from pydantic import ValidationError

from app.analysis_service import _finalize_callback, analyze_job
from app.models import AnalysisResult, Callback, V3AnalysisJob, V3Callback
from app.openai_compatible import ModelOutputInvalid, ModelUnavailable


def test_frozen_callback_fixture_validates():
    path = Path(__file__).resolve().parents[3] / "contracts" / "fixtures" / "v2" / "callback-valid.json"
    assert Callback.model_validate(json.loads(path.read_text(encoding="utf-8"))).outcome == "FAILED"


def _v3_job_fixture() -> dict:
    return {
        "taskId": "task001",
        "revisionId": "revision001",
        "callbackId": "callback001",
        "attempt": 1,
        "resumeVersion": 0,
        "sourceType": "TXT",
        "jobFamily": "JAVA_BACKEND",
        "document": {
            "contentBase64": base64.b64encode(b"resume text").decode("ascii"),
            "originalFilename": "resume.txt",
        },
        "allowedEvidence": [{
            "evidenceId": "evidence001",
            "sourceLocation": "txt:0",
            "sourceStart": 0,
            "sourceEnd": 11,
        }],
        "jobDescriptionText": "Build reliable software with clear communication.",
        "redactionRequired": True,
        "callbackUrl": "http://127.0.0.1:8080/callback",
        "callbackToken": "c" * 32,
        "provider": {
            "baseUrl": "http://127.0.0.1:9000",
            "model": "model",
            "apiKey": "provider-secret",
        },
        "correlationId": str(uuid4()),
    }


def test_v3_job_requires_a_valid_java_issued_revision_id():
    missing_revision = _v3_job_fixture()
    missing_revision.pop("revisionId")

    with pytest.raises(ValidationError):
        V3AnalysisJob.model_validate(missing_revision)
    with pytest.raises(ValidationError):
        V3AnalysisJob.model_validate({**_v3_job_fixture(), "revisionId": "revision-id"})


def test_v3_job_rejects_python_field_names_at_every_contract_boundary():
    snake_case_root = _v3_job_fixture()
    snake_case_root["revision_id"] = snake_case_root.pop("revisionId")
    snake_case_nested = _v3_job_fixture()
    snake_case_nested["document"]["content_base64"] = snake_case_nested["document"].pop("contentBase64")

    with pytest.raises(ValidationError):
        V3AnalysisJob.model_validate(snake_case_root)
    with pytest.raises(ValidationError):
        V3AnalysisJob.model_validate(snake_case_nested)


def test_v3_callback_rejects_python_field_names():
    path = Path(__file__).resolve().parents[3] / "contracts" / "fixtures" / "v3" / "callback-valid.json"
    callback = json.loads(path.read_text(encoding="utf-8"))
    callback["revision_id"] = callback.pop("revisionId")

    with pytest.raises(ValidationError):
        V3Callback.model_validate(callback)


@pytest.mark.asyncio
async def test_v3_callback_echoes_the_input_revision_id(monkeypatch):
    class SuccessfulClient:
        def __init__(self, *_args, **_kwargs):
            pass

        async def complete_structured(self, _request):
            return AnalysisResult.model_validate({
                "score": {"skills": 0, "projectExperience": 0, "workContent": 0, "educationExperience": 0, "softSkills": 0, "composite": 0},
                "requirements": [],
                "suggestions": [],
            })

    monkeypatch.setattr("app.analysis_service.OpenAICompatibleClient", SuccessfulClient)

    callback = await analyze_job(V3AnalysisJob.model_validate(_v3_job_fixture()))

    assert callback["revisionId"] == "revision001"


@pytest.mark.asyncio
async def test_v3_failed_timed_out_and_fallback_callbacks_keep_the_input_revision_id(monkeypatch):
    class TimedOutClient:
        def __init__(self, *_args, **_kwargs):
            pass

        async def complete_structured(self, _request):
            raise ModelUnavailable()

    class FailedClient(TimedOutClient):
        async def complete_structured(self, _request):
            raise ModelOutputInvalid()

    monkeypatch.setattr("app.analysis_service.OpenAICompatibleClient", TimedOutClient)

    timed_out = await analyze_job(V3AnalysisJob.model_validate(_v3_job_fixture()))
    monkeypatch.setattr("app.analysis_service.OpenAICompatibleClient", FailedClient)
    failed = await analyze_job(V3AnalysisJob.model_validate(_v3_job_fixture()))
    fallback = _finalize_callback(
        {"outcome": "SUCCEEDED"},
        {
            "taskId": "task001",
            "revisionId": "revision001",
            "attempt": 1,
            "callbackId": "callback001",
            "callbackToken": "c" * 32,
            "correlationId": "00000000-0000-4000-8000-000000000001",
        },
        V3Callback,
    )

    assert timed_out["outcome"] == "TIMED_OUT"
    assert timed_out["revisionId"] == "revision001"
    assert failed["outcome"] == "FAILED"
    assert failed["revisionId"] == "revision001"
    assert fallback["outcome"] == "FAILED"
    assert fallback["revisionId"] == "revision001"
