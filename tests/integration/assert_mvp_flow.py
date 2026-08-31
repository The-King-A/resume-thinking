"""Offline contract checks and an opt-in, sanitized MVP integration flow.

The default test path only reads controlled fixtures.  ``--live`` exercises
the running Java and Python services with an in-process loopback provider.
Secrets, resume contents, callback credentials, and response bodies are never
printed by this module.
"""

from __future__ import annotations

import argparse
from datetime import datetime, timedelta, timezone
import json
import math
import os
import re
import secrets
import socket
import sys
import threading
import time
import zipfile
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any
from xml.etree import ElementTree

import pytest


FIXTURES = Path(__file__).resolve().parent / "fixtures"
JOB_PATH = FIXTURES / "java-backend-job.txt"
RESUME_PATH = FIXTURES / "student-resume.txt"
DOCX_PATH = FIXTURES / "student-resume.docx"
PDF_PATH = FIXTURES / "invalid-resume.pdf"
CONFIRMATION = "确认删除简历"
TERMINAL_TASK_STATES = {"SUCCEEDED", "FAILED", "TIMED_OUT", "BLOCKED"}
BUSINESS_ID_PREFIXES = {
    "user",
    "profile",
    "resume",
    "task",
    "callback",
    "requirement",
    "evidence",
    "result",
    "suggestion",
    "audit",
}


class FlowError(RuntimeError):
    """A safe-to-print integration error containing no response body."""

    def __init__(self, operation: str, status: int = 0, code: str | None = None) -> None:
        self.operation = operation
        self.status = status
        self.code = code
        detail = f" status={status}" if status else ""
        suffix = f" code={code}" if code else ""
        super().__init__(f"{operation}{detail}{suffix}")


def _require_business_id(value: Any, prefix: str, field: str) -> str:
    """Validate a v2 readable business identifier, separate from trace IDs."""
    if prefix not in BUSINESS_ID_PREFIXES or not isinstance(value, str):
        raise AssertionError(f"{field} is not a v2 business ID")
    if re.fullmatch(rf"{re.escape(prefix)}[0-9]{{3,}}", value) is None:
        raise AssertionError(f"{field} is not a v2 business ID")
    return value


def _require_httpx() -> Any:
    try:
        import httpx  # type: ignore
    except ImportError as exc:  # pragma: no cover - environment dependent
        raise FlowError("http client dependency unavailable") from exc
    return httpx


def _safe_json(response: Any) -> Any:
    try:
        return response.json()
    except (ValueError, TypeError):
        return None


def _error_code(response: Any) -> str | None:
    payload = _safe_json(response)
    if isinstance(payload, dict) and isinstance(payload.get("code"), str):
        return payload["code"]
    return None


