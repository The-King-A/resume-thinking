from __future__ import annotations

from typing import Any, Literal
from urllib.parse import urlsplit
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field, StrictFloat, StrictInt, field_validator, model_validator


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)


class Document(StrictModel):
    content_base64: str = Field(alias="contentBase64", min_length=4)
    original_filename: str = Field(alias="originalFilename", min_length=1, max_length=255)


class AllowedEvidence(StrictModel):
    evidence_id: UUID = Field(alias="evidenceId")
    source_location: str = Field(alias="sourceLocation", min_length=1, max_length=500)
    source_start: StrictInt = Field(alias="sourceStart", ge=0)
    source_end: StrictInt = Field(alias="sourceEnd", ge=0)

    @model_validator(mode="after")
    def non_empty_range(self) -> "AllowedEvidence":
        if self.source_start >= self.source_end:
            raise ValueError("evidence range must be non-empty")
        return self


class Provider(StrictModel):
    base_url: str = Field(alias="baseUrl", min_length=1, max_length=2048)
    model: str = Field(min_length=1, max_length=200)
    api_key: str = Field(alias="apiKey", min_length=1, max_length=4096)


class AnalysisJob(StrictModel):
    task_id: UUID = Field(alias="taskId")
    attempt: StrictInt = Field(ge=1)
    resume_version: StrictInt = Field(alias="resumeVersion", ge=0)
    source_type: Literal["TXT", "DOCX"] = Field(alias="sourceType")
    job_family: Literal["JAVA_BACKEND"] = Field(alias="jobFamily")
    document: Document
    allowed_evidence: list[AllowedEvidence] = Field(alias="allowedEvidence", min_length=1)
    job_description_text: str = Field(alias="jobDescriptionText", min_length=20, max_length=20000)
    redaction_required: Literal[True] = Field(alias="redactionRequired")
    callback_url: str = Field(alias="callbackUrl", min_length=1, max_length=2048)
    callback_token: str = Field(alias="callbackToken", min_length=32, max_length=1024)
    provider: Provider
    correlation_id: UUID = Field(alias="correlationId")

    @field_validator("callback_url")
    @classmethod
    def callback_uri(cls, value: str) -> str:
        try:
            parsed = urlsplit(value)
            hostname = parsed.hostname
            port = parsed.port
        except (ValueError, UnicodeError) as exc:
            raise ValueError("callbackUrl must be a URI") from exc
        if parsed.scheme not in {"http", "https"} or not parsed.netloc or not hostname or parsed.username or parsed.password:
            raise ValueError("callbackUrl must be a URI")
        if port is not None and not 1 <= port <= 65535:
            raise ValueError("callbackUrl must be a URI")
        return value


class EvidenceReference(StrictModel):
    evidence_id: UUID = Field(alias="evidenceId")
    source_start: StrictInt = Field(alias="sourceStart", ge=0)
    source_end: StrictInt = Field(alias="sourceEnd", ge=0)
    excerpt: str = Field(min_length=1, max_length=5000)
    confidence: StrictFloat = Field(ge=0, le=1)

    @model_validator(mode="after")
    def non_empty_range(self) -> "EvidenceReference":
        if self.source_start >= self.source_end:
            raise ValueError("evidence range must be non-empty")
        return self


class RequirementMatch(StrictModel):
    requirement_id: UUID = Field(alias="requirementId")
    job_requirement_text: str = Field(alias="jobRequirementText", min_length=1, max_length=20000)
    requirement_type: Literal["MANDATORY", "PREFERRED"] = Field(alias="requirementType")
    match_status: Literal["SATISFIED", "PARTIALLY_SATISFIED", "RELATED_BUT_EVIDENCE_INSUFFICIENT", "UNMET"] = Field(alias="matchStatus")
    match_type: Literal["EXACT", "SEMANTIC", "RELATED", "NO_MATCH"] = Field(alias="matchType")
    component: Literal["SKILLS", "PROJECT_EXPERIENCE", "WORK_CONTENT", "EDUCATION_EXPERIENCE", "SOFT_SKILLS"]
    component_score: StrictFloat = Field(alias="componentScore", ge=0, le=1)
    evidence: list[EvidenceReference]
    evidence_strength: Literal["NONE", "LOW", "MEDIUM", "HIGH"] = Field(alias="evidenceStrength")
    gap: str | None = Field(max_length=5000)
    suggestion_state: Literal["SUPPORTED_FACT", "WORDING_ONLY_REWRITE", "NEEDS_USER_CONFIRMATION", "RISKY_OR_UNSUPPORTED"] = Field(alias="suggestionState")

    @model_validator(mode="after")
    def evidence_requirements(self) -> "RequirementMatch":
        if self.match_status in {"SATISFIED", "PARTIALLY_SATISFIED"} and not self.evidence:
            raise ValueError("matched requirements require evidence")
        if self.suggestion_state in {"SUPPORTED_FACT", "WORDING_ONLY_REWRITE"} and not self.evidence:
            raise ValueError("supported suggestions require evidence")
        return self


