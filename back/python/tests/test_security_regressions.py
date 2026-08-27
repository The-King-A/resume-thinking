import hashlib
import json
import math

import pytest
from pydantic import ValidationError

from app.analysis_service import _hash_payload
from app.openai_compatible import ModelEndpointRejected, OpenAICompatibleClient


def test_hash_uses_rfc8785_number_serialization():
    payload = {"taskId": "x", "attempt": 1, "score": 0.0, "text": "你好"}
    canonical = '{"attempt":1,"score":0,"taskId":"x","text":"你好"}'.encode("utf-8")
    assert _hash_payload(payload) == hashlib.sha256(canonical).hexdigest()


def test_lookalike_loopback_is_rejected():
    with pytest.raises(ModelEndpointRejected):
        OpenAICompatibleClient({"baseUrl": "http://127.0.0.1.evil", "model": "m", "apiKey": "k"})


def test_private_dns_result_is_rejected(monkeypatch):
    def private_result(*_args, **_kwargs):
        return [(2, 1, 6, "", ("10.0.0.8", 443))]

    monkeypatch.setattr("socket.getaddrinfo", private_result)
    with pytest.raises(ModelEndpointRejected):
        OpenAICompatibleClient({"baseUrl": "https://model.example.test", "model": "m", "apiKey": "k"})


@pytest.mark.parametrize("address", [
    "224.0.0.1", "239.255.255.250", "ff02::1", "::", "192.0.2.1", "100.64.0.1",
    "169.254.1.1", "fc00::1", "240.0.0.1",
])
def test_special_dns_result_is_rejected(monkeypatch, address):
    family = 10 if ":" in address else 2

    def special_result(*_args, **_kwargs):
        return [(family, 1, 6, "", (address, 443))]

    monkeypatch.setattr("socket.getaddrinfo", special_result)
    with pytest.raises(ModelEndpointRejected):
        OpenAICompatibleClient({"baseUrl": "https://model.example.test", "model": "m", "apiKey": "k"})


def test_analysis_result_rejects_coerced_numeric_types():
    from app.models import AnalysisResult

    payload = {"score": {"skills": "0", "projectExperience": 0, "workContent": 0, "educationExperience": 0, "softSkills": 0, "composite": 0}, "requirements": [], "suggestions": []}
    with pytest.raises(ValidationError):
        AnalysisResult.model_validate(payload)


def test_jcs_rejects_non_finite_numbers():
    with pytest.raises(ValueError):
        _hash_payload({"value": math.nan})


@pytest.mark.parametrize("url", ["https://model.example.test:99999", "https://[::1"])
def test_malformed_provider_url_is_rejected(url):
    with pytest.raises(ModelEndpointRejected):
        OpenAICompatibleClient({"baseUrl": url, "model": "m", "apiKey": "k"})
