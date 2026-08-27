from __future__ import annotations

import ipaddress
import os
import posixpath
from typing import Iterable
from urllib.parse import unquote, urlsplit

from pydantic import BaseModel, Field


class Settings(BaseModel):
    connect_timeout: float = Field(default=5.0, gt=0)
    read_timeout: float = Field(default=30.0, gt=0)
    callback_attempts: int = Field(default=3, ge=1, le=10)
    callback_backoff_seconds: float = Field(default=0.05, ge=0)
    # The worker intentionally has no insecure development default.  An empty
    # value makes the internal route reject every request until configured.
    internal_service_token: str = Field(
        default_factory=lambda: os.getenv("PYTHON_INTERNAL_SERVICE_TOKEN", ""),
        repr=False,
    )
    java_callback_base_url: str = Field(default_factory=lambda: os.getenv("JAVA_CALLBACK_BASE_URL", ""))
    callback_allowed_base_urls: str = Field(
        default_factory=lambda: os.getenv(
            "PYTHON_CALLBACK_ALLOWED_BASE_URLS",
            os.getenv("PYTHON_CALLBACK_ALLOWLIST", ""),
        )
    )


settings = Settings()


def _parse_http_url(value: str) -> tuple[str, str, int, str] | None:
    """Parse a callback URL without resolving or contacting its host."""
    if not isinstance(value, str) or not value or len(value) > 2048:
        return None
    # Reject surrounding/embedded whitespace and all C0 controls before
    # ``urlsplit`` can normalize them into a different target.
    if any(char.isspace() or ord(char) < 0x20 or ord(char) == 0x7F for char in value):
        return None
    try:
        parsed = urlsplit(value)
        hostname = parsed.hostname
        port = parsed.port
    except (TypeError, ValueError, UnicodeError):
        return None
    if (
        parsed.scheme.lower() not in {"http", "https"}
        or not parsed.netloc
        or not hostname
        or parsed.username is not None
        or parsed.password is not None
        or parsed.query
        or parsed.fragment
        or "\\" in parsed.path
        or "%" in hostname
    ):
        return None
    hostname = hostname.lower()
    # A trailing dot is deliberately not canonicalized.  Treating it as an
    # alias would make an allowlist comparison surprising and harder to audit.
    if hostname.endswith("."):
        return None
    if port is None:
        port = 80 if parsed.scheme.lower() == "http" else 443
    if not 1 <= port <= 65535:
        return None
    try:
        decoded_path = unquote(parsed.path or "/")
    except (TypeError, ValueError, UnicodeError):
        return None
    # Reject traversal and encoded traversal instead of relying on the HTTP
    # client's path normalization behavior.
    if any(part in {".", ".."} for part in decoded_path.split("/")):
        return None
    normalized_path = posixpath.normpath(decoded_path)
    if not normalized_path.startswith("/"):
        normalized_path = "/" + normalized_path
    if normalized_path != "/":
        normalized_path = normalized_path.rstrip("/")
    return parsed.scheme.lower(), hostname, port, normalized_path


def _iter_configured_bases(config: Settings) -> Iterable[str]:
    if config.java_callback_base_url:
        yield config.java_callback_base_url
    values = config.callback_allowed_base_urls
    if isinstance(values, str):
        yield from (item.strip() for item in values.split(",") if item.strip())
    elif isinstance(values, (list, tuple, set)):
        yield from (item for item in values if isinstance(item, str) and item.strip())


def _is_loopback_host(hostname: str) -> bool:
    if hostname == "localhost":
        return True
    try:
        return ipaddress.ip_address(hostname).is_loopback
    except ValueError:
        return False


def _path_is_under(path: str, base_path: str) -> bool:
    if base_path == "/":
        return True
    return path == base_path or path.startswith(base_path + "/")


def is_allowed_callback_url(url: str, config: Settings | None = None) -> bool:
    """Return whether a callback target is in the worker's egress allowlist.

    Loopback targets are the default for local development.  Deployments can
    add the configured Java callback origin (and optional additional exact
    origins) without changing the v1 JSON contract.
    """
    parsed = _parse_http_url(url)
    if parsed is None:
        return False
    scheme, hostname, port, path = parsed
    if _is_loopback_host(hostname):
        return True

    config = settings if config is None else config
    for base_url in _iter_configured_bases(config):
        base = _parse_http_url(base_url)
        if base is None:
            continue
        base_scheme, base_host, base_port, base_path = base
        if (
            scheme == base_scheme
            and hostname == base_host
            and port == base_port
            and _path_is_under(path, base_path)
        ):
            return True
    return False
