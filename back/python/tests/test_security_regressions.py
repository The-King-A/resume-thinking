import hashlib
import json

import pytest

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


@pytest.mark.parametrize("url", ["https://model.example.test:99999", "https://[::1"])
def test_malformed_provider_url_is_rejected(url):
    with pytest.raises(ModelEndpointRejected):
        OpenAICompatibleClient({"baseUrl": url, "model": "m", "apiKey": "k"})