class ApiClient:
    """Small contract client which deliberately discards response bodies on errors."""

    def __init__(self, base_url: str, timeout: float = 8.0) -> None:
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout
        self.token: str | None = None
        self._httpx = _require_httpx()
        self.client = self._httpx.Client(timeout=timeout, follow_redirects=False)

    def close(self) -> None:
        self.client.close()

    def _request(
        self,
        method: str,
        path: str,
        expected: set[int],
        *,
        body: dict[str, Any] | None = None,
        upload: tuple[str, bytes, str] | None = None,
        title: str | None = None,
    ) -> Any:
        headers: dict[str, str] = {}
        if self.token:
            headers["Authorization"] = f"Bearer {self.token}"
        files = None
        data = None
        if upload is not None:
            files = {"file": upload}
            data = {"title": title} if title else None
        try:
            response = self.client.request(
                method,
                f"{self.base_url}{path}",
                headers=headers,
                json=body if upload is None else None,
                files=files,
                data=data,
            )
        except self._httpx.HTTPError as exc:
            raise FlowError(path, code="SERVICE_UNAVAILABLE") from exc
        if response.status_code not in expected:
            raise FlowError(path, response.status_code, _error_code(response))
        if response.status_code == 204:
            return None
        payload = _safe_json(response)
        if payload is None:
            raise FlowError(path, response.status_code, "INVALID_RESPONSE")
        return payload

    def health(self, path: str) -> bool:
        try:
            response = self.client.get(f"{self.base_url}{path}")
        except self._httpx.HTTPError:
            return False
        return response.status_code == 200

    def register(self, username: str, email: str, password: str, role: str) -> dict[str, Any]:
        payload = self._request(
            "POST",
            "/api/v2/auth/register",
            {201},
            body={"username": username, "email": email, "password": password, "role": role},
        )
        self.token = payload.get("accessToken") if isinstance(payload, dict) else None
        if not self.token or not isinstance(payload.get("user"), dict):
            raise FlowError("register", code="INVALID_RESPONSE")
        return payload

    def create_profile(self, endpoint: str, api_key: str) -> dict[str, Any]:
        payload = self._request(
            "POST",
            "/api/v2/llm-profiles",
            {201},
            body={
                "displayName": "MVP loopback provider",
                "endpointUrl": endpoint,
                "modelName": "mvp-fixture-model",
                "apiKey": api_key,
                "selected": True,
            },
        )
        if not isinstance(payload, dict) or not payload.get("id"):
            raise FlowError("create profile", code="INVALID_RESPONSE")
        return payload

    def upload_resume(self, path: Path, title: str) -> dict[str, Any]:
        media_type = "application/octet-stream"
        if path.suffix.lower() == ".txt":
            media_type = "text/plain; charset=utf-8"
        elif path.suffix.lower() == ".docx":
            media_type = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        payload = self._request(
            "POST",
            "/api/v2/resumes",
            {201},
            upload=(path.name, path.read_bytes(), media_type),
            title=title,
        )
        if not isinstance(payload, dict) or not payload.get("id"):
            raise FlowError("upload resume", code="INVALID_RESPONSE")
        return payload

    def list_resumes(self) -> dict[str, Any]:
        return self._request("GET", "/api/v2/resumes", {200})

    def delete_resume(self, resume_id: str, version: int, *, admin: bool = False) -> dict[str, Any]:
        path = f"/api/v2/admin/recovery/resumes/{resume_id}" if admin else f"/api/v2/resumes/{resume_id}"
        payload = self._request(
            "DELETE",
            path,
            {200},
            body={"confirmationText": CONFIRMATION, "expectedVersion": version},
        )
        if not isinstance(payload, dict):
            raise FlowError("delete resume", code="INVALID_RESPONSE")
        return payload

    def recovery(self, *, admin: bool = False) -> dict[str, Any]:
        path = "/api/v2/admin/recovery/resumes" if admin else "/api/v2/recovery/resumes"
        return self._request("GET", path, {200})

    def restore(self, resume_id: str, version: int, *, admin: bool = False) -> dict[str, Any]:
        path = (
            f"/api/v2/admin/recovery/resumes/{resume_id}/restore"
            if admin
            else f"/api/v2/recovery/resumes/{resume_id}/restore"
        )
        payload = self._request("POST", path, {200}, body={"expectedVersion": version})
        if not isinstance(payload, dict):
            raise FlowError("restore resume", code="INVALID_RESPONSE")
        return payload

    def create_task(self, resume_id: str, profile_id: str, job_text: str) -> dict[str, Any]:
        payload = self._request(
            "POST",
            "/api/v2/match-tasks",
            {202},
            body={
                "resumeId": resume_id,
                "llmProfileId": profile_id,
                "jobFamily": "JAVA_BACKEND",
                "jobDescriptionText": job_text,
                "idempotencyKey": f"mvp-{secrets.token_hex(16)}",
            },
        )
        if not isinstance(payload, dict) or not payload.get("id"):
            raise FlowError("create task", code="INVALID_RESPONSE")
        return payload

    def task(self, task_id: str) -> dict[str, Any]:
        payload = self._request("GET", f"/api/v2/match-tasks/{task_id}", {200})
        if not isinstance(payload, dict):
            raise FlowError("get task", code="INVALID_RESPONSE")
        return payload

    def result(self, task_id: str) -> dict[str, Any]:
        payload = self._request("GET", f"/api/v2/match-tasks/{task_id}/result", {200})
        if not isinstance(payload, dict):
            raise FlowError("get result", code="INVALID_RESPONSE")
        return payload


def _emit(event: str, *, identifier: str | None = None, state: str | None = None, status: int | None = None, code: str | None = None) -> None:
    """Print only identifiers, enum-like states, and HTTP status codes."""
    parts = [f"[flow] {event}"]
    if identifier:
        parts.append(f"id={identifier}")
    if state:
        parts.append(f"state={state}")
    if status is not None:
        parts.append(f"status={status}")
    if code:
        parts.append(f"code={code}")
    print(" ".join(parts), flush=True)


