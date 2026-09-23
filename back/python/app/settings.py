from __future__ import annotations

import ipaddress
import math
import os
import posixpath
from pathlib import Path
from typing import Iterable, Literal
from urllib.parse import unquote, urlsplit

from pydantic import BaseModel, Field


_LOCAL_RUNTIME_ENVIRONMENT_KEYS = frozenset(
    {
        "PYTHON_INTERNAL_SERVICE_TOKEN",
        "PYTHON_MODEL_READ_TIMEOUT",
        "PYTHON_MODEL_MAX_TOKENS",
        "PYTHON_MODEL_THINKING",
        "JAVA_CALLBACK_BASE_URL",
        "PYTHON_CALLBACK_ALLOWED_BASE_URLS",
    }
)


def load_local_runtime_environment(env_file: Path | None = None) -> None:
    """Load only worker-owned local settings without overriding process config.

    Java and FastAPI are commonly started from separate local shells.  The
    project root's ``.env`` is their shared local source, but Uvicorn does not
    load it by itself.  Explicit process variables remain authoritative, and
    unrelated values such as database credentials are intentionally ignored.
    """
    path = env_file or Path(__file__).resolve().parents[3] / ".env"
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except OSError:
        return
    for raw_line in lines:
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        name, value = line.split("=", 1)
        name = name.strip().lstrip("\ufeff")
        if name not in _LOCAL_RUNTIME_ENVIRONMENT_KEYS or name in os.environ:
            continue
        value = value.strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in {"'", '"'}:
            value = value[1:-1]
        os.environ[name] = value


load_local_runtime_environment()


_MODEL_MAX_TOKENS_DEFAULT = 8192
_MODEL_MAX_TOKENS_MIN = 1024
# Keep a finite local bound while allowing the 100000-token value supported by
# current DeepSeek models.  Invalid or excessive environment values are
# clamped instead of preventing the worker from starting.
_MODEL_MAX_TOKENS_MAX = 100_000
_MODEL_READ_TIMEOUT_DEFAULT = 900.0
_MODEL_READ_TIMEOUT_MIN = 1.0
_MODEL_READ_TIMEOUT_MAX = 900.0


def _thinking_mode_from_env() -> Literal["auto", "enabled", "disabled"]:
    value = os.getenv("PYTHON_MODEL_THINKING", "auto").strip().lower()
    return value if value in {"auto", "enabled", "disabled"} else "auto"


def _bounded_int_from_env(name: str, default: int, minimum: int, maximum: int) -> int:
    raw = os.getenv(name)
    if raw is None:
        return default
    try:
        value = int(raw.strip())
    except (AttributeError, TypeError, ValueError):
        return default
    return min(max(value, minimum), maximum)


def _bounded_float_from_env(name: str, default: float, minimum: float, maximum: float) -> float:
    raw = os.getenv(name)
    if raw is None:
        return default
    try:
        value = float(raw.strip())
    except (AttributeError, TypeError, ValueError):
        return default
    if not math.isfinite(value):
        return default
    return min(max(value, minimum), maximum)


class Settings(BaseModel):
    connect_timeout: float = Field(default=5.0, gt=0)
    read_timeout: float = Field(default=30.0, gt=0)
    # Provider responses (especially reasoning models) can take longer than
    # the short callback transport window. Keep this separate so a slow model
    # is not incorrectly reported as a callback/service failure.
    model_read_timeout: float = Field(
        default_factory=lambda: _bounded_float_from_env(
            "PYTHON_MODEL_READ_TIMEOUT",
            _MODEL_READ_TIMEOUT_DEFAULT,
            _MODEL_READ_TIMEOUT_MIN,
            _MODEL_READ_TIMEOUT_MAX,
        ),
        ge=_MODEL_READ_TIMEOUT_MIN,
        le=_MODEL_READ_TIMEOUT_MAX,
    )
    model_max_tokens: int = Field(
        default_factory=lambda: _bounded_int_from_env(
            "PYTHON_MODEL_MAX_TOKENS",
            _MODEL_MAX_TOKENS_DEFAULT,
            _MODEL_MAX_TOKENS_MIN,
            _MODEL_MAX_TOKENS_MAX,
        ),
        ge=_MODEL_MAX_TOKENS_MIN,
        le=_MODEL_MAX_TOKENS_MAX,
    )
    model_thinking: Literal["auto", "enabled", "disabled"] = Field(
        default_factory=_thinking_mode_from_env,
    )
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


_TOKEN_PLACEHOLDER_MARKERS = ("replace-with", "change-me", "placeholder")


def is_usable_internal_service_token(token: object) -> bool:
    """Require a generated-looking header-safe shared secret.

    The value is deliberately constrained to printable ASCII because it is
    transported in an HTTP header. Length and character checks are applied at
    both ingress and egress so a weak local configuration cannot silently
    downgrade the private service boundary.
    """
    if not isinstance(token, str) or len(token) < 32:
        return False
    if any(not 0x21 <= ord(character) <= 0x7E for character in token):
        return False
    normalized = token.lower()
    return not any(marker in normalized for marker in _TOKEN_PLACEHOLDER_MARKERS)


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
    origins) without changing the v2 JSON contract.
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
