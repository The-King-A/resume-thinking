from __future__ import annotations

import json
from typing import Any

import httpx
from pydantic import ValidationError

from .models import AnalysisRequest, AnalysisResult, Provider
from .settings import settings


class ModelOutputInvalid(Exception):
    code = "MODEL_OUTPUT_INVALID"


class ModelUnavailable(Exception):
    code = "MODEL_UNAVAILABLE"


class ModelEndpointRejected(Exception):
    code = "MODEL_ENDPOINT_REJECTED"


class OpenAICompatibleClient:
    def __init__(self, provider: Provider | dict[str, Any], *, transport: httpx.AsyncBaseTransport | None = None):
        self.provider = provider if isinstance(provider, Provider) else Provider.model_validate(provider)
        self.transport = transport

    async def complete_structured(self, request: AnalysisRequest | dict[str, Any]) -> AnalysisResult:
        req = request if isinstance(request, AnalysisRequest) else AnalysisRequest.model_validate(request)
        base = self.provider.base_url.rstrip("/")
        if not (base.startswith("https://") or base.startswith("http://127.0.0.1")):
            raise ModelEndpointRejected("provider endpoint rejected")
        payload = {"model": self.provider.model, "messages": [{"role": "user", "content": req.model_dump_json(by_alias=True)}], "response_format": {"type": "json_object"}}
        timeout = httpx.Timeout(settings.read_timeout, connect=settings.connect_timeout)
        try:
            async with httpx.AsyncClient(timeout=timeout, transport=self.transport) as client:
                response = await client.post(f"{base}/chat/completions", headers={"Authorization": f"Bearer {self.provider.api_key}", "Content-Type": "application/json"}, json=payload)
        except (httpx.TimeoutException, httpx.NetworkError, httpx.ProtocolError) as exc:
            raise ModelUnavailable("model unavailable") from exc
        if response.status_code >= 500:
            raise ModelUnavailable("model unavailable")
        if response.status_code >= 400:
            raise ModelEndpointRejected("model endpoint rejected")
        try:
            body = response.json()
            content = body.get("choices", [{}])[0].get("message", {}).get("content", body)
            parsed = json.loads(content) if isinstance(content, str) else content
            return AnalysisResult.model_validate(parsed)
        except (ValueError, KeyError, IndexError, TypeError, ValidationError, json.JSONDecodeError) as exc:
            raise ModelOutputInvalid("model output invalid") from exc


OpenAiClient = OpenAICompatibleClient