def _assert_result_evidence(result: dict[str, Any], *, source_text: str | None = None) -> None:
    _require_business_id(result.get("taskId"), "task", "result.taskId")
    _require_business_id(result.get("resumeId"), "resume", "result.resumeId")

    def unit_number(value: Any, field: str) -> float:
        if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(float(value)):
            raise AssertionError(f"{field} must be a finite number")
        numeric = float(value)
        if not 0 <= numeric <= 1:
            raise AssertionError(f"{field} must be between 0 and 1")
        return numeric

    score = result.get("score")
    if not isinstance(score, dict):
        raise AssertionError("result score missing")
    score_fields = ("skills", "projectExperience", "workContent", "educationExperience", "softSkills", "composite")
    if any(field not in score for field in score_fields):
        raise AssertionError("score components are incomplete")
    values = {field: unit_number(score[field], f"score.{field}") for field in score_fields}
    expected = (
        0.40 * values["skills"]
        + 0.25 * values["projectExperience"]
        + 0.15 * values["workContent"]
        + 0.10 * values["educationExperience"]
        + 0.10 * values["softSkills"]
    )
    if abs(values["composite"] - round(expected, 4)) > 1e-6:
        raise AssertionError("composite score is inconsistent")

    requirements = result.get("requirements")
    if not isinstance(requirements, list) or not requirements:
        raise AssertionError("result has no requirement evidence")
    requirement_ids: set[str] = set()
    evidence_ids: set[str] = set()
    evidence_metadata: dict[str, tuple[Any, ...]] = {}
    required_evidence_fields = {"id", "sourceType", "sourceLocation", "excerpt", "confidence", "strength"}
    allowed_evidence_fields = required_evidence_fields | {"sourceStart", "sourceEnd"}
    source_types = {"TXT", "DOCX"}
    requirement_types = {"MANDATORY", "PREFERRED"}
    match_statuses = {"SATISFIED", "PARTIALLY_SATISFIED", "RELATED_BUT_EVIDENCE_INSUFFICIENT", "UNMET"}
    match_types = {"EXACT", "SEMANTIC", "RELATED", "NO_MATCH"}
    components = {"SKILLS", "PROJECT_EXPERIENCE", "WORK_CONTENT", "EDUCATION_EXPERIENCE", "SOFT_SKILLS"}
    suggestion_states = {"SUPPORTED_FACT", "WORDING_ONLY_REWRITE", "NEEDS_USER_CONFIRMATION", "RISKY_OR_UNSUPPORTED"}
    strengths = {"NONE", "LOW", "MEDIUM", "HIGH"}
    evidence_count = 0
    for requirement in requirements:
        if not isinstance(requirement, dict) or not requirement.get("requirementText"):
            raise AssertionError("requirement text missing")
        requirement_id = _require_business_id(requirement.get("requirementId"), "requirement", "requirementId")
        if requirement_id in requirement_ids:
            raise AssertionError("duplicate requirement ID")
        requirement_ids.add(requirement_id)
        for field, allowed in (("requirementType", requirement_types), ("matchStatus", match_statuses), ("matchType", match_types), ("component", components), ("suggestionState", suggestion_states)):
            if requirement.get(field) not in allowed:
                raise AssertionError(f"invalid requirement {field}")
        unit_number(requirement.get("componentScore"), "requirement.componentScore")
        evidence = requirement.get("evidence")
        if not isinstance(evidence, list):
            raise AssertionError("requirement evidence missing")
        if requirement.get("matchStatus") in {"SATISFIED", "PARTIALLY_SATISFIED"} and not evidence:
            raise AssertionError("satisfied requirement has no evidence")
        for item in evidence:
            if not isinstance(item, dict) or set(item) - allowed_evidence_fields:
                raise AssertionError("evidence fields are not v2")
            if any(field not in item for field in required_evidence_fields):
                raise AssertionError("evidence fields are incomplete")
            if not item.get("excerpt") or not item.get("sourceLocation"):
                raise AssertionError("evidence excerpt missing")
            evidence_id = _require_business_id(item.get("id"), "evidence", "evidence.id")
            evidence_ids.add(evidence_id)
            if item.get("sourceType") not in source_types or item.get("strength") not in strengths:
                raise AssertionError("evidence enum is invalid")
            unit_number(item["confidence"], "evidence.confidence")
            metadata = (item["sourceType"], item["sourceLocation"], item["excerpt"], item["confidence"], item["strength"])
            if evidence_id in evidence_metadata and evidence_metadata[evidence_id] != metadata:
                raise AssertionError("evidence ID metadata changed across references")
            evidence_metadata[evidence_id] = metadata
            has_start, has_end = "sourceStart" in item, "sourceEnd" in item
            if has_start != has_end:
                raise AssertionError("evidence offsets must be supplied together")
            if has_start:
                start, end = item["sourceStart"], item["sourceEnd"]
                if isinstance(start, bool) or isinstance(end, bool) or not isinstance(start, int) or not isinstance(end, int):
                    raise AssertionError("evidence offsets must be integers")
                if start < 0 or end <= start:
                    raise AssertionError("evidence offset is invalid")
                if source_text is not None:
                    if end > len(source_text):
                        raise AssertionError("evidence offset exceeds source length")
                    if str(item["excerpt"]) != source_text[start:end]:
                        raise AssertionError("evidence excerpt does not match source bounds")
            evidence_count += 1
    if evidence_count == 0:
        raise AssertionError("result contains no evidence references")
    suggestions = result.get("suggestions")
    if not isinstance(suggestions, list):
        raise AssertionError("result suggestions missing")
    for suggestion in suggestions:
        if not isinstance(suggestion, dict) or suggestion.get("state") not in suggestion_states:
            raise AssertionError("suggestion state is invalid")
        _require_business_id(suggestion.get("id"), "suggestion", "suggestion.id")
        suggestion_requirement = _require_business_id(
            suggestion.get("requirementId"), "requirement", "suggestion.requirementId"
        )
        if suggestion_requirement not in requirement_ids or not suggestion.get("proposedText"):
            raise AssertionError("suggestion references are invalid")
        refs = suggestion.get("evidenceIds")
        if not isinstance(refs, list):
            raise AssertionError("suggestion evidence references missing")
        for reference in refs:
            reference_id = _require_business_id(reference, "evidence", "suggestion.evidenceIds")
            if reference_id not in evidence_ids:
                raise AssertionError("suggestion references unknown evidence")
        if suggestion["state"] in {"SUPPORTED_FACT", "WORDING_ONLY_REWRITE"} and not refs:
            raise AssertionError("supported suggestion has no evidence")


