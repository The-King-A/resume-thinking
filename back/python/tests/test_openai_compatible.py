import pytest
import httpx

from app.openai_compatible import ModelOutputInvalid, OpenAICompatibleClient


def test_invalid_model_json_error_type_is_exposed():
    assert ModelOutputInvalid.code == "MODEL_OUTPUT_INVALID"


@pytest.mark.asyncio
async def test_malformed_provider_json_raises_model_output_invalid():
    async def handler(_request):
        return httpx.Response(200, json={"choices": [{"message": {"content": "{"}}]})

    client = OpenAICompatibleClient(
        {"baseUrl": "http://127.0.0.1:8080", "model": "m", "apiKey": "k"},
        transport=httpx.MockTransport(handler),
    )
    with pytest.raises(ModelOutputInvalid):
        await client.complete_structured({"resumeText": "x", "jobDescriptionText": "y", "evidence": []})


@pytest.mark.asyncio
async def test_provider_request_redacts_all_text_fields():
    seen = {}

    async def handler(request):
        seen["body"] = request.content.decode("utf-8")
        return httpx.Response(200, json={"choices": [{"message": {"content": "{\"score\":{\"skills\":0,\"projectExperience\":0,\"workContent\":0,\"educationExperience\":0,\"softSkills\":0,\"composite\":0},\"requirements\":[],\"suggestions\":[]}"}}]})

    client = OpenAICompatibleClient(
        {"baseUrl": "http://127.0.0.1:8080", "model": "m", "apiKey": "sk-live-secret-123"},
        blocked_secrets=("CALLBACK_TOKEN",),
        transport=httpx.MockTransport(handler),
    )
    await client.complete_structured({
        "resumeText": "alice@example.com 13800138000",
        "jobDescriptionText": "Build reliable software with clear communication. CALLBACK_TOKEN",
        "evidence": [{"evidenceId": "00000000-0000-0000-0000-000000000001", "sourceLocation": "txt:0", "sourceStart": 0, "sourceEnd": 5, "excerpt": "110101199001011234"}],
    })
    assert "alice@example.com" not in seen["body"]
    assert "13800138000" not in seen["body"]
    assert "110101199001011234" not in seen["body"]
    assert "sk-live-secret-123" not in seen["body"]
    assert "CALLBACK_TOKEN" not in seen["body"]
