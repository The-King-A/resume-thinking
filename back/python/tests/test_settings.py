import pytest

from app.settings import Settings, load_local_runtime_environment


def test_local_runtime_environment_loads_only_missing_worker_settings(tmp_path, monkeypatch):
    for name in (
        "PYTHON_INTERNAL_SERVICE_TOKEN",
        "PYTHON_MODEL_READ_TIMEOUT",
        "PYTHON_MODEL_MAX_TOKENS",
        "PYTHON_MODEL_THINKING",
        "JAVA_CALLBACK_BASE_URL",
        "PYTHON_CALLBACK_ALLOWED_BASE_URLS",
        "UNRELATED_SECRET",
    ):
        monkeypatch.delenv(name, raising=False)

    env_file = tmp_path / ".env"
    env_file.write_text(
        "PYTHON_INTERNAL_SERVICE_TOKEN=token-" + "x" * 32 + "\n"
        "PYTHON_MODEL_READ_TIMEOUT=300\n"
        "PYTHON_MODEL_MAX_TOKENS=100000\n"
        "PYTHON_MODEL_THINKING=disabled\n"
        "JAVA_CALLBACK_BASE_URL=http://127.0.0.1:8080\n"
        "PYTHON_CALLBACK_ALLOWED_BASE_URLS=http://127.0.0.1:8080\n"
        "UNRELATED_SECRET=must-not-be-imported\n",
        encoding="utf-8",
    )

    load_local_runtime_environment(env_file)

    assert Settings().internal_service_token == "token-" + "x" * 32
    assert Settings().model_read_timeout == 300
    assert Settings().model_max_tokens == 100000
    assert Settings().model_thinking == "disabled"
    assert Settings().java_callback_base_url == "http://127.0.0.1:8080"
    assert Settings().callback_allowed_base_urls == "http://127.0.0.1:8080"
    assert "UNRELATED_SECRET" not in __import__("os").environ
    for name in (
        "PYTHON_INTERNAL_SERVICE_TOKEN",
        "PYTHON_MODEL_READ_TIMEOUT",
        "PYTHON_MODEL_MAX_TOKENS",
        "PYTHON_MODEL_THINKING",
        "JAVA_CALLBACK_BASE_URL",
        "PYTHON_CALLBACK_ALLOWED_BASE_URLS",
    ):
        monkeypatch.delenv(name, raising=False)


def test_local_runtime_environment_does_not_override_explicit_process_settings(tmp_path, monkeypatch):
    monkeypatch.setenv("PYTHON_INTERNAL_SERVICE_TOKEN", "process-" + "y" * 32)
    env_file = tmp_path / ".env"
    env_file.write_text("PYTHON_INTERNAL_SERVICE_TOKEN=file-" + "x" * 32 + "\n", encoding="utf-8")

    load_local_runtime_environment(env_file)

    assert Settings().internal_service_token == "process-" + "y" * 32


def test_model_read_timeout_is_separate_from_callback_timeout(monkeypatch):
    monkeypatch.delenv("PYTHON_MODEL_READ_TIMEOUT", raising=False)
    monkeypatch.delenv("PYTHON_MODEL_MAX_TOKENS", raising=False)
    monkeypatch.delenv("PYTHON_MODEL_THINKING", raising=False)
    config = Settings()
    assert config.read_timeout == 30
    assert config.model_read_timeout == 900
    assert config.model_max_tokens == 8192
    assert config.model_thinking == "auto"


@pytest.mark.parametrize(
    "raw,expected",
    [("not-a-number", 900), ("0", 1), ("10000", 900), ("nan", 900)],
)
def test_model_read_timeout_env_is_bounded_without_startup_failure(monkeypatch, raw, expected):
    monkeypatch.setenv("PYTHON_MODEL_READ_TIMEOUT", raw)

    assert Settings().model_read_timeout == expected


def test_model_thinking_env_accepts_supported_values(monkeypatch):
    for value in ("auto", "enabled", "disabled", " DISABLED "):
        monkeypatch.setenv("PYTHON_MODEL_THINKING", value)
        assert Settings().model_thinking == value.strip().lower()


def test_model_thinking_env_invalid_value_falls_back_to_auto(monkeypatch):
    monkeypatch.setenv("PYTHON_MODEL_THINKING", "unsupported-mode")

    assert Settings().model_thinking == "auto"


@pytest.mark.parametrize(
    "raw,expected",
    [("not-a-number", 8192), ("0", 1024), ("100000", 100000), ("999999", 100000)],
)
def test_model_max_tokens_env_is_bounded_without_startup_failure(monkeypatch, raw, expected):
    monkeypatch.setenv("PYTHON_MODEL_MAX_TOKENS", raw)

    assert Settings().model_max_tokens == expected
