import base64
from uuid import UUID, uuid4

import pytest
from fastapi.testclient import TestClient
from pydantic import ValidationError

from app.analysis_service import analyze_job
from app.main import app
from app.models import AnalysisJob, Callback, AnalysisResult
from app.settings import settings


def _job() -> dict:
    return {
        "taskId": "task001",
        "callbackId": "callback001",
        "attempt": 1,
        "resumeVersion": 0,
        "sourceType": "TXT",
        "jobFamily": "JAVA_BACKEND",
        "document": {
            "contentBase64": base64.b64encode(b"Java backend engineer").decode("ascii"),
            "originalFilename": "resume.txt",
        },
        "allowedEvidence": [
            {
                "evidenceId": "evidence001",
                "sourceLocation": "txt:0",
                "sourceStart": 0,
                "sourceEnd": 21,
            }
        ],
        "jobDescriptionText": "Build reliable software with clear communication.",
        "redactionRequired": True,
        "callbackUrl": "http://127.0.0.1:8080/internal/v2/analysis-results",
        "callbackToken": "c" * 32,
        "provider": {
            "baseUrl": "http://127.0.0.1:9000",
            "model": "model",
            "apiKey": "provider-secret",
        },
        "correlationId": str(uuid4()),
    }


def test_v2_business_ids_are_readable_strings_and_correlation_stays_uuid():
    parsed = AnalysisJob.model_validate(_job())
    assert parsed.task_id == "task001"
    assert parsed.callback_id == "callback001"
    assert parsed.allowed_evidence[0].evidence_id == "evidence001"
    assert isinstance(parsed.correlation_id, UUID)


@pytest.mark.parametrize(
    ("field", "value"),
    [
        ("taskId", str(uuid4())),
        ("callbackId", "task001"),
        ("callbackId", "callback01"),
        ("allowedEvidence", [{"evidenceId": str(uuid4()), "sourceLocation": "txt:0", "sourceStart": 0, "sourceEnd": 1}]),
    ],
)
def test_v2_rejects_uuid_or_wrong_business_id_prefix(field, value):
    payload = _job()
    payload[field] = value
    with pytest.raises(ValidationError):
        AnalysisJob.model_validate(payload)


def test_callback_id_is_required_and_prefixed():
    payload = {
        "taskId": "task001",
        "attempt": 1,
        "callbackToken": "c" * 32,
        "payloadHash": "a" * 64,
        "outcome": "FAILED",
        "errorCode": "MODEL_OUTPUT_INVALID",
        "correlationId": str(uuid4()),
    }
    with pytest.raises(ValidationError):
        Callback.model_validate(payload)
    payload["callbackId"] = "callback001"
    assert Callback.model_validate(payload).callback_id == "callback001"


@pytest.mark.asyncio
async def test_analysis_uses_java_callback_id_and_numbers_result_items(monkeypatch):
    class FakeClient:
        def __init__(self, *_args, **_kwargs):
            pass

        async def complete_structured(self, _request):
            return AnalysisResult.model_validate(
                {
                    "score": {
                        "skills": 0,
                        "projectExperience": 0,
                        "workContent": 0,
                        "educationExperience": 0,
                        "softSkills": 0,
                        "composite": 0,
                    },
                    "requirements": [
                        {
                            "requirementId": "requirement999",
                            "jobRequirementText": "Java",
                            "requirementType": "MANDATORY",
                            "matchStatus": "UNMET",
                            "matchType": "NO_MATCH",
                            "component": "SKILLS",
                            "componentScore": 0,
                            "evidence": [],
                            "evidenceStrength": "NONE",
                            "gap": None,
                            "suggestionState": "RISKY_OR_UNSUPPORTED",
                        }
                    ],
                    "suggestions": [
                        {
                            "suggestionId": "suggestion999",
                            "requirementId": "requirement999",
                            "state": "NEEDS_USER_CONFIRMATION",
                            "proposedText": "Consider adding Java details",
                            "evidenceIds": [],
                        }
                    ],
                }
            )

    monkeypatch.setattr("app.analysis_service.OpenAICompatibleClient", FakeClient)
    callback = await analyze_job(_job())
    assert callback["callbackId"] == "callback001"
    assert callback["result"]["requirements"][0]["requirementId"] == "requirement001"
    assert callback["result"]["suggestions"][0]["suggestionId"] == "suggestion001"
    assert callback["result"]["suggestions"][0]["requirementId"] == "requirement001"


def test_only_v2_analysis_job_route_is_exposed(monkeypatch):
    monkeypatch.setattr(settings, "internal_service_token", "t" * 32)

    async def fake_analyze_job(job):
        return {
            "taskId": job.task_id,
            "attempt": job.attempt,
            "callbackId": job.callback_id,
            "callbackToken": job.callback_token,
            "payloadHash": "a" * 64,
            "outcome": "FAILED",
            "errorCode": "MODEL_OUTPUT_INVALID",
            "correlationId": str(job.correlation_id),
        }

    class FakeCallbackClient:
        async def post(self, _url, _callback):
            return True

    monkeypatch.setattr("app.main.analyze_job", fake_analyze_job)
    monkeypatch.setattr("app.main.CallbackClient", FakeCallbackClient)

    client = TestClient(app)
    headers = {"X-Internal-Service-Token": "t" * 32}
    response = client.post("/internal/v2/analysis-jobs", json=_job(), headers=headers)
    assert response.status_code == 202
    assert response.json() == {"status": "accepted", "taskId": "task001"}
    assert client.post("/internal/v1/analysis-jobs", json=_job(), headers=headers).status_code == 404
