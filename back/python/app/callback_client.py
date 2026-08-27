from __future__ import annotations

import asyncio
from typing import Any
import httpx

from .settings import settings


STOP_CODES = {"TASK_GONE", "STALE_ATTEMPT", "IDEMPOTENCY_CONFLICT"}


class CallbackClient:
    def __init__(self, *, transport: httpx.AsyncBaseTransport | None = None, attempts: int | None = None, backoff_seconds: float | None = None):
        self.transport = transport
        self.attempts = attempts or settings.callback_attempts
        self.backoff_seconds = settings.callback_backoff_seconds if backoff_seconds is None else backoff_seconds

    async def post(self, url: str, callback: dict[str, Any]) -> bool:
        timeout = httpx.Timeout(settings.read_timeout, connect=settings.connect_timeout)
        for number in range(self.attempts):
            try:
                async with httpx.AsyncClient(timeout=timeout, transport=self.transport) as client:
                    response = await client.post(url, json=callback)
            except httpx.TransportError:
                response = None
            if response is not None:
                if 200 <= response.status_code < 300:
                    # Java may return a semantic stop code in an otherwise successful envelope.
                    try:
                        code = response.json().get("code")
                    except (ValueError, TypeError):
                        code = None
                    if code in STOP_CODES:
                        return False
                    return True
                if response.status_code in {409, 410, 412}:
                    return False
                if response.status_code < 500:
                    return False
            if number + 1 < self.attempts:
                await asyncio.sleep(self.backoff_seconds * (2**number))
        return False


async def post_callback(url: str, callback: dict[str, Any], **kwargs: Any) -> bool:
    return await CallbackClient(**kwargs).post(url, callback)
