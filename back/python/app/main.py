from __future__ import annotations

import hmac
from typing import Annotated
from uuid import UUID, uuid4

from fastapi import BackgroundTasks, Depends, FastAPI, Header, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from .analysis_service import analyze_job
from .callback_client import CallbackClient
from .models import AnalysisJob
from .settings import is_allowed_callback_url, settings



class InternalServiceAuthenticationError(Exception):
    pass


class CallbackUrlRejected(Exception):
    pass


app = FastAPI()


def _correlation_id(request: Request) -> str:
    candidate = request.headers.get("X-Correlation-Id")
    try:
        return str(UUID(candidate)) if candidate else str(uuid4())
    except (ValueError, TypeError, AttributeError):
        return str(uuid4())


def _error_response(request: Request, *, status_code: int, code: str, message: str) -> JSONResponse:
    return JSONResponse(
        status_code=status_code,
        content={
            "code": code,
            "message": message,
            "correlationId": _correlation_id(request),
            "retryable": False,
        },
    )


async def _validation_exception_handler(request: Request, _exc: RequestValidationError) -> JSONResponse:
    # Never serialize ``exc.errors()``: Pydantic may include the offending
    # input, which can contain an API key, callback token, or resume payload.
    return _error_response(
        request,
        status_code=422,
        code="VALIDATION_ERROR",
        message="Request validation failed.",
    )


async def _internal_auth_exception_handler(request: Request, _exc: InternalServiceAuthenticationError) -> JSONResponse:
    return _error_response(
        request,
        status_code=401,
        code="AUTHENTICATION_REQUIRED",
        message="Internal service authentication required.",
    )


async def _callback_url_exception_handler(request: Request, _exc: CallbackUrlRejected) -> JSONResponse:
    return _error_response(
        request,
        status_code=400,
        code="VALIDATION_ERROR",
        message="Request validation failed.",
    )


app.add_exception_handler(RequestValidationError, _validation_exception_handler)
app.add_exception_handler(InternalServiceAuthenticationError, _internal_auth_exception_handler)
app.add_exception_handler(CallbackUrlRejected, _callback_url_exception_handler)


async def require_internal_service_auth(
    token: Annotated[str | None, Header(alias="X-Internal-Service-Token")] = None,
) -> None:
    expected = settings.internal_service_token
    # Empty configuration fails closed.  compare_digest avoids timing-based
    # token checks once an operator has configured the shared secret.
    placeholder = ("replace-with", "change-me", "placeholder")
    if (
        not isinstance(expected, str)
        or not expected
        or any(marker in expected.lower() for marker in placeholder)
        or not token
        or not hmac.compare_digest(token, expected)
    ):
        raise InternalServiceAuthenticationError


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok"}


@app.post("/internal/v2/analysis-jobs", status_code=202)
async def submit_analysis_job(
    job: AnalysisJob,
    background_tasks: BackgroundTasks,
    _auth: Annotated[None, Depends(require_internal_service_auth)],
) -> dict[str, str]:
    if not is_allowed_callback_url(job.callback_url):
        raise CallbackUrlRejected

    async def process() -> None:
        callback = await analyze_job(job)
        await CallbackClient().post(job.callback_url, callback)

    background_tasks.add_task(process)
    return {"status": "accepted", "taskId": str(job.task_id)}
