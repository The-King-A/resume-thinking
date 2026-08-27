import base64
from uuid import uuid4

import pytest

from app.analysis_service import analyze_job


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
    assert callback.get("errorCode") in {"MODEL_OUTPUT_INVALID", "MODEL_UNAVAILABLE"}
