import json
import httpx
import pytest

from app.callback_client import CallbackClient
from app.settings import is_usable_internal_service_token, settings


@pytest.fixture(autouse=True)
def valid_internal_service_token(monkeypatch):
    monkeypatch.setattr(settings, "internal_service_token", "t" * 32)


@pytest.mark.parametrize(
    "token",
    [
        "",
        "t" * 31,
        "t" * 31 + " ",
        "t" * 16 + "\t" + "t" * 16,
        "可" * 32,
    ],
)
def test_internal_service_token_policy_rejects_weak_or_non_header_safe_values(token):
    assert is_usable_internal_service_token(token) is False


def test_internal_service_token_policy_accepts_printable_ascii_at_minimum_length():
    assert is_usable_internal_service_token("t" * 32) is True


@pytest.mark.asyncio
async def test_callback_retries_5xx_then_succeeds():
    calls = 0

    async def handler(request):
        nonlocal calls
        calls += 1
        return httpx.Response(500 if calls == 1 else 200)

    ok = await CallbackClient(transport=httpx.MockTransport(handler), attempts=2).post("http://127.0.0.1/callback", {"callbackId": "same"})
    assert ok and calls == 2


@pytest.mark.asyncio
async def test_callback_retries_keep_java_callback_id_and_payload_hash_unchanged():
    payload = {"callbackId": "callback001", "payloadHash": "a" * 64}
    bodies: list[dict] = []

    async def handler(request):
        bodies.append(json.loads(request.content))
        return httpx.Response(500 if len(bodies) == 1 else 200)

    assert await CallbackClient(
        transport=httpx.MockTransport(handler), attempts=2, backoff_seconds=0
    ).post("http://127.0.0.1/callback", payload)
    assert bodies == [payload, payload]


@pytest.mark.asyncio
async def test_callback_stops_on_task_gone():
    calls = 0

    async def handler(request):
        nonlocal calls
        calls += 1
        return httpx.Response(410, json={"code": "TASK_GONE", "secret": "redacted"})

    assert not await CallbackClient(transport=httpx.MockTransport(handler), attempts=3).post("http://127.0.0.1/callback", {})
    assert calls == 1


@pytest.mark.asyncio
async def test_callback_rejects_a_success_http_status_when_java_did_not_accept_the_callback():
    async def handler(_request):
        return httpx.Response(200, json={"code": "MODEL_OUTPUT_INVALID", "accepted": False})

    ok = await CallbackClient(transport=httpx.MockTransport(handler), attempts=2).post(
        "http://127.0.0.1/callback", {"callbackId": "callback001"}
    )

    assert ok is False


@pytest.mark.asyncio
async def test_callback_retries_protocol_transport_errors():
    calls = 0

    async def handler(_request):
        nonlocal calls
        calls += 1
        if calls == 1:
            raise httpx.ProtocolError("connection dropped")
        return httpx.Response(200)

    assert await CallbackClient(transport=httpx.MockTransport(handler), attempts=2, backoff_seconds=0).post("http://127.0.0.1/callback", {})
    assert calls == 2


@pytest.mark.asyncio
async def test_callback_fails_closed_when_internal_service_token_is_empty(monkeypatch):
    calls = 0

    async def handler(_request):
        nonlocal calls
        calls += 1
        return httpx.Response(200)

    monkeypatch.setattr(settings, "internal_service_token", "")

    ok = await CallbackClient(transport=httpx.MockTransport(handler), attempts=3).post(
        "http://127.0.0.1/callback", {}
    )

    assert ok is False
    assert calls == 0


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "token",
    [
        "replace-with-shared-service-token",
        "change-me-in-production",
        "placeholder-internal-token",
    ],
)
async def test_callback_fails_closed_for_placeholder_internal_service_token(monkeypatch, token):
    calls = 0

    async def handler(_request):
        nonlocal calls
        calls += 1
        return httpx.Response(200)

    monkeypatch.setattr(settings, "internal_service_token", token)

    ok = await CallbackClient(transport=httpx.MockTransport(handler), attempts=3).post(
        "http://127.0.0.1/callback", {}
    )

    assert ok is False
    assert calls == 0