class Suggestion(StrictModel):
    suggestion_id: UUID = Field(alias="suggestionId")
    requirement_id: UUID = Field(alias="requirementId")
    state: Literal["SUPPORTED_FACT", "WORDING_ONLY_REWRITE", "NEEDS_USER_CONFIRMATION", "RISKY_OR_UNSUPPORTED"]
    proposed_text: str = Field(alias="proposedText", min_length=1, max_length=5000)
    evidence_ids: list[UUID] = Field(alias="evidenceIds")

    @model_validator(mode="after")
    def supported_needs_evidence(self) -> "Suggestion":
        if self.state in {"SUPPORTED_FACT", "WORDING_ONLY_REWRITE"} and not self.evidence_ids:
            raise ValueError("supported suggestions require evidence")
        return self


class ScoreBreakdown(StrictModel):
    skills: StrictFloat = Field(ge=0, le=1)
    project_experience: StrictFloat = Field(alias="projectExperience", ge=0, le=1)
    work_content: StrictFloat = Field(alias="workContent", ge=0, le=1)
    education_experience: StrictFloat = Field(alias="educationExperience", ge=0, le=1)
    soft_skills: StrictFloat = Field(alias="softSkills", ge=0, le=1)
    composite: StrictFloat = Field(ge=0, le=1)


class AnalysisResult(StrictModel):
    score: ScoreBreakdown
    requirements: list[RequirementMatch]
    suggestions: list[Suggestion]


class Callback(StrictModel):
    task_id: UUID = Field(alias="taskId")
    attempt: StrictInt = Field(ge=1)
    callback_id: UUID = Field(alias="callbackId")
    callback_token: str = Field(alias="callbackToken", min_length=32, max_length=1024)
    payload_hash: str = Field(alias="payloadHash", pattern=r"^[a-f0-9]{64}$")
    outcome: Literal["SUCCEEDED", "FAILED", "TIMED_OUT"]
    result: AnalysisResult | None = None
    error_code: Literal["MODEL_UNAVAILABLE", "MODEL_OUTPUT_INVALID", "MODEL_ENDPOINT_REJECTED", "UNSUPPORTED_FILE"] | None = Field(default=None, alias="errorCode")
    correlation_id: UUID = Field(alias="correlationId")

    @model_validator(mode="after")
    def outcome_fields(self) -> "Callback":
        if self.outcome == "SUCCEEDED" and (self.result is None or self.error_code is not None):
            raise ValueError("successful callback requires result only")
        if self.outcome != "SUCCEEDED" and (self.error_code is None or self.result is not None):
            raise ValueError("failed callback requires errorCode only")
        return self


class ExtractedEvidence(StrictModel):
    evidence_id: UUID | None = Field(default=None, alias="evidenceId")
    source_location: str = Field(alias="sourceLocation")
    source_start: StrictInt = Field(alias="sourceStart", ge=0)
    source_end: StrictInt = Field(alias="sourceEnd", ge=0)
    excerpt: str


class AnalysisRequest(StrictModel):
    resume_text: str = Field(alias="resumeText")
    job_description_text: str = Field(alias="jobDescriptionText")
    # The first released family is Java backend; keep a default so direct
    # provider integrations that predate the internal job envelope remain
    # source-compatible while the Java worker always supplies the field.
    job_family: Literal["JAVA_BACKEND"] = Field(default="JAVA_BACKEND", alias="jobFamily")
    evidence: list[ExtractedEvidence] = Field(default_factory=list)