def _assert_callback_race_fixtures(
    valid: dict[str, Any],
    duplicate: dict[str, Any],
    stale: dict[str, Any],
    deleted: dict[str, Any],
    context: dict[str, Any],
) -> None:
    _require_business_id(valid.get("taskId"), "task", "valid.taskId")
    _require_business_id(valid.get("callbackId"), "callback", "valid.callbackId")
    _require_business_id(stale.get("taskId"), "task", "stale.taskId")
    _require_business_id(stale.get("callbackId"), "callback", "stale.callbackId")
    _require_business_id(deleted.get("taskId"), "task", "deleted.taskId")
    _require_business_id(deleted.get("callbackId"), "callback", "deleted.callbackId")
    _require_business_id(context.get("task", {}).get("id"), "task", "context.task.id")
    _require_business_id(context.get("resume", {}).get("id"), "resume", "context.resume.id")
    _require_business_id(context.get("resume", {}).get("ownerId"), "user", "context.resume.ownerId")
    if duplicate != valid:
        raise AssertionError("duplicate callback fixture must replay the exact payload")
    if stale.get("taskId") != valid.get("taskId") or stale.get("attempt", 0) >= valid.get("attempt", 0):
        raise AssertionError("stale callback fixture must target the same task with an older attempt")
    if stale.get("callbackId") == valid.get("callbackId"):
        raise AssertionError("stale callback fixture must have a distinct callback ID")
    if deleted.get("taskId") != context.get("task", {}).get("id"):
        raise AssertionError("deleted callback task ID must match its context")
    if context.get("task", {}).get("state") != "BLOCKED" or context.get("task", {}).get("resultAvailable") is not False:
        raise AssertionError("deleted callback context must block task results")
    if context.get("expectedJavaRejection") != "TASK_GONE":
        raise AssertionError("deleted callback must expect TASK_GONE")


def _assert_retention_window(payload: dict[str, Any], expected_days: int) -> None:
    try:
        created = datetime.fromisoformat(str(payload["createdAt"]).replace("Z", "+00:00"))
        visible_until = datetime.fromisoformat(str(payload["visibleUntil"]).replace("Z", "+00:00"))
    except (KeyError, TypeError, ValueError) as exc:
        raise AssertionError("retention timestamps are missing or invalid") from exc
    if created.tzinfo is None:
        created = created.replace(tzinfo=timezone.utc)
    if visible_until.tzinfo is None:
        visible_until = visible_until.replace(tzinfo=timezone.utc)
    if visible_until - created != timedelta(days=expected_days):
        raise AssertionError(f"retention window is not exactly {expected_days} days")


def _redis_read_line(stream: Any) -> bytes:
    line = bytearray()
    while len(line) < 256:
        byte = stream.recv(1)
        if not byte:
            break
        line.extend(byte)
        if line.endswith(b"\r\n"):
            return bytes(line[:-2])
    return bytes(line)


def _assert_redis_key_absent(key: str) -> None:
    host = os.getenv("MVP_REDIS_HOST") or os.getenv("REDIS_HOST")
    port_raw = os.getenv("MVP_REDIS_PORT") or os.getenv("REDIS_PORT")
    if not host or not port_raw:
        _emit("redis result key", state="SKIP")
        return
    try:
        port = int(port_raw)
        with socket.create_connection((host, port), timeout=2) as connection:
            connection.settimeout(2)
            connection.sendall(b"*1\r\n$4\r\nPING\r\n")
            if _redis_read_line(connection) != b"+PONG":
                raise FlowError("redis ping", code="INVALID_RESPONSE")
            encoded_key = key.encode("utf-8")
            connection.sendall(b"*2\r\n$6\r\nEXISTS\r\n$" + str(len(encoded_key)).encode("ascii") + b"\r\n" + encoded_key + b"\r\n")
            if _redis_read_line(connection) != b":0":
                raise AssertionError("late result Redis key remained present")
    except (OSError, ValueError) as exc:
        raise FlowError("redis key probe", code="SERVICE_UNAVAILABLE") from exc
    _emit("redis result key", state="ABSENT")


