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
