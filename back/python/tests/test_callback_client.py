import httpx
import pytest

from app.callback_client import CallbackClient


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
async def test_callback_stops_on_task_gone():
    calls = 0

    async def handler(request):
        nonlocal calls
        calls += 1
        return httpx.Response(410, json={"code": "TASK_GONE", "secret": "redacted"})

    assert not await CallbackClient(transport=httpx.MockTransport(handler), attempts=3).post("http://127.0.0.1/callback", {})
    assert calls == 1