def _docx_text(path: Path) -> str:
    with zipfile.ZipFile(path) as archive:
        xml = archive.read("word/document.xml")
    root = ElementTree.fromstring(xml)
    chunks: list[str] = []
    for node in root.iter():
        if node.tag.endswith("}t") and node.text:
            chunks.append(node.text)
        elif node.tag.endswith("}p"):
            chunks.append("\n")
    return "".join(chunks)


def test_fixture_documents_are_controlled() -> None:
    """The checked-in inputs stay deterministic and contain no credentials."""
    text = RESUME_PATH.read_text(encoding="utf-8")
    job = JOB_PATH.read_text(encoding="utf-8")
    assert "Java developer" in text
    assert "Spring Boot" in text
    assert "Java backend developer" in job
    assert "apiKey" not in text and "Bearer " not in text
    assert "apiKey" not in job and "Bearer " not in job
    assert "Java developer" in _docx_text(DOCX_PATH)


def test_pdf_fixture_is_explicitly_unsupported() -> None:
    assert PDF_PATH.suffix.lower() == ".pdf"
    assert PDF_PATH.read_bytes() == b"not a valid PDF\n"


def test_match_result_binds_requirements_to_existing_evidence() -> None:
    synthetic = {
        "taskId": "task001",
        "resumeId": "resume001",
        "score": {"skills": 0.8, "projectExperience": 0.7, "workContent": 0.6, "educationExperience": 0.5, "softSkills": 0.4, "composite": 0.675},
        "requirements": [
            {
                "requirementId": "requirement001",
                "requirementText": "Java",
                "requirementType": "MANDATORY",
                "matchStatus": "SATISFIED",
                "matchType": "EXACT",
                "component": "SKILLS",
                "componentScore": 0.8,
                "evidence": [{"id": "evidence001", "sourceType": "TXT", "sourceLocation": "SUMMARY", "sourceStart": 0, "sourceEnd": 14, "excerpt": "Java developer", "confidence": 0.95, "strength": "HIGH"}],
                "gap": None,
                "suggestionState": "NEEDS_USER_CONFIRMATION",
            }
        ],
        "suggestions": [],
    }
    _assert_result_evidence(synthetic)


def test_fixture_evidence_assertion_requires_exact_source_bounds() -> None:
    source = RESUME_PATH.read_text(encoding="utf-8")
    start = source.index("Java developer")
    synthetic = {
        "taskId": "task001",
        "resumeId": "resume001",
        "score": {"skills": 0.8, "projectExperience": 0.7, "workContent": 0.6, "educationExperience": 0.5, "softSkills": 0.4, "composite": 0.675},
        "requirements": [
            {
                "requirementId": "requirement001",
                "requirementText": "Java",
                "requirementType": "MANDATORY",
                "matchStatus": "SATISFIED",
                "matchType": "EXACT",
                "component": "SKILLS",
                "componentScore": 0.8,
                "evidence": [{"id": "evidence001", "sourceType": "TXT", "sourceLocation": "SUMMARY", "sourceStart": start, "sourceEnd": start + len("Java developer"), "excerpt": "Java developer", "confidence": 0.95, "strength": "HIGH"}],
                "gap": None,
                "suggestionState": "NEEDS_USER_CONFIRMATION",
            }
        ],
        "suggestions": [],
    }
    _assert_result_evidence(synthetic, source_text=source)


def test_callback_fixture_races_preserve_duplicate_and_stale_semantics() -> None:
    fixture_root = Path(__file__).resolve().parents[2] / "contracts/fixtures/v2"
    valid = json.loads((fixture_root / "callback-valid.json").read_text(encoding="utf-8"))
    duplicate = json.loads((fixture_root / "callback-duplicate.json").read_text(encoding="utf-8"))
    stale = json.loads((fixture_root / "callback-stale.json").read_text(encoding="utf-8"))
    deleted = json.loads((fixture_root / "callback-after-soft-delete.json").read_text(encoding="utf-8"))
    context = json.loads((fixture_root / "callback-after-soft-delete-context.json").read_text(encoding="utf-8"))
    _assert_callback_race_fixtures(valid, duplicate, stale, deleted, context)


def test_retention_policy_is_explicit_for_user_and_admin() -> None:
    _assert_retention_window({"createdAt": "2026-08-27T00:00:00Z", "visibleUntil": "2026-09-03T00:00:00Z"}, 7)
    _assert_retention_window({"createdAt": "2026-08-27T00:00:00Z", "visibleUntil": "2026-09-26T00:00:00Z"}, 30)


def test_evidence_rejects_legacy_source_offset_and_incomplete_v2_fields() -> None:
    result = {
        "taskId": "task001",
        "resumeId": "resume001",
        "score": {"skills": 0.8, "projectExperience": 0.7, "workContent": 0.6, "educationExperience": 0.5, "softSkills": 0.4, "composite": 0.675},
        "requirements": [{"requirementText": "Java", "evidence": [{"sourceOffset": 0, "excerpt": "Java developer"}]}],
    }
    with pytest.raises(AssertionError):
        _assert_result_evidence(result)


