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
