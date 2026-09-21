from __future__ import annotations

from io import BytesIO
from dataclasses import dataclass

from docx import Document as DocxDocument
from docx.oxml.ns import qn


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
            paragraphs = _docx_paragraphs(document)
        except Exception as exc:
            raise UnsupportedFile("invalid DOCX") from exc
        return _docx_result(paragraphs)
    raise UnsupportedFile(f"unsupported source type: {source_type}")


def _docx_paragraphs(document: DocxDocument) -> list[str]:
    """Match Java's document.xml traversal, including paragraphs in tables."""
    paragraphs: list[str] = []
    paragraph_tag = qn("w:p")
    text_tags = {qn("w:t"), qn("w:delText")}
    tab_tag = qn("w:tab")
    break_tags = {qn("w:br"), qn("w:cr")}
    for paragraph in document.element.body.iter():
        if paragraph.tag != paragraph_tag:
            continue
        fragments: list[str] = []
        for node in paragraph.iter():
            if node.tag in text_tags:
                fragments.append(node.text or "")
            elif node.tag == tab_tag:
                fragments.append("\t")
            elif node.tag in break_tags:
                fragments.append("\n")
        paragraphs.append("".join(fragments))
    return paragraphs


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


def _docx_result(paragraphs: list[str]) -> ExtractedResume:
    """Preserve Java's physical ``w:p`` evidence numbering and offsets."""
    text = "\n".join(paragraphs)
    evidence = []
    offset = 0
    for index, paragraph in enumerate(paragraphs):
        end = offset + len(paragraph)
        if paragraph:
            evidence.append(
                Evidence(
                    evidenceId=f"evidence{len(evidence) + 1:03d}",
                    sourceLocation=f"paragraph:{index}",
                    sourceStart=offset,
                    sourceEnd=end,
                    excerpt=paragraph,
                )
            )
        offset = end + 1
    return ExtractedResume(text, tuple(evidence))