def test_score_rejects_missing_or_non_finite_components() -> None:
    result = {
        "taskId": "task001",
        "resumeId": "resume001",
        "score": {"skills": 0.8, "composite": 0.32},
        "requirements": [{"requirementText": "Java", "evidence": [{"sourceOffset": 0, "excerpt": "Java developer"}]}],
    }
    with pytest.raises(AssertionError):
        _assert_result_evidence(result)


def test_v2_evidence_offsets_are_optional_when_both_are_absent() -> None:
    evidence_id = "evidence001"
    result = {
        "taskId": "task001",
        "resumeId": "resume001",
        "score": {"skills": 0.8, "projectExperience": 0.7, "workContent": 0.6, "educationExperience": 0.5, "softSkills": 0.4, "composite": 0.675},
        "requirements": [{
            "requirementId": "requirement001", "requirementText": "Java", "requirementType": "MANDATORY",
            "matchStatus": "SATISFIED", "matchType": "EXACT", "component": "SKILLS", "componentScore": 0.8,
            "evidence": [{"id": evidence_id, "sourceType": "TXT", "sourceLocation": "SUMMARY", "excerpt": "Java developer", "confidence": 0.95, "strength": "HIGH"}],
            "gap": None, "suggestionState": "NEEDS_USER_CONFIRMATION",
        }],
        "suggestions": [],
    }
    _assert_result_evidence(result)


def test_v2_evidence_id_can_be_reused_across_requirements() -> None:
    evidence_id = "evidence001"
    def requirement(text: str) -> dict[str, Any]:
        return {
            "requirementId": "requirement001" if text == "Java" else "requirement002", "requirementText": text, "requirementType": "MANDATORY",
            "matchStatus": "SATISFIED", "matchType": "EXACT", "component": "SKILLS", "componentScore": 0.8,
            "evidence": [{"id": evidence_id, "sourceType": "TXT", "sourceLocation": "SUMMARY", "excerpt": "Java developer", "confidence": 0.95, "strength": "HIGH"}],
            "gap": None, "suggestionState": "NEEDS_USER_CONFIRMATION",
        }
    result = {
        "taskId": "task001", "resumeId": "resume001",
        "score": {"skills": 0.8, "projectExperience": 0.7, "workContent": 0.6, "educationExperience": 0.5, "softSkills": 0.4, "composite": 0.675},
        "requirements": [requirement("Java"), requirement("Spring Boot")], "suggestions": [],
    }
    _assert_result_evidence(result)


class _ProviderState:
    def __init__(self) -> None:
        self.gated = False
        self.released = threading.Event()
        self.request_seen = threading.Event()
        self.request_count = 0
        self.lock = threading.Lock()


class FakeOpenAIProvider:
    """A local-only provider returning one evidence-bound deterministic result."""

    def __init__(self) -> None:
        self.state = _ProviderState()
        state = self.state

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *_args: Any) -> None:
                return

            def _send(self, status: int, payload: dict[str, Any]) -> None:
                body = json.dumps(payload, separators=(",", ":")).encode("utf-8")
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def do_GET(self) -> None:  # noqa: N802
                if self.path == "/health":
                    self._send(200, {"status": "ok", "data": []})
                else:
                    self._send(404, {"status": "not-found"})

            def do_POST(self) -> None:  # noqa: N802
                if self.path == "/control/gate":
                    with state.lock:
                        state.gated = True
                        state.released.clear()
                    self._send(200, {"status": "gated"})
                    return
                if self.path == "/control/release":
                    state.released.set()
                    self._send(200, {"status": "released"})
                    return
                if self.path.rstrip("/") != "/chat/completions":
                    self._send(404, {"status": "not-found"})
                    return
                try:
                    length = int(self.headers.get("Content-Length", "0"))
                    outer = json.loads(self.rfile.read(length))
                    content = outer["messages"][0]["content"]
                    request = json.loads(content) if isinstance(content, str) else content
                except (ValueError, KeyError, IndexError, TypeError):
                    self._send(400, {"status": "invalid"})
                    return
                with state.lock:
                    state.request_count += 1
                    state.request_seen.set()
                    gated = state.gated
                if gated:
                    state.released.wait(timeout=45)
                evidence = request.get("evidence", []) if isinstance(request, dict) else []
                requirements: list[dict[str, Any]] = []
                if evidence:
                    first = evidence[0]
                    requirements.append(
                        {
                            "requirementId": "requirement001",
                            "jobRequirementText": "Java backend development",
                            "requirementType": "MANDATORY",
                            "matchStatus": "SATISFIED",
                            "matchType": "EXACT",
                            "component": "SKILLS",
                            "componentScore": 0.8,
                            "evidence": [
                                {
                                    "evidenceId": first.get("evidenceId"),
                                    "sourceStart": first.get("sourceStart"),
                                    "sourceEnd": first.get("sourceEnd"),
                                    "excerpt": first.get("excerpt"),
                                    "confidence": 0.95,
                                }
                            ],
                            "evidenceStrength": "HIGH",
                            "gap": None,
                            "suggestionState": "NEEDS_USER_CONFIRMATION",
                        }
                    )
                result = {
                    "score": {
                        "skills": 0.8,
                        "projectExperience": 0.7,
                        "workContent": 0.6,
                        "educationExperience": 0.5,
                        "softSkills": 0.4,
                        "composite": 0.675,
                    },
                    "requirements": requirements,
                    "suggestions": [],
                }
                self._send(200, {"choices": [{"message": {"content": json.dumps(result, separators=(",", ":"))}}]})

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.server.daemon_threads = True
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)

    @property
    def base_url(self) -> str:
        return f"http://127.0.0.1:{self.server.server_port}"

    def start(self) -> "FakeOpenAIProvider":
        self.thread.start()
        return self

    def gate(self) -> None:
        with self.state.lock:
            self.state.gated = True
            self.state.released.clear()
            self.state.request_seen.clear()

    def release(self) -> None:
        self.state.released.set()

    def wait_for_request(self, timeout: float) -> bool:
        return self.state.request_seen.wait(timeout)

    def close(self) -> None:
        self.release()
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)


