import base64
import json
from uuid import UUID, uuid4

import httpx
import pytest
from fastapi.testclient import TestClient

from app.callback_client import CallbackClient
from app.main import app
from app.models import AnalysisJob
from app.settings import is_allowed_callback_url, settings


def _job(*, callback_url: str = "http://127.0.0.1:8080/callback") -> dict:
    return {
        "taskId": str(uuid4()),
        "attempt": 1,
        "resumeVersion": 0,
        "sourceType": "TXT",
        "jobFamily": "JAVA_BACKEND",
        "document": {
            "contentBase64": base64.b64encode(b"resume text").decode("ascii"),
            "originalFilename": "resume.txt",
        },
        "allowedEvidence": [
            {
                "evidenceId": str(uuid4()),
                "sourceLocation": "txt:0",
                "sourceStart": 0,
                "sourceEnd": 11,
            }
        ],
        "jobDescriptionText": "Build reliable software with clear communication.",
        "redactionRequired": True,
        "callbackUrl": callback_url,
        "callbackToken": "c" * 32,
        "provider": {
            "baseUrl": "http://127.0.0.1:9000",
            "model": "model",
            "apiKey": "provider-secret",
        },
        "correlationId": str(uuid4()),
    }


@pytest.fixture(autouse=True)
def internal_security_settings(monkeypatch):
    monkeypatch.setattr(settings, "internal_service_token", "t" * 32)
    monkeypatch.setattr(settings, "java_callback_base_url", "http://127.0.0.1:8080")
    monkeypatch.setattr(settings, "callback_allowed_base_urls", "")


def test_analysis_job_requires_internal_service_auth_without_parsing_body():
    secret_resume = base64.b64encode(b"resume with private content").decode("ascii")
    payload = _job()
    payload["document"]["contentBase64"] = secret_resume
    payload["callbackToken"] = "callback-secret-should-not-echo-" + "x" * 32
    payload["provider"]["apiKey"] = "api-key-should-not-echo"

    response = TestClient(app).post("/internal/v1/analysis-jobs", json=payload)

    assert response.status_code == 401
    body = response.json()
    assert body["code"] == "AUTHENTICATION_REQUIRED"
    assert body["retryable"] is False
    assert "correlationId" in body
    assert secret_resume not in response.text
    assert "callback-secret-should-not-echo" not in response.text
    assert "api-key-should-not-echo" not in response.text


def test_analysis_job_accepts_the_released_java_backend_job_family():
    payload = _job()
    payload["jobFamily"] = "JAVA_BACKEND"

    parsed = AnalysisJob.model_validate(payload)

    assert parsed.model_dump(by_alias=True)["jobFamily"] == "JAVA_BACKEND"


def test_analysis_job_rejects_wrong_internal_service_auth():
    response = TestClient(app).post(
        "/internal/v1/analysis-jobs",
        headers={"X-Internal-Service-Token": "wrong-token"},
        json=_job(),
    )

    assert response.status_code == 401
    assert response.json()["code"] == "AUTHENTICATION_REQUIRED"


def test_analysis_job_fails_closed_when_internal_token_is_not_configured(monkeypatch):
    monkeypatch.setattr(settings, "internal_service_token", "")

    response = TestClient(app).post(
        "/internal/v1/analysis-jobs",
        headers={"X-Internal-Service-Token": "t" * 32},
        json=_job(),
    )

    assert response.status_code == 401
    assert response.json()["code"] == "AUTHENTICATION_REQUIRED"


def test_analysis_job_fails_closed_for_example_token(monkeypatch):
    monkeypatch.setattr(settings, "internal_service_token", "replace-with-shared-service-token")

    response = TestClient(app).post(
        "/internal/v1/analysis-jobs",
        headers={"X-Internal-Service-Token": "replace-with-shared-service-token"},
        json=_job(),
    )

    assert response.status_code == 401


def test_analysis_job_rejects_callback_url_outside_allowlist():
    response = TestClient(app).post(
        "/internal/v1/analysis-jobs",
        headers={"X-Internal-Service-Token": "t" * 32},
        json=_job(callback_url="http://169.254.169.254/latest/meta-data"),
    )

    assert response.status_code == 400
    body = response.json()
    assert body["code"] == "VALIDATION_ERROR"
    assert body["retryable"] is False
    assert "169.254.169.254" not in response.text


def test_validation_error_does_not_echo_sensitive_request_fields():
    payload = _job()
    payload["callbackToken"] = "callback-secret-" + "x" * 32
    payload["provider"]["apiKey"] = "api-key-secret"
    payload["document"]["contentBase64"] = "not-base64-sensitive-value"
    payload["attempt"] = "not-an-integer"

    response = TestClient(app).post(
        "/internal/v1/analysis-jobs",
        headers={"X-Internal-Service-Token": "t" * 32},
        json=payload,
    )

    assert response.status_code == 422
    body = response.json()
    assert set(body) == {"code", "message", "correlationId", "retryable"}
    assert body["code"] == "VALIDATION_ERROR"
    assert body["message"] == "Request validation failed."
    assert body["retryable"] is False
    UUID(body["correlationId"])
    assert "api-key-secret" not in response.text
    assert "callback-secret-" not in response.text
    assert "not-base64-sensitive-value" not in response.text
    assert "not-an-integer" not in response.text
    assert "detail" not in body


def test_callback_url_rejects_query_fragment_and_path_traversal():
    assert not is_allowed_callback_url("http://127.0.0.1/callback?token=secret")
    assert not is_allowed_callback_url("http://127.0.0.1/callback#secret")
    assert not is_allowed_callback_url("http://127.0.0.1/callback/%2e%2e/private")
    assert not is_allowed_callback_url(" http://127.0.0.1/callback")
    assert not is_allowed_callback_url("http://127.0.0.1/callback\n")


@pytest.mark.asyncio
async def test_callback_client_rejects_disallowed_url_before_network_request():
    calls = 0

    async def handler(_request):
        nonlocal calls
        calls += 1
        return httpx.Response(200)

    ok = await CallbackClient(transport=httpx.MockTransport(handler)).post(
        "http://192.0.2.1/callback", {"callbackToken": "secret"}
    )

    assert ok is False
    assert calls == 0


@pytest.mark.asyncio
async def test_callback_client_sends_internal_service_token_as_header():
    seen: dict[str, str] = {}

    async def handler(request):
        seen["token"] = request.headers.get("X-Internal-Service-Token", "")
        payload = json.loads(request.content)
        assert "callbackToken" not in payload
        return httpx.Response(200)

    ok = await CallbackClient(
        transport=httpx.MockTransport(handler), attempts=1
    ).post("http://127.0.0.1/callback", {"callbackId": str(uuid4())})

    assert ok is True
    assert seen["token"] == "t" * 32


def test_configured_java_callback_base_allows_same_origin_path(monkeypatch):
    monkeypatch.setattr(settings, "java_callback_base_url", "https://java.example.test/internal/v1")

    async def fake_analyze_job(_job):
        return {"callbackId": str(uuid4())}

    class FakeCallbackClient:
        async def post(self, _url, _callback):
            return True

    monkeypatch.setattr("app.main.analyze_job", fake_analyze_job)
    monkeypatch.setattr("app.main.CallbackClient", FakeCallbackClient)

    response = TestClient(app).post(
        "/internal/v1/analysis-jobs",
        headers={"X-Internal-Service-Token": "t" * 32},
        json=_job(callback_url="https://java.example.test/internal/v1/analysis-results"),
    )

    assert response.status_code == 202
