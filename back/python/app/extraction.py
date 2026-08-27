from __future__ import annotations

from io import BytesIO
from dataclasses import dataclass
from uuid import uuid4
from zipfile import BadZipFile

from docx import Document as DocxDocument
from docx.opc.exceptions import PackageNotFoundError


class UnsupportedFile(Exception):
    code = "UNSUPPORTED_FILE"


@dataclass(frozen=True)
class ExtractedResume:
    text: str
    evidence: tuple[dict, ...]


class Evidence(dict):
    """Mapping with snake_case attribute access for convenient callers."""

    def __getattr__(self, name: str):
        aliases = {"evidence_id": "evidenceId", "source_location": "sourceLocation", "source_start": "sourceStart", "source_end": "sourceEnd"}
        try:
            return self[aliases.get(name, name)]
        except KeyError as exc:
            raise AttributeError(name) from exc


def extract_resume(source_type: str, content: bytes) -> ExtractedResume:
    if source_type == "TXT":
        text = content.decode("utf-8", errors="replace")
        return _result(text, "txt")
    if source_type == "DOCX":
        try:
            document = DocxDocument(BytesIO(content))
        except (BadZipFile, PackageNotFoundError, ValueError) as exc:
            raise UnsupportedFile("invalid DOCX") from exc
        paragraphs = [p.text for p in document.paragraphs]
        text = "\n".join(paragraphs)
        return _result(text, "paragraph")
    raise UnsupportedFile(f"unsupported source type: {source_type}")


def _result(text: str, location: str) -> ExtractedResume:
    evidence = []
    offset = 0
    for index, paragraph in enumerate(text.split("\n")):
        end = offset + len(paragraph)
        if paragraph:
            evidence.append(Evidence(evidenceId=str(uuid4()), sourceLocation=f"{location}:{index}", sourceStart=offset, sourceEnd=end, excerpt=paragraph))
        offset = end + 1
    return ExtractedResume(text, tuple(evidence))
