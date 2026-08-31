import json
import pytest
import httpx

from app.openai_compatible import ModelEndpointRejected, ModelOutputInvalid, OpenAICompatibleClient


def test_invalid_model_json_error_type_is_exposed():
    assert ModelOutputInvalid.code == "MODEL_OUTPUT_INVALID"


@pytest.mark.asyncio
async def test_provider_endpoint_is_revalidated_before_request(monkeypatch):
    validations = 0
    requests = 0

    def validate(_url):
        nonlocal validations
        validations += 1
        if validations == 2:
            raise ModelEndpointRejected("provider endpoint rejected")

    async def handler(_request):
        nonlocal requests
        requests += 1
        return httpx.Response(
            200,
            json={
                "choices": [
                    {
                        "message": {
                            "content": {
                                "score": {
                                    "skills": 0,
                                    "projectExperience": 0,
                                    "workContent": 0,
                                    "educationExperience": 0,
                                    "softSkills": 0,
                                    "composite": 0,
                                },
                                "requirements": [],
                                "suggestions": [],
                            }
                        }
                    }
                ]
            },
        )

    monkeypatch.setattr(OpenAICompatibleClient, "_validate_endpoint", staticmethod(validate))
    client = OpenAICompatibleClient(
        {"baseUrl": "http://127.0.0.1:8080", "model": "m", "apiKey": "k"},
        transport=httpx.MockTransport(handler),
    )

    with pytest.raises(ModelEndpointRejected):
        await client.complete_structured({"resumeText": "x", "jobDescriptionText": "y", "evidence": []})

    assert validations == 2
    assert requests == 0


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
        "evidence": [{"evidenceId": "evidence001", "sourceLocation": "txt:0", "sourceStart": 0, "sourceEnd": 5, "excerpt": "110101199001011234"}],
    })
    assert "alice@example.com" not in seen["body"]
    assert "13800138000" not in seen["body"]
    assert "110101199001011234" not in seen["body"]
    assert "sk-live-secret-123" not in seen["body"]
    assert "CALLBACK_TOKEN" not in seen["body"]


@pytest.mark.asyncio
async def test_provider_request_redacts_labeled_names():
    seen = {}

    async def handler(request):
        seen["body"] = request.content.decode("utf-8")
        return httpx.Response(
            200,
            json={
                "choices": [
                    {
                        "message": {
                            "content": "{\"score\":{\"skills\":0,\"projectExperience\":0,\"workContent\":0,\"educationExperience\":0,\"softSkills\":0,\"composite\":0},\"requirements\":[],\"suggestions\":[]}"
                        }
                    }
                ]
            },
        )

    client = OpenAICompatibleClient(
        {"baseUrl": "http://127.0.0.1:8080", "model": "m", "apiKey": "k"},
        transport=httpx.MockTransport(handler),
    )
    await client.complete_structured(
        {
            "resumeText": "\u59d3\u540d\uff1a\u5f20\u4e09\nName: Alice Zhang\nProject name: Resume Matcher",
            "jobDescriptionText": "Build reliable software with clear communication.",
            "evidence": [],
        }
    )

    content = json.loads(seen["body"])["messages"][0]["content"]
    assert "\u5f20\u4e09" not in content
    assert "Alice Zhang" not in content
    assert "Project name: Resume Matcher" in content


