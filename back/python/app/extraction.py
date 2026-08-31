from __future__ import annotations

from io import BytesIO
from dataclasses import dataclass

from docx import Document as DocxDocument


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
            paragraphs = [p.text for p in document.paragraphs]
        except Exception as exc:
            raise UnsupportedFile("invalid DOCX") from exc
        text = "\n".join(paragraphs)
        return _result(text, "paragraph")
    raise UnsupportedFile(f"unsupported source type: {source_type}")


def _result(text: str, location: str) -> ExtractedResume:
    evidence = []
    offset = 0
    for index, paragraph in enumerate(text.split("\n")):
        end = offset + len(paragraph)
        if paragraph:
            # Extracted evidence is transient.  Java owns persisted evidence
            # identifiers; these local labels merely satisfy the v2 shape and
            # make provider-facing diagnostics deterministic.
            evidence.append(Evidence(evidenceId=f"evidence{len(evidence) + 1:03d}", sourceLocation=f"{location}:{index}", sourceStart=offset, sourceEnd=end, excerpt=paragraph))
        offset = end + 1
    return ExtractedResume(text, tuple(evidence))
