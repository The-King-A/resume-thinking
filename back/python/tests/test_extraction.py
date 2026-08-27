from io import BytesIO

import pytest
from docx import Document

from app.extraction import UnsupportedFile, extract_resume


def test_txt_offsets_are_stable():
    result = extract_resume("TXT", "alpha\nbeta".encode())
    assert result.text == "alpha\nbeta"
    assert (result.evidence[1]["sourceStart"], result.evidence[1]["sourceEnd"]) == (6, 10)


def test_docx_paragraph_offsets_are_stable():
    document = Document()
    document.add_paragraph("alpha")
    document.add_paragraph("beta")
    stream = BytesIO()
    document.save(stream)
    result = extract_resume("DOCX", stream.getvalue())
    assert result.text == "alpha\nbeta"
    assert result.evidence[1]["sourceStart"] == 6


def test_other_types_are_unsupported():
    with pytest.raises(UnsupportedFile):
        extract_resume("PDF", b"%PDF")
