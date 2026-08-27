from app.redaction import redact_text


def test_redaction_removes_email_phone_and_identity_number():
    result = redact_text("Li Ming, li@example.test, 13800138000, 110101199001011234")
    assert "li@example.test" not in result.redacted_text
    assert "13800138000" not in result.redacted_text
    assert "110101199001011234" not in result.redacted_text


def test_redaction_exposes_replacement_offsets():
    result = redact_text("mail a@example.test")
    assert result.replacements[0].kind == "email"
    assert result.replacements[0].start == 5


def test_redaction_removes_labeled_chinese_address():
    result = redact_text("\u5730\u5740\uff1a\u5317\u4eac\u5e02\u671d\u9633\u533a\u671b\u4eac\u8857\u90538\u53f7")
    assert "\u5317\u4eac\u5e02\u671d\u9633\u533a" not in result.redacted_text


def test_redaction_keeps_ascii_period_after_labeled_address():
    result = redact_text("\u5730\u5740\uff1a\u5317\u4eac\u5e02\u671d\u9633\u533a\u671b\u4eac\u8857\u90538\u53f7.")
    assert "\u5317\u4eac\u5e02\u671d\u9633\u533a" not in result.redacted_text
    assert result.redacted_text.endswith(".")


def test_redaction_removes_explicit_name_fields_but_keeps_prose():
    text = (
        "\u59d3\u540d\uff1a\u5f20\u4e09\n"
        "Name: Alice Zhang\n"
        "Project name: Resume Matcher\n"
        "The name: Bob"
    )

    result = redact_text(text)

    assert "\u5f20\u4e09" not in result.redacted_text
    assert "Alice Zhang" not in result.redacted_text
    assert "Project name: Resume Matcher" in result.redacted_text
    assert "The name: Bob" in result.redacted_text
    name_replacements = [item for item in result.replacements if item.kind == "name"]
    assert len(name_replacements) == 2
    assert name_replacements[0].start == text.index("\u5f20\u4e09")
    assert name_replacements[0].end == name_replacements[0].start + 2
