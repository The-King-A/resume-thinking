from pathlib import Path
import re
import os
import shutil
import subprocess
import tempfile
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import pytest


RUNNER = Path(__file__).with_name("run_mvp_flow.ps1")


class _HealthHandler(BaseHTTPRequestHandler):
    def do_GET(self) -> None:  # noqa: N802 - required by BaseHTTPRequestHandler
        self.server.request_count += 1  # type: ignore[attr-defined]
        status = self.server.health_status  # type: ignore[attr-defined]
        self.send_response(status)
        self.end_headers()

    def log_message(self, format: str, *args: object) -> None:
        return


def _start_health_server(status: int) -> tuple[ThreadingHTTPServer, threading.Thread]:
    server = ThreadingHTTPServer(("127.0.0.1", 0), _HealthHandler)
    server.health_status = status  # type: ignore[attr-defined]
    server.request_count = 0  # type: ignore[attr-defined]
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    return server, thread


def _function_body(source: str, name: str) -> str:
    match = re.search(
        rf"function {re.escape(name)} \{{(?P<body>.*?)^\}}",
        source,
        flags=re.MULTILINE | re.DOTALL,
    )
    assert match is not None
    return match.group("body")


def test_isolated_runner_passes_the_requested_java_port_to_spring_boot() -> None:
    source = RUNNER.read_text(encoding="utf-8")

    assert "$javaPort = ([Uri]$javaBase).Port" in source
    assert "SetEnvironmentVariable('SERVER_PORT', [string]$javaPort, 'Process')" in source


def test_status_messages_do_not_contaminate_health_check_boolean_values() -> None:
    source = RUNNER.read_text(encoding="utf-8")
    body = _function_body(source, "Write-Flow")

    assert "Write-Output" not in body
    assert "Write-Host" in body


def test_runner_cleans_up_all_children_of_started_services() -> None:
    source = RUNNER.read_text(encoding="utf-8")

    assert "function Stop-StartedProcessTree" in source
    assert "Stop-StartedProcessTree $process" in source


def test_runner_reads_dotenv_as_utf8_for_windows_powershell_compatibility() -> None:
    source = RUNNER.read_text(encoding="utf-8")

    assert "Get-Content -LiteralPath $envPath -Encoding utf8" in source


def test_require_live_rejects_an_isolated_python_target_when_java_is_already_running() -> None:
    """Catch a runner that starts Python but cannot reconfigure existing Java."""
    powershell = shutil.which("powershell") or shutil.which("pwsh")
    if powershell is None:
        pytest.skip("PowerShell is required to exercise the Windows startup runner")

    java_server, _ = _start_health_server(200)
    python_server, _ = _start_health_server(503)
    try:
        with tempfile.TemporaryDirectory() as temporary_directory:
            temporary_path = Path(temporary_directory)
            launch_log = temporary_path / "python-launches.log"
            fake_python = temporary_path / "python.cmd"
            fake_python.write_text(
                "@echo off\n"
                f'echo %*>> "{launch_log}"\n'
                'if "%1"=="-c" echo 3.11\n'
                "exit /b 0\n",
                encoding="ascii",
            )
            environment = os.environ.copy()
            environment.update(
                {
                    "MVP_API_BASE_URL": f"http://127.0.0.1:{java_server.server_port}",
                    "MVP_PYTHON_BASE_URL": f"http://127.0.0.1:{python_server.server_port}",
                    "MVP_PYTHON_EXECUTABLE": str(fake_python),
                    "MVP_START_REDIS": "0",
                    "APP_ALLOW_LOCAL_MODEL_ENDPOINTS": "true",
                    "MYSQL_URL": "jdbc:mysql://127.0.0.1:3306/resume_thinking",
                    "MYSQL_USERNAME": "integration_test",
                    "MYSQL_PASSWORD": "integration_test",
                    "REDIS_HOST": "127.0.0.1",
                    "REDIS_PORT": "6379",
                    "MVP_REDIS_HOST": "127.0.0.1",
                    "MVP_REDIS_PORT": "6379",
                    "JWT_SIGNING_KEY_BASE64": "aW50ZWdyYXRpb24tdGVzdC1qd3Qta2V5",
                    "APP_ENCRYPTION_KEY_BASE64": "aW50ZWdyYXRpb24tdGVzdC1lbmNyeXB0aW9uLWtleQ==",
                    "PYTHON_INTERNAL_SERVICE_TOKEN": "integration-test-token",
                }
            )
            completed = subprocess.run(
                [
                    powershell,
                    "-NoProfile",
                    "-ExecutionPolicy",
                    "Bypass",
                    "-File",
                    str(RUNNER),
                    "-RequireLive",
                    "-ReadyTimeoutSeconds",
                    "1",
                ],
                cwd=RUNNER.parent.parent.parent,
                env=environment,
                text=True,
                capture_output=True,
                timeout=15,
                check=False,
            )

            assert completed.returncode == 2
            assert "[flow] FAIL configuration=JAVA_ALREADY_RUNNING_FOR_ISOLATED_PYTHON" in completed.stdout
            assert "[flow] hint=START_JAVA_WITH_TARGET_PYTHON_ANALYSIS_BASE_URL_OR_ALLOW_RUNNER_TO_START_JAVA" in completed.stdout
            assert not launch_log.exists()
            assert environment["MVP_PYTHON_BASE_URL"] not in completed.stdout
            assert environment["PYTHON_INTERNAL_SERVICE_TOKEN"] not in completed.stdout
    finally:
        java_server.shutdown()
        python_server.shutdown()
        java_server.server_close()
        python_server.server_close()


