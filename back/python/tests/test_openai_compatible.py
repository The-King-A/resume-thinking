import pytest

from app.openai_compatible import ModelOutputInvalid


def test_invalid_model_json_error_type_is_exposed():
    assert ModelOutputInvalid.code == "MODEL_OUTPUT_INVALID"
