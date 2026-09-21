from fastapi.testclient import TestClient

from app.main import app
from app.settings import settings
from test_interview_contracts import question_job


def test_v4_interview_job_requires_internal_token_without_echoing_sensitive_input(monkeypatch):
    monkeypatch.setattr(settings, "internal_service_token", "t" * 32)
    payload = question_job()
    payload["provider"]["apiKey"] = "private-provider-key"
    payload["callbackToken"] = "private-callback-token-" + "x" * 32

    response = TestClient(app).post("/internal/v4/interview-jobs", json=payload)

    assert response.status_code == 401
    assert response.json()["code"] == "AUTHENTICATION_REQUIRED"
    assert "private-provider-key" not in response.text
    assert "private-callback-token" not in response.text


def test_v4_interview_job_rejects_python_field_names(monkeypatch):
    monkeypatch.setattr(settings, "internal_service_token", "t" * 32)
    payload = question_job()
    payload["session_id"] = payload.pop("sessionId")

    response = TestClient(app).post("/internal/v4/interview-jobs", headers={"X-Internal-Service-Token": "t" * 32}, json=payload)

    assert response.status_code == 422
    assert response.json()["code"] == "VALIDATION_ERROR"