def test_runner_rejects_conflicting_redis_targets_before_health_checks() -> None:
    powershell = shutil.which("powershell") or shutil.which("pwsh")
    if powershell is None:
        pytest.skip("PowerShell is required to exercise the Windows startup runner")

    java_server, _ = _start_health_server(503)
    python_server, _ = _start_health_server(503)
    try:
        environment = os.environ.copy()
        environment.update(
            {
                "MVP_API_BASE_URL": f"http://127.0.0.1:{java_server.server_port}",
                "MVP_PYTHON_BASE_URL": f"http://127.0.0.1:{python_server.server_port}",
                "MVP_START_REDIS": "0",
                "APP_ALLOW_LOCAL_MODEL_ENDPOINTS": "true",
                "MYSQL_URL": "jdbc:mysql://127.0.0.1:3306/resume_thinking",
                "MYSQL_USERNAME": "integration_test",
                "MYSQL_PASSWORD": "integration_test",
                "REDIS_HOST": "127.0.0.1",
                "REDIS_PORT": "6379",
                "MVP_REDIS_HOST": "127.0.0.1",
                "MVP_REDIS_PORT": "6380",
                "JWT_SIGNING_KEY_BASE64": "aW50ZWdyYXRpb24tdGVzdC1qd3Qta2V5",
                "APP_ENCRYPTION_KEY_BASE64": "aW50ZWdyYXRpb24tdGVzdC1lbmNyeXB0aW9uLWtleQ==",
                "PYTHON_INTERNAL_SERVICE_TOKEN": "integration-test-token",
            }
        )
        completed = subprocess.run(
            [
                powershell,
                "-NoProfile",
                "-ExecutionPolicy",
                "Bypass",
                "-File",
                str(RUNNER),
                "-NoStart",
                "-ReadyTimeoutSeconds",
                "1",
            ],
            cwd=RUNNER.parent.parent.parent,
            env=environment,
            text=True,
            capture_output=True,
            timeout=15,
            check=False,
        )

        assert completed.returncode == 0
        assert "[flow] SKIP targets=UNSAFE_LOCAL_TARGET" in completed.stdout
        assert java_server.request_count == 0  # type: ignore[attr-defined]
        assert python_server.request_count == 0  # type: ignore[attr-defined]
    finally:
        java_server.shutdown()
        python_server.shutdown()
        java_server.server_close()
        python_server.server_close()
