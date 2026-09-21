import json
from hashlib import sha256
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
FIXTURES = ROOT / "contracts" / "fixtures" / "v4" / "interview"


def load(name: str) -> dict:
    return json.loads((FIXTURES / name).read_text(encoding="utf-8"))


def canonical(value):
    if value is None or isinstance(value, (str, bool)):
        return json.dumps(value, ensure_ascii=False, separators=(",", ":"))
    if isinstance(value, (int, float)):
        return json.dumps(value, ensure_ascii=False, separators=(",", ":"))
    if isinstance(value, list):
        return "[" + ",".join(canonical(item) for item in value) + "]"
    return "{" + ",".join(json.dumps(key, ensure_ascii=False) + ":" + canonical(value[key]) for key in sorted(value)) + "}"


def test_interview_fixtures_define_one_round_state_flow_without_secrets():
    create = load("session-create-valid.json")
    session = load("session-valid.json")
    questions = load("questions-valid.json")
    feedback = load("feedback-valid.json")
    deleted = load("callback-after-delete.json")

    assert create == {"matchTaskId": "task001", "idempotencyKey": "interview-create-key-0001"}
    assert session["state"] == "WAITING_FOR_ANSWER"
    assert {item["questionType"] for item in questions["questions"]} == {
        "BASIC_CONFIRMATION", "PROJECT_DEEP_DIVE", "JOB_SCENARIO", "SYNTHESIS_FOLLOW_UP"
    }
    assert feedback["state"] == "FEEDBACK_READY"
    assert all(claim["applied"] is False for claim in feedback["claims"])
    assert deleted["errorCode"] == "INTERVIEW_SESSION_GONE"


def test_interview_callback_hashes_and_fixtures_do_not_contain_usable_secrets():
    for name in ["callback-questions-valid.json", "callback-feedback-valid.json", "callback-duplicate.json", "callback-timeout.json", "callback-stale.json", "callback-after-delete.json"]:
        callback = load(name)
        received_hash = callback.pop("payloadHash")
        assert sha256(canonical(callback).encode("utf-8")).hexdigest() == received_hash

    forbidden = ("contentBase64", "@", "[REDACTED_PHONE]", "sk-live-", "sk-proj-")
    for path in FIXTURES.glob("*.json"):
        text = path.read_text(encoding="utf-8")
        assert all(value not in text for value in forbidden), path.name
        if "callbackToken" in text:
            assert "placeholder-callback-token" in text, path.name
        if "apiKey" in text:
            assert "fixture-api-key" in text, path.name
