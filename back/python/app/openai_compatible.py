from __future__ import annotations

import json
import ipaddress
from urllib.parse import urlsplit
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
        self._validate_endpoint(self.provider.base_url)

    @staticmethod
    def _validate_endpoint(url: str) -> None:
        parsed = urlsplit(url)
        if parsed.username or parsed.password or not parsed.hostname or parsed.query or parsed.fragment:
            raise ModelEndpointRejected("provider endpoint rejected")
        try:
            address = ipaddress.ip_address(parsed.hostname)
        except ValueError:
            address = None
        if parsed.scheme == "http":
            if address != ipaddress.ip_address("127.0.0.1"):
                raise ModelEndpointRejected("provider endpoint rejected")
        elif parsed.scheme == "https":
            if address is not None and (address.is_private or address.is_loopback or address.is_link_local or address.is_reserved or address.is_multicast):
                raise ModelEndpointRejected("provider endpoint rejected")
        else:
            raise ModelEndpointRejected("provider endpoint rejected")

    async def complete_structured(self, request: AnalysisRequest | dict[str, Any]) -> AnalysisResult:
        req = request if isinstance(request, AnalysisRequest) else AnalysisRequest.model_validate(request)
        base = self.provider.base_url.rstrip("/")
        payload = {"model": self.provider.model, "messages": [{"role": "user", "content": req.model_dump_json(by_alias=True)}], "response_format": {"type": "json_object"}}
        timeout = httpx.Timeout(settings.read_timeout, connect=settings.connect_timeout)
        try:
            async with httpx.AsyncClient(timeout=timeout, transport=self.transport) as client:
                response = await client.post(f"{base}/chat/completions", headers={"Authorization": f"Bearer {self.provider.api_key}", "Content-Type": "application/json"}, json=payload, follow_redirects=False)
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
        except (ValueError, KeyError, IndexError, TypeError, AttributeError, ValidationError, json.JSONDecodeError) as exc:
            raise ModelOutputInvalid("model output invalid") from exc


OpenAiClient = OpenAICompatibleClient
