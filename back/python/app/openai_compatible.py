from __future__ import annotations

import json
import ipaddress
import socket
from urllib.parse import urlsplit
from typing import Any

import httpx
from pydantic import ValidationError

from .models import AnalysisRequest, AnalysisResult, Provider
from .redaction import redact_text
from .settings import settings


class ModelOutputInvalid(Exception):
    code = "MODEL_OUTPUT_INVALID"


class ModelUnavailable(Exception):
    code = "MODEL_UNAVAILABLE"


class ModelEndpointRejected(Exception):
    code = "MODEL_ENDPOINT_REJECTED"


def _sanitize_model_value(value: Any, secrets: tuple[str, ...]) -> Any:
    """Redact sensitive strings in provider output before it crosses the boundary."""
    if isinstance(value, str):
        safe = redact_text(value).redacted_text
        for secret in secrets:
            safe = safe.replace(secret, "[REDACTED_SECRET]")
        return safe
    if isinstance(value, dict):
        return {key: _sanitize_model_value(item, secrets) for key, item in value.items()}
    if isinstance(value, list):
        return [_sanitize_model_value(item, secrets) for item in value]
    if isinstance(value, tuple):
        return tuple(_sanitize_model_value(item, secrets) for item in value)
    return value


class OpenAICompatibleClient:
    def __init__(
        self,
        provider: Provider | dict[str, Any],
        *,
        transport: httpx.AsyncBaseTransport | None = None,
        blocked_secrets: tuple[str, ...] = (),
    ):
        self.provider = provider if isinstance(provider, Provider) else Provider.model_validate(provider)
        self.transport = transport
        self.blocked_secrets = tuple(secret for secret in blocked_secrets if secret)
        self._validate_endpoint(self.provider.base_url)

    @staticmethod
    def _validate_endpoint(url: str) -> None:
        try:
            parsed = urlsplit(url)
            hostname = parsed.hostname
            port = parsed.port
        except (ValueError, UnicodeError) as exc:
            raise ModelEndpointRejected("provider endpoint rejected") from exc
        if parsed.username or parsed.password or not hostname or parsed.query or parsed.fragment:
            raise ModelEndpointRejected("provider endpoint rejected")
        if port is None:
            port = 80 if parsed.scheme == "http" else 443
        if not 1 <= port <= 65535:
            raise ModelEndpointRejected("provider endpoint rejected")
        if parsed.scheme == "http":
            if hostname != "127.0.0.1":
                raise ModelEndpointRejected("provider endpoint rejected")
        elif parsed.scheme == "https":
            try:
                address = ipaddress.ip_address(hostname)
            except ValueError:
                address = None
            if address is not None:
                addresses = [address]
            else:
                try:
                    resolved = socket.getaddrinfo(hostname, port, type=socket.SOCK_STREAM)
                    addresses = [ipaddress.ip_address(item[4][0]) for item in resolved]
                except (OSError, ValueError, UnicodeError) as exc:
                    raise ModelEndpointRejected("provider endpoint rejected") from exc
            if not addresses or any(
                not item.is_global
                or item.is_multicast
                or item.is_unspecified
                or item.is_reserved
                for item in addresses
            ):
                raise ModelEndpointRejected("provider endpoint rejected")
        else:
            raise ModelEndpointRejected("provider endpoint rejected")

    async def complete_structured(self, request: AnalysisRequest | dict[str, Any]) -> AnalysisResult:
        req = request if isinstance(request, AnalysisRequest) else AnalysisRequest.model_validate(request)

        def sanitize(value: str) -> str:
            redacted = redact_text(value).redacted_text
            for secret in (self.provider.api_key, *self.blocked_secrets):
                redacted = redacted.replace(secret, "[REDACTED_SECRET]")
            return redacted

        safe_evidence = [
            evidence.model_copy(update={"excerpt": sanitize(evidence.excerpt)})
            for evidence in req.evidence
        ]
        req = req.model_copy(update={
            "resume_text": sanitize(req.resume_text),
            "job_description_text": sanitize(req.job_description_text),
            "evidence": safe_evidence,
        })
        base = self.provider.base_url.rstrip("/")
        payload = {"model": self.provider.model, "messages": [{"role": "user", "content": req.model_dump_json(by_alias=True)}], "response_format": {"type": "json_object"}}
        timeout = httpx.Timeout(settings.read_timeout, connect=settings.connect_timeout)
        # Resolve and classify the provider again immediately before egress.
        # The constructor check protects configuration, while this check
        # limits the DNS-rebinding window between validation and the request.
        self._validate_endpoint(self.provider.base_url)
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
            safe_parsed = _sanitize_model_value(
                parsed,
                (self.provider.api_key, *self.blocked_secrets),
            )
            return AnalysisResult.model_validate(safe_parsed)
        except (ValueError, KeyError, IndexError, TypeError, AttributeError, ValidationError, json.JSONDecodeError) as exc:
            raise ModelOutputInvalid("model output invalid") from exc


OpenAiClient = OpenAICompatibleClient
