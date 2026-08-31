import json
from pathlib import Path

from app.models import Callback


def test_frozen_callback_fixture_validates():
    path = Path(__file__).resolve().parents[3] / "contracts" / "fixtures" / "v2" / "callback-valid.json"
    assert Callback.model_validate(json.loads(path.read_text(encoding="utf-8"))).outcome == "FAILED"