@pytest.mark.asyncio
async def test_provider_result_redacts_pii_before_returning_to_java():
    async def handler(_request):
        return httpx.Response(
            200,
            json={
                "choices": [
                    {
                        "message": {
                            "content": json.dumps(
                                {
                                    "score": {
                                        "skills": 0,
                                        "projectExperience": 0,
                                        "workContent": 0,
                                        "educationExperience": 0,
                                        "softSkills": 0,
                                        "composite": 0,
                                    },
                                    "requirements": [
                                        {
                                            "requirementId": "requirement001",
                                            "jobRequirementText": "\u59d3\u540d\uff1a\u5f20\u4e09\uff0c\u90ae\u7bb1 alice@example.com",
                                            "requirementType": "MANDATORY",
                                            "matchStatus": "UNMET",
                                            "matchType": "NO_MATCH",
                                            "component": "SKILLS",
                                            "componentScore": 0,
                                            "evidence": [],
                                            "evidenceStrength": "NONE",
                                            "gap": "联系 alice@example.com",
                                            "suggestionState": "RISKY_OR_UNSUPPORTED",
                                        }
                                    ],
                                    "suggestions": [
                                        {
                                            "suggestionId": "suggestion001",
                                            "requirementId": "requirement001",
                                            "state": "NEEDS_USER_CONFIRMATION",
                                            "proposedText": "Name: Alice Zhang",
                                            "evidenceIds": [],
                                        }
                                    ],
                                }
                            )
                        }
                    }
                ]
            },
        )

    client = OpenAICompatibleClient(
        {"baseUrl": "http://127.0.0.1:8080", "model": "m", "apiKey": "k"},
        transport=httpx.MockTransport(handler),
    )

    result = await client.complete_structured({
        "resumeText": "Java",
        "jobDescriptionText": "Build reliable software with clear communication.",
        "evidence": [],
    })

    serialized = json.dumps(result.model_dump(by_alias=True, mode="json"), ensure_ascii=False)
    assert "alice@example.com" not in serialized
    assert "张三" not in serialized
    assert "Alice Zhang" not in serialized
    assert "[REDACTED_EMAIL]" in serialized
    assert "[REDACTED_NAME]" in serialized


@pytest.mark.asyncio
async def test_provider_request_redacts_labeled_address_before_punctuation():
    seen = {}

    async def handler(request):
        seen["body"] = request.content.decode("utf-8")
        return httpx.Response(200, json={"choices": [{"message": {"content": "{\"score\":{\"skills\":0,\"projectExperience\":0,\"workContent\":0,\"educationExperience\":0,\"softSkills\":0,\"composite\":0},\"requirements\":[],\"suggestions\":[]}"}}]})

    client = OpenAICompatibleClient(
        {"baseUrl": "http://127.0.0.1:8080", "model": "m", "apiKey": "sk-live-secret-123"},
        transport=httpx.MockTransport(handler),
    )
    address = "\u5730\u5740\uff1a\u5317\u4eac\u5e02\u671d\u9633\u533a\u671b\u4eac\u8857\u90538\u53f7\uff0c\u7535\u8bdd\uff1a13800138000\u3002"
    await client.complete_structured({
        "resumeText": address,
        "jobDescriptionText": "Build reliable software with clear communication.",
        "evidence": [],
    })
    assert address not in seen["body"]
    assert "\u5317\u4eac\u5e02\u671d\u9633\u533a" not in seen["body"]
    assert "\uff0c\u7535\u8bdd\uff1a" in seen["body"]
    assert "13800138000" not in seen["body"]


@pytest.mark.asyncio
async def test_provider_request_redacts_address_before_ascii_period():
    seen = {}

    async def handler(request):
        seen["body"] = request.content.decode("utf-8")
        return httpx.Response(200, json={"choices": [{"message": {"content": "{\"score\":{\"skills\":0,\"projectExperience\":0,\"workContent\":0,\"educationExperience\":0,\"softSkills\":0,\"composite\":0},\"requirements\":[],\"suggestions\":[]}"}}]})

    client = OpenAICompatibleClient(
        {"baseUrl": "http://127.0.0.1:8080", "model": "m", "apiKey": "sk-live-secret-123"},
        transport=httpx.MockTransport(handler),
    )
    address = "\u5730\u5740\uff1a\u5317\u4eac\u5e02\u671d\u9633\u533a\u671b\u4eac\u8857\u90538\u53f7."
    await client.complete_structured({
        "resumeText": address,
        "jobDescriptionText": "Build reliable software with clear communication.",
        "evidence": [],
    })
    assert address not in seen["body"]
    assert "\u5317\u4eac\u5e02\u671d\u9633\u533a" not in seen["body"]
    content = json.loads(seen["body"])["messages"][0]["content"]
    assert json.loads(content)["resumeText"].endswith(".")
