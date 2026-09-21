from __future__ import annotations

import asyncio
from typing import Any
import httpx

from .settings import is_allowed_callback_url, is_usable_internal_service_token, settings


STOP_CODES = {"TASK_GONE", "STALE_ATTEMPT", "IDEMPOTENCY_CONFLICT", "INTERVIEW_SESSION_GONE", "INTERVIEW_CALLBACK_STALE"}
class CallbackClient:
    def __init__(self, *, transport: httpx.AsyncBaseTransport | None = None, attempts: int | None = None, backoff_seconds: float | None = None):
        self.transport = transport
        self.attempts = attempts or settings.callback_attempts
        self.backoff_seconds = settings.callback_backoff_seconds if backoff_seconds is None else backoff_seconds

    async def post(self, url: str, callback: dict[str, Any]) -> bool:
        # The callback URL originates in the internal job payload.  Recheck it
        # here as well as at the FastAPI boundary so direct callers cannot turn
        # this client into an unrestricted HTTP proxy.
        if not is_allowed_callback_url(url):
            return False
        token = settings.internal_service_token
        # Never send a callback without a configured, non-placeholder service
        # token.  This also prevents retries from leaking an unauthenticated
        # request when deployment configuration is incomplete.
        if not is_usable_internal_service_token(token):
            return False
        timeout = httpx.Timeout(settings.read_timeout, connect=settings.connect_timeout)
        headers = {"X-Internal-Service-Token": token}
        for number in range(self.attempts):
            try:
                async with httpx.AsyncClient(timeout=timeout, transport=self.transport) as client:
                    response = await client.post(url, json=callback, headers=headers)
            except httpx.TransportError:
                response = None
            if response is not None:
                if 200 <= response.status_code < 300:
                    # Java may return a semantic stop code in an otherwise successful envelope.
                    try:
                        body = response.json()
                        code = body.get("code") if isinstance(body, dict) else None
                        accepted = body.get("accepted") if isinstance(body, dict) else None
                    except (ValueError, TypeError):
                        code = None
                        accepted = None
                    if accepted is False:
                        return False
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
