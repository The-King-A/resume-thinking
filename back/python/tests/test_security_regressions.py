import hashlib
import json

import pytest

from app.analysis_service import _hash_payload
from app.openai_compatible import ModelEndpointRejected, OpenAICompatibleClient


def test_hash_is_stable_for_fixture_like_payload():
    payload = {"taskId": "x", "attempt": 1, "outcome": "FAILED", "errorCode": "MODEL_UNAVAILABLE"}
    assert _hash_payload(payload) == hashlib.sha256(json.dumps(payload, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode()).hexdigest()


def test_lookalike_loopback_is_rejected():
    with pytest.raises(ModelEndpointRejected):
        OpenAICompatibleClient({"baseUrl": "http://127.0.0.1.evil", "model": "m", "apiKey": "k"})
