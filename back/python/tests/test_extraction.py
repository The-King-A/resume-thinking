from io import BytesIO
from zipfile import ZipFile

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


def test_malformed_docx_is_unsupported():
    with pytest.raises(UnsupportedFile):
        extract_resume("DOCX", b"not a docx package")


def test_incomplete_docx_package_is_unsupported():
    stream = BytesIO()
    with ZipFile(stream, "w") as package:
        package.writestr("word/document.xml", b"<broken>")
    with pytest.raises(UnsupportedFile):
        extract_resume("DOCX", stream.getvalue())