def _wait_for_state(client: ApiClient, task_id: str, timeout: float) -> dict[str, Any]:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        task = client.task(task_id)
        state = task.get("state")
        if state in TERMINAL_TASK_STATES:
            return task
        time.sleep(0.25)
    raise FlowError("wait task", code="DEADLINE_EXCEEDED")


def _wait_for_gone(client: ApiClient, task_id: str, timeout: float) -> FlowError:
    deadline = time.monotonic() + timeout
    last_state: str | None = None
    while time.monotonic() < deadline:
        try:
            task = client.task(task_id)
            last_state = str(task.get("state"))
        except FlowError as exc:
            if exc.status == 410 and exc.code == "TASK_GONE":
                return exc
            raise
        time.sleep(0.25)
    raise FlowError(f"wait task gone ({last_state or 'unknown'})", code="DEADLINE_EXCEEDED")


def _assert_absent(page: dict[str, Any], resume_id: str) -> None:
    items = page.get("items", []) if isinstance(page, dict) else []
    if any(isinstance(item, dict) and item.get("id") == resume_id for item in items):
        raise AssertionError("resume remained visible")


def run_live_flow(api_base: str, python_base: str | None = None, timeout: float = 45.0) -> None:
    provider = FakeOpenAIProvider().start()
    user = ApiClient(api_base, timeout=8)
    admin = ApiClient(api_base, timeout=8)
    suffix = secrets.token_hex(5)
    user_password = secrets.token_urlsafe(18)
    admin_password = secrets.token_urlsafe(18)
    # This credential is intentionally ephemeral and accepted only by the local fake provider.
    fixture_api_key = secrets.token_urlsafe(24)
    try:
        if not user.health("/actuator/health"):
            raise FlowError("java health", code="SERVICE_UNAVAILABLE")
        if python_base:
            python_health = ApiClient(python_base, timeout=3)
            try:
                if not python_health.health("/health"):
                    raise FlowError("python health", code="SERVICE_UNAVAILABLE")
            finally:
                python_health.close()
            _emit("python health", state="PASS")
        else:
            _emit("python health", state="SKIP")
        _emit("java health", state="PASS")

        registered_user = user.register(f"mvp_user_{suffix}", f"mvp_user_{suffix}@example.test", user_password, "USER")
        user_id = str(registered_user["user"]["id"])
        _emit("registered user", identifier=user_id, state="PASS")
        registered_admin = admin.register(f"mvp_admin_{suffix}", f"mvp_admin_{suffix}@example.test", admin_password, "ADMIN")
        admin_id = str(registered_admin["user"]["id"])
        _emit("registered admin", identifier=admin_id, state="PASS")

        try:
            profile = user.create_profile(provider.base_url, fixture_api_key)
        except FlowError as exc:
            if exc.code == "MODEL_ENDPOINT_REJECTED":
                raise FlowError("create local provider profile; enable APP_ALLOW_LOCAL_MODEL_ENDPOINTS", exc.status, exc.code) from exc
            raise
        profile_id = str(profile["id"])
        _emit("model profile", identifier=profile_id, state="PASS")

        resume = user.upload_resume(RESUME_PATH, "MVP student resume")
        resume_id = str(resume["id"])
        _assert_retention_window(resume, 7)
        _emit("TXT upload", identifier=resume_id, state=str(resume.get("visibilityState", "UNKNOWN")))
        docx_resume = user.upload_resume(DOCX_PATH, "MVP DOCX resume")
        _emit("DOCX upload", identifier=str(docx_resume["id"]), state=str(docx_resume.get("visibilityState", "UNKNOWN")))
        admin_resume = admin.upload_resume(RESUME_PATH, "MVP admin retention resume")
        _assert_retention_window(admin_resume, 30)
        _emit("ADMIN retention", identifier=str(admin_resume["id"]), state="30_DAYS")
        try:
            user.upload_resume(PDF_PATH, "MVP PDF rejection")
        except FlowError as exc:
            if exc.status != 415 or exc.code != "UNSUPPORTED_FILE":
                raise AssertionError("PDF upload did not return UNSUPPORTED_FILE") from exc
            _emit("PDF upload", state="UNSUPPORTED_FILE", status=exc.status)
        else:
            raise AssertionError("PDF upload unexpectedly succeeded")

        job_text = JOB_PATH.read_text(encoding="utf-8")
        task = user.create_task(resume_id, profile_id, job_text)
        task_id = str(task["id"])
        _emit("match task", identifier=task_id, state=str(task.get("state", "UNKNOWN")))
        terminal = _wait_for_state(user, task_id, timeout)
        if terminal.get("state") != "SUCCEEDED":
            raise AssertionError("match task did not succeed")
        result = user.result(task_id)
        _assert_result_evidence(result, source_text=RESUME_PATH.read_text(encoding="utf-8"))
        _emit("match result", identifier=task_id, state="SUCCEEDED")

        deleted = user.delete_resume(resume_id, int(resume.get("version", 0)))
        _emit("user soft delete", identifier=resume_id, state=str(deleted.get("visibilityState", "UNKNOWN")))
        _assert_absent(user.list_resumes(), resume_id)
        recovery = user.recovery()
        if not any(item.get("id") == resume_id for item in recovery.get("items", [])):
            raise AssertionError("deleted resume missing from user recovery")
        restored = user.restore(resume_id, int(deleted.get("version", 0)))
        _emit("user restore", identifier=resume_id, state=str(restored.get("visibilityState", "UNKNOWN")))

        provider.gate()
        late_resume = user.upload_resume(RESUME_PATH, "MVP late callback resume")
        late_id = str(late_resume["id"])
        late_task = user.create_task(late_id, profile_id, job_text)
        late_task_id = str(late_task["id"])
        if not provider.wait_for_request(timeout):
            raise FlowError("fake provider request", code="DEADLINE_EXCEEDED")
        late_deleted = user.delete_resume(late_id, int(late_resume.get("version", 0)))
        provider.release()
        gone = _wait_for_gone(user, late_task_id, timeout)
        _emit("late callback", identifier=late_task_id, state="TASK_GONE", status=gone.status)
        try:
            user.result(late_task_id)
        except FlowError as exc:
            if exc.status != 410 or exc.code != "TASK_GONE":
                raise AssertionError("late result did not return TASK_GONE") from exc
        else:
            raise AssertionError("late result unexpectedly available")
        _assert_redis_key_absent(f"resume:v2:view:{late_id}")
        _emit("late resume recovery", identifier=late_id, state=str(late_deleted.get("visibilityState", "UNKNOWN")))

        # Administrative deletion hides the record from the owner, including recovery.
        admin_deleted = admin.delete_resume(resume_id, int(restored.get("version", 0)), admin=True)
        _emit("admin soft delete", identifier=resume_id, state=str(admin_deleted.get("visibilityState", "UNKNOWN")))
        _assert_absent(user.list_resumes(), resume_id)
        _assert_absent(user.recovery(), resume_id)
        if not any(item.get("id") == resume_id for item in admin.recovery(admin=True).get("items", [])):
            raise AssertionError("admin recovery did not include deleted resume")
        admin_restored = admin.restore(resume_id, int(admin_deleted.get("version", 0)), admin=True)
        _emit("admin restore", identifier=resume_id, state=str(admin_restored.get("visibilityState", "UNKNOWN")))
        _emit("archive clock", state="SKIP")
    finally:
        user.close()
        admin.close()
        provider.close()


def _parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--live", action="store_true", help="run against live Java/Python services")
    parser.add_argument("--api-base", default=os.getenv("MVP_API_BASE_URL", "http://127.0.0.1:8080"))
    parser.add_argument("--python-base", default=os.getenv("MVP_PYTHON_BASE_URL"))
    parser.add_argument("--timeout", type=float, default=float(os.getenv("MVP_FLOW_TIMEOUT_SECONDS", "45")))
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = _parse_args(argv or sys.argv[1:])
    if not args.live:
        print("[flow] SKIP live: pass --live after Java, Python, Redis, and credentials are configured")
        return 0
    try:
        run_live_flow(args.api_base, args.python_base, max(5.0, min(args.timeout, 300.0)))
    except (AssertionError, FlowError) as exc:
        print(f"[flow] FAIL {exc}", flush=True)
        return 1
    print("[flow] PASS controlled MVP flow", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
