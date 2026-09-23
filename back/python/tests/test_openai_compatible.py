import json
import logging
import pytest
import httpx

from app.openai_compatible import ModelEndpointRejected, ModelOutputInvalid, ModelUnavailable, OpenAICompatibleClient
from app.interview_models import InterviewFeedbackPayload
from app.settings import settings


def test_invalid_model_json_error_type_is_exposed():
    assert ModelOutputInvalid.code == "MODEL_OUTPUT_INVALID"


@pytest.mark.asyncio
async def test_provider_request_separates_structured_instruction_from_analysis_request():
    seen = {}

    async def handler(request):
        seen["payload"] = json.loads(request.content)
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
                                    "requirements": [],
                                    "suggestions": [],
                                }
                            )
                        }
                    }
                ]
            },
        )

    request_payload = {
        "resumeText": "Experienced Java developer",
        "jobDescriptionText": "JD",
        "evidence": [
            {
                "evidenceId": "evidence001",
                "sourceLocation": "txt:0",
                "sourceStart": 0,
                "sourceEnd": 9,
                "excerpt": "Experienced",
            }
        ],
    }
    client = OpenAICompatibleClient(
        {"baseUrl": "http://127.0.0.1:8080", "model": "m", "apiKey": "sk-test-secret-123"},
        transport=httpx.MockTransport(handler),
    )

    await client.complete_structured(request_payload)

    messages = seen["payload"]["messages"]
    assert [message["role"] for message in messages] == ["system", "user"]
    assert json.loads(messages[1]["content"]) == {**request_payload, "jobFamily": "JAVA_BACKEND"}
    assert "AnalysisResult" in messages[0]["content"]
    assert "0.40" in messages[0]["content"]
    assert "evidenceId" in messages[0]["content"]
    assert seen["payload"]["response_format"] == {"type": "json_object"}
    assert seen["payload"]["max_tokens"] == min(settings.model_max_tokens, 8192)
    assert seen["payload"]["temperature"] == 0
    system_prompt = seen["payload"]["messages"][0]["content"]
    assert "json" in system_prompt.lower()
    assert '"score":{"skills":0' in system_prompt.replace(" ", "")
    assert "untrusted data" in system_prompt.lower()


@pytest.mark.asyncio
async def test_deepseek_flash_auto_disables_thinking_and_keeps_configured_budget(monkeypatch):
    seen = {}

    async def handler(request):
        seen["payload"] = json.loads(request.content)
        return httpx.Response(
            200,
            json={
                "choices": [
                    {
                        "finish_reason": "stop",
                        "message": {
                            "content": "{\"score\":{\"skills\":0,\"projectExperience\":0,\"workContent\":0,\"educationExperience\":0,\"softSkills\":0,\"composite\":0},\"requirements\":[],\"suggestions\":[]}"
                        },
                    }
                ]
            },
        )

    monkeypatch.setattr(OpenAICompatibleClient, "_validate_endpoint", staticmethod(lambda _url: None))
    monkeypatch.setattr(settings, "model_max_tokens", 100000)
    monkeypatch.setattr(settings, "model_thinking", "auto")
    client = OpenAICompatibleClient(
        {"baseUrl": "https://api.deepseek.com", "model": "deepseek-flash", "apiKey": "k"},
        transport=httpx.MockTransport(handler),
    )

    await client.complete_structured({"resumeText": "x", "jobDescriptionText": "y", "evidence": []})

    assert seen["payload"]["thinking"] == {"type": "disabled"}
    assert seen["payload"]["max_tokens"] == 100000
    assert seen["payload"]["temperature"] == 0


@pytest.mark.asyncio
@pytest.mark.parametrize("model", ["deepseek-v4-flash"])
async def test_deepseek_v4_auto_uses_explicit_non_reasoning_structured_request(monkeypatch, model):
    seen = {}

    async def handler(request):
        seen["payload"] = json.loads(request.content)
        return httpx.Response(
            200,
            json={
                "choices": [
                    {
                        "finish_reason": "stop",
                        "message": {
                            "content": "{\"score\":{\"skills\":0,\"projectExperience\":0,\"workContent\":0,\"educationExperience\":0,\"softSkills\":0,\"composite\":0},\"requirements\":[],\"suggestions\":[]}"
                        },
                    }
                ]
            },
        )

    monkeypatch.setattr(OpenAICompatibleClient, "_validate_endpoint", staticmethod(lambda _url: None))
    monkeypatch.setattr(settings, "model_max_tokens", 100000)
    monkeypatch.setattr(settings, "model_thinking", "auto")
    client = OpenAICompatibleClient(
        {"baseUrl": "https://api.deepseek.com", "model": model, "apiKey": "k"},
        transport=httpx.MockTransport(handler),
    )

    await client.complete_structured({"resumeText": "x", "jobDescriptionText": "y", "evidence": []})

    assert seen["payload"]["model"] == model
    assert seen["payload"]["thinking"] == {"type": "disabled"}
    assert seen["payload"]["max_tokens"] == 100000
    assert seen["payload"]["temperature"] == 0


@pytest.mark.asyncio
async def test_deepseek_v4_pro_auto_uses_reasoning_mode_for_structured_requests(monkeypatch):
    seen = {}

    async def handler(request):
        seen["payload"] = json.loads(request.content)
        return httpx.Response(
            200,
            json={
                "choices": [{
                    "finish_reason": "stop",
                    "message": {
                        "content": "{\"score\":{\"skills\":0,\"projectExperience\":0,\"workContent\":0,\"educationExperience\":0,\"softSkills\":0,\"composite\":0},\"requirements\":[],\"suggestions\":[]}"
                    },
                }],
            },
        )

    monkeypatch.setattr(OpenAICompatibleClient, "_validate_endpoint", staticmethod(lambda _url: None))
    monkeypatch.setattr(settings, "model_max_tokens", 100000)
    monkeypatch.setattr(settings, "model_thinking", "auto")
    client = OpenAICompatibleClient(
        {"baseUrl": "https://api.deepseek.com", "model": "deepseek-v4-pro", "apiKey": "k"},
        transport=httpx.MockTransport(handler),
    )

    await client.complete_structured({"resumeText": "x", "jobDescriptionText": "y", "evidence": []})

    assert seen["payload"]["model"] == "deepseek-v4-pro"
    assert seen["payload"]["thinking"] == {"type": "enabled"}
    assert seen["payload"]["reasoning_effort"] == "high"
    assert "temperature" not in seen["payload"]


@pytest.mark.asyncio
async def test_deepseek_v4_auto_disables_thinking_for_trimmed_model_name(monkeypatch):
    seen = {}

    async def handler(request):
        seen["payload"] = json.loads(request.content)
        return httpx.Response(
            200,
            json={
                "choices": [
                    {
                        "finish_reason": "stop",
                        "message": {
                            "content": "{\"score\":{\"skills\":0,\"projectExperience\":0,\"workContent\":0,\"educationExperience\":0,\"softSkills\":0,\"composite\":0},\"requirements\":[],\"suggestions\":[]}"
                        },
                    }
                ]
            },
        )

    monkeypatch.setattr(settings, "model_thinking", "auto")
    client = OpenAICompatibleClient(
        {"baseUrl": "https://api.deepseek.com", "model": "  DEEPSEEK-V4-FLASH  ", "apiKey": "k"},
        transport=httpx.MockTransport(handler),
    )

    await client.complete_structured({"resumeText": "x", "jobDescriptionText": "y", "evidence": []})

    assert seen["payload"]["thinking"] == {"type": "disabled"}
    assert seen["payload"]["temperature"] == 0


@pytest.mark.asyncio
async def test_provider_logs_model_identity_and_usage_without_model_input(caplog, monkeypatch):
    seen = {}

    async def handler(request):
        seen["payload"] = json.loads(request.content)
        return httpx.Response(
            200,
            json={
                "model": "deepseek-v4-pro",
                "choices": [{
                    "finish_reason": "stop",
                    "message": {
                        "content": "{\"score\":{\"skills\":0,\"projectExperience\":0,\"workContent\":0,\"educationExperience\":0,\"softSkills\":0,\"composite\":0},\"requirements\":[],\"suggestions\":[]}",
                    },
                }],
                "usage": {
                    "prompt_tokens": 12,
                    "completion_tokens": 34,
                    "total_tokens": 46,
                    "completion_tokens_details": {"reasoning_tokens": 0},
                },
            },
        )

    caplog.set_level(logging.INFO, logger="app.openai_compatible")
    monkeypatch.setattr(OpenAICompatibleClient, "_validate_endpoint", staticmethod(lambda _url: None))
    client = OpenAICompatibleClient(
        {"baseUrl": "https://api.deepseek.com", "model": "deepseek-v4-pro", "apiKey": "secret-key"},
        transport=httpx.MockTransport(handler),
    )

    await client.complete_structured({"resumeText": "private resume text", "jobDescriptionText": "y", "evidence": []})

    messages = "\n".join(record.getMessage() for record in caplog.records)
    assert "request_model=deepseek-v4-pro" in messages
    assert "response_model=deepseek-v4-pro" in messages
    assert "finish_reason=stop" in messages
    assert "total_tokens=46" in messages
    assert "secret-key" not in messages
    assert "private resume text" not in messages


@pytest.mark.asyncio
async def test_deepseek_reasoner_auto_omits_temperature(monkeypatch):
    seen = {}

    async def handler(request):
        seen["payload"] = json.loads(request.content)
        return httpx.Response(
            200,
            json={
                "choices": [
                    {
                        "finish_reason": "stop",
                        "message": {
                            "content": "{\"score\":{\"skills\":0,\"projectExperience\":0,\"workContent\":0,\"educationExperience\":0,\"softSkills\":0,\"composite\":0},\"requirements\":[],\"suggestions\":[]}"
                        },
                    }
                ]
            },
        )

    monkeypatch.setattr(OpenAICompatibleClient, "_validate_endpoint", staticmethod(lambda _url: None))
    monkeypatch.setattr(settings, "model_thinking", "auto")
    client = OpenAICompatibleClient(
        {"baseUrl": "https://api.deepseek.com/v1", "model": " DeepSeek-Reasoner ", "apiKey": "k"},
        transport=httpx.MockTransport(handler),
    )

    await client.complete_structured({"resumeText": "x", "jobDescriptionText": "y", "evidence": []})

    assert "thinking" not in seen["payload"]
    assert "temperature" not in seen["payload"]


@pytest.mark.asyncio
async def test_non_deepseek_provider_caps_max_tokens_for_compatibility(monkeypatch):
    seen = {}

    async def handler(request):
        seen["payload"] = json.loads(request.content)
        return httpx.Response(
            200,
            json={
                "choices": [
                    {
                        "finish_reason": "stop",
                        "message": {
                            "content": "{\"score\":{\"skills\":0,\"projectExperience\":0,\"workContent\":0,\"educationExperience\":0,\"softSkills\":0,\"composite\":0},\"requirements\":[],\"suggestions\":[]}"
                        },
                    }
                ]
            },
        )

    monkeypatch.setattr(settings, "model_max_tokens", 100000)
    client = OpenAICompatibleClient(
        {"baseUrl": "http://127.0.0.1:8080", "model": "gpt-compatible", "apiKey": "k"},
        transport=httpx.MockTransport(handler),
    )

    await client.complete_structured({"resumeText": "x", "jobDescriptionText": "y", "evidence": []})

    assert seen["payload"]["max_tokens"] == 8192


@pytest.mark.asyncio
async def test_deepseek_v4_auto_keeps_configured_structured_budget(monkeypatch):
    seen = {}

    async def handler(request):
        seen["payload"] = json.loads(request.content)
        return httpx.Response(
            200,
            json={
                "choices": [
                    {
                        "finish_reason": "stop",
                        "message": {
                            "content": "{\"score\":{\"skills\":0,\"projectExperience\":0,\"workContent\":0,\"educationExperience\":0,\"softSkills\":0,\"composite\":0},\"requirements\":[],\"suggestions\":[]}"
                        },
                    }
                ]
            },
        )

    monkeypatch.setattr(settings, "model_max_tokens", 100000)
    monkeypatch.setattr(settings, "model_thinking", "auto")
    monkeypatch.setattr(OpenAICompatibleClient, "_validate_endpoint", staticmethod(lambda _url: None))
    client = OpenAICompatibleClient(
        {"baseUrl": "https://api.deepseek.com/v1", "model": "deepseek-v4-flash", "apiKey": "k"},
        transport=httpx.MockTransport(handler),
    )

    await client.complete_structured({"resumeText": "x", "jobDescriptionText": "y", "evidence": []})

    assert seen["payload"]["max_tokens"] == 100000


@pytest.mark.asyncio
async def test_deepseek_v4_does_not_use_cross_provider_reasoning_budget_retry(monkeypatch):
    calls = 0
    budgets = []

    async def handler(request):
        nonlocal calls
        calls += 1
        budgets.append(json.loads(request.content)["max_tokens"])
        return httpx.Response(
            200,
            json={"choices": [{"finish_reason": "length", "message": {"content": "{", "reasoning_content": "r"}}]},
        )

    monkeypatch.setattr(settings, "model_max_tokens", 100000)
    monkeypatch.setattr(settings, "model_thinking", "auto")
    monkeypatch.setattr(OpenAICompatibleClient, "_validate_endpoint", staticmethod(lambda _url: None))
    client = OpenAICompatibleClient(
        {"baseUrl": "https://api.deepseek.com", "model": "deepseek-v4-pro", "apiKey": "k"},
        transport=httpx.MockTransport(handler),
    )

    with pytest.raises(ModelOutputInvalid):
        await client.complete_structured({"resumeText": "x", "jobDescriptionText": "y", "evidence": []})

    assert calls == 1
    assert budgets == [100000]


@pytest.mark.asyncio
async def test_explicit_thinking_enabled_omits_temperature(monkeypatch):
    seen = {}

    async def handler(request):
        seen["payload"] = json.loads(request.content)
        return httpx.Response(
            200,
            json={
                "choices": [
                    {
                        "finish_reason": "stop",
                        "message": {
                            "content": "{\"score\":{\"skills\":0,\"projectExperience\":0,\"workContent\":0,\"educationExperience\":0,\"softSkills\":0,\"composite\":0},\"requirements\":[],\"suggestions\":[]}"
                        },
                    }
                ]
            },
        )

    monkeypatch.setattr(settings, "model_thinking", "enabled")
    client = OpenAICompatibleClient(
        {"baseUrl": "https://api.deepseek.com", "model": "deepseek-v4-flash", "apiKey": "k"},
        transport=httpx.MockTransport(handler),
    )

    await client.complete_structured({"resumeText": "x", "jobDescriptionText": "y", "evidence": []})

    assert seen["payload"]["thinking"] == {"type": "enabled"}
    assert "temperature" not in seen["payload"]


@pytest.mark.asyncio
async def test_explicit_thinking_disabled_is_forwarded(monkeypatch):
    seen = {}

    async def handler(request):
        seen["payload"] = json.loads(request.content)
        return httpx.Response(
            200,
            json={
                "choices": [{"message": {"content": "{\"score\":{\"skills\":0,\"projectExperience\":0,\"workContent\":0,\"educationExperience\":0,\"softSkills\":0,\"composite\":0},\"requirements\":[],\"suggestions\":[]}"}}],
            },
        )

    monkeypatch.setattr(settings, "model_thinking", "disabled")
    client = OpenAICompatibleClient(
        {"baseUrl": "https://api.deepseek.com", "model": "deepseek-v4-flash", "apiKey": "k"},
        transport=httpx.MockTransport(handler),
    )

    await client.complete_structured({"resumeText": "x", "jobDescriptionText": "y", "evidence": []})

    assert seen["payload"]["thinking"] == {"type": "disabled"}


@pytest.mark.asyncio
async def test_custom_openai_endpoint_does_not_receive_deepseek_thinking_extension(monkeypatch):
    seen = {}

    async def handler(request):
        seen["payload"] = json.loads(request.content)
        return httpx.Response(
            200,
            json={
                "choices": [
                    {
                        "finish_reason": "stop",
                        "message": {
                            "content": "{\"score\":{\"skills\":0,\"projectExperience\":0,\"workContent\":0,\"educationExperience\":0,\"softSkills\":0,\"composite\":0},\"requirements\":[],\"suggestions\":[]}"
                        },
                    }
                ]
            },
        )

    monkeypatch.setattr(settings, "model_thinking", "auto")
    client = OpenAICompatibleClient(
        {"baseUrl": "http://127.0.0.1:8080", "model": "custom-model", "apiKey": "k"},
        transport=httpx.MockTransport(handler),
    )

    await client.complete_structured({"resumeText": "x", "jobDescriptionText": "y", "evidence": []})

    assert "thinking" not in seen["payload"]


@pytest.mark.asyncio
async def test_qwen_compatible_endpoint_keeps_existing_auto_request_shape(monkeypatch):
    seen = {}

    async def handler(request):
        seen["payload"] = json.loads(request.content)
        return httpx.Response(
            200,
            json={
                "choices": [{
                    "finish_reason": "stop",
                    "message": {
                        "content": "{\"score\":{\"skills\":0,\"projectExperience\":0,\"workContent\":0,\"educationExperience\":0,\"softSkills\":0,\"composite\":0},\"requirements\":[],\"suggestions\":[]}"
                    },
                }],
            },
        )

    monkeypatch.setattr(settings, "model_max_tokens", 100000)
    monkeypatch.setattr(settings, "model_thinking", "auto")
    monkeypatch.setattr(OpenAICompatibleClient, "_validate_endpoint", staticmethod(lambda _url: None))
    client = OpenAICompatibleClient(
        {"baseUrl": "https://dashscope.aliyuncs.com/compatible-mode/v1", "model": "qwen-plus", "apiKey": "k"},
        transport=httpx.MockTransport(handler),
    )

    await client.complete_structured({"resumeText": "x", "jobDescriptionText": "y", "evidence": []})

    assert "thinking" not in seen["payload"]
    assert seen["payload"]["max_tokens"] == 8192
    assert seen["payload"]["temperature"] == 0


@pytest.mark.asyncio
async def test_provider_retries_once_when_json_mode_returns_empty_content():
    calls = 0

    async def handler(_request):
        nonlocal calls
        calls += 1
        content = "   " if calls == 1 else json.dumps(
            {
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
        )
        return httpx.Response(200, json={"choices": [{"finish_reason": "stop", "message": {"content": content}}]})

    client = OpenAICompatibleClient(
        {"baseUrl": "http://127.0.0.1:8080", "model": "m", "apiKey": "k"},
        transport=httpx.MockTransport(handler),
    )

    result = await client.complete_structured({"resumeText": "x", "jobDescriptionText": "y", "evidence": []})

    assert result.score.composite == 0
    assert calls == 2


@pytest.mark.asyncio
async def test_provider_retries_insufficient_system_resource_once(monkeypatch):
    calls = 0
    delays = []

    async def fake_sleep(delay):
        delays.append(delay)

    async def handler(_request):
        nonlocal calls
        calls += 1
        if calls == 1:
            return httpx.Response(
                200,
                json={"choices": [{"finish_reason": "insufficient_system_resource", "message": {"content": ""}}]},
            )
        return httpx.Response(
            200,
            json={
                "choices": [
                    {
                        "finish_reason": "stop",
                        "message": {
                            "content": "{\"score\":{\"skills\":0,\"projectExperience\":0,\"workContent\":0,\"educationExperience\":0,\"softSkills\":0,\"composite\":0},\"requirements\":[],\"suggestions\":[]}"
                        },
                    }
                ]
            },
        )

    monkeypatch.setattr("app.openai_compatible.asyncio.sleep", fake_sleep)
    client = OpenAICompatibleClient(
        {"baseUrl": "http://127.0.0.1:8080", "model": "m", "apiKey": "k"},
        transport=httpx.MockTransport(handler),
    )

    result = await client.complete_structured({"resumeText": "x", "jobDescriptionText": "y", "evidence": []})

    assert result.score.composite == 0
    assert calls == 2
    assert len(delays) == 1
    assert 0 < delays[0] <= 1


@pytest.mark.asyncio
async def test_provider_maps_repeated_insufficient_system_resource_to_unavailable(monkeypatch):
    calls = 0
    delays = []

    async def fake_sleep(delay):
        delays.append(delay)

    async def handler(_request):
        nonlocal calls
        calls += 1
        return httpx.Response(
            200,
            json={"choices": [{"finish_reason": "insufficient_system_resource", "message": {"content": ""}}]},
        )

    monkeypatch.setattr("app.openai_compatible.asyncio.sleep", fake_sleep)
    client = OpenAICompatibleClient(
        {"baseUrl": "http://127.0.0.1:8080", "model": "m", "apiKey": "k"},
        transport=httpx.MockTransport(handler),
    )

    with pytest.raises(ModelUnavailable):
        await client.complete_structured({"resumeText": "x", "jobDescriptionText": "y", "evidence": []})

    assert calls == 2
    assert len(delays) == 1


@pytest.mark.asyncio
async def test_provider_retries_once_when_first_json_payload_is_malformed():
    calls = 0

    async def handler(_request):
        nonlocal calls
        calls += 1
        content = "not valid json" if calls == 1 else json.dumps(
            {
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
        )
        return httpx.Response(200, json={"choices": [{"finish_reason": "stop", "message": {"content": content}}]})

    client = OpenAICompatibleClient(
        {"baseUrl": "http://127.0.0.1:8080", "model": "m", "apiKey": "k"},
        transport=httpx.MockTransport(handler),
    )

    result = await client.complete_structured({"resumeText": "x", "jobDescriptionText": "y", "evidence": []})

    assert result.score.composite == 0
    assert calls == 2


@pytest.mark.asyncio
async def test_provider_retries_once_when_real_job_description_returns_empty_requirements():
    calls = 0
    valid_result = {
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
                "jobRequirementText": "Build Java services",
                "requirementType": "MANDATORY",
                "matchStatus": "UNMET",
                "matchType": "NO_MATCH",
                "component": "SKILLS",
                "componentScore": 0,
                "evidence": [],
                "evidenceStrength": "NONE",
                "gap": None,
                "suggestionState": "RISKY_OR_UNSUPPORTED",
            }
        ],
        "suggestions": [],
    }

    async def handler(request):
        nonlocal calls
        calls += 1
        payload = json.loads(request.content)
        if calls == 1:
            content = {
                "score": valid_result["score"],
                "requirements": [],
                "suggestions": [],
            }
        else:
            assert "Correction:" in payload["messages"][0]["content"]
            assert "requirements" in payload["messages"][0]["content"]
            assert "If the supplied job description has at least 20 characters" in payload["messages"][0]["content"]
            assert "same interview JSON contract" not in payload["messages"][0]["content"]
            content = valid_result
        return httpx.Response(200, json={"choices": [{"finish_reason": "stop", "message": {"content": json.dumps(content)}}]})

    client = OpenAICompatibleClient(
        {"baseUrl": "http://127.0.0.1:8080", "model": "m", "apiKey": "k"},
        transport=httpx.MockTransport(handler),
    )

    result = await client.complete_structured(
        {"resumeText": "x", "jobDescriptionText": "This is a real Java backend job requirement.", "evidence": []}
    )

    assert len(result.requirements) == 1
    assert calls == 2


@pytest.mark.asyncio
async def test_provider_rejects_empty_requirements_after_one_correction_for_real_job_description():
    calls = 0

    async def handler(_request):
        nonlocal calls
        calls += 1
        return httpx.Response(
            200,
            json={
                "choices": [
                    {
                        "finish_reason": "stop",
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
                                    "requirements": [],
                                    "suggestions": [],
                                }
                            )
                        },
                    }
                ]
            },
        )

    client = OpenAICompatibleClient(
        {"baseUrl": "http://127.0.0.1:8080", "model": "m", "apiKey": "k"},
        transport=httpx.MockTransport(handler),
    )

    with pytest.raises(ModelOutputInvalid):
        await client.complete_structured(
            {
                "resumeText": "x",
                "jobDescriptionText": "This is a real Java backend job requirement.",
                "evidence": [],
            }
        )

    assert calls == 2


@pytest.mark.asyncio
async def test_interview_retry_keeps_the_interview_contract_after_two_invalid_responses():
    calls = 0

    async def handler(request):
        nonlocal calls
        calls += 1
        payload = json.loads(request.content)
        if calls < 3:
            return httpx.Response(200, json={"choices": [{"message": {"content": '{"feedbackId":"feedback001"}'}}]})
        assert "same interview JSON contract" in payload["messages"][0]["content"]
        assert "Do not return a resume matching result" in payload["messages"][0]["content"]
        assert "If the supplied job description has at least 20 characters" not in payload["messages"][0]["content"]
        return httpx.Response(200, json={
            "choices": [{"finish_reason": "stop", "message": {"content": json.dumps({
                "feedbackId": "feedback001", "answerId": "answer001", "state": "FEEDBACK_READY",
                "relevance": "HIGH", "completeness": "MEDIUM", "technicalAccuracy": "HIGH",
                "factualConsistency": "HIGH", "clarity": "MEDIUM", "evidenceIds": [],
                "riskFlags": [], "claims": [], "improvementSuggestion": "补充技术取舍。",
                "suggestedAnswer": "我会说明职责和技术取舍。",
                "answerComparison": "当前回答说明了职责，还需要补充技术取舍。", "version": 1,
            })}}],
        })

    client = OpenAICompatibleClient(
        {"baseUrl": "http://127.0.0.1:8080", "model": "fixture", "apiKey": "secret"},
        transport=httpx.MockTransport(handler),
    )

    result = await client.complete_interview_structured(
        "Return exactly one interview JSON object.", {"answerId": "answer001"}, InterviewFeedbackPayload,
    )

    assert result.suggested_answer == "我会说明职责和技术取舍。"
    assert calls == 3


@pytest.mark.asyncio
async def test_provider_does_not_retry_length_terminated_response():
    calls = 0

    async def handler(_request):
        nonlocal calls
        calls += 1
        return httpx.Response(
            200,
            json={
                "choices": [
                    {
                        "finish_reason": "length",
                        "message": {"content": "{"},
                    }
                ]
            },
        )

    client = OpenAICompatibleClient(
        {"baseUrl": "http://127.0.0.1:8080", "model": "m", "apiKey": "k"},
        transport=httpx.MockTransport(handler),
    )

    with pytest.raises(ModelOutputInvalid):
        await client.complete_structured({"resumeText": "x", "jobDescriptionText": "y", "evidence": []})

    assert calls == 1


@pytest.mark.asyncio
async def test_provider_accepts_a_complete_matching_json_object_when_provider_reports_length():
    calls = 0
    content = json.dumps({
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
    })

    async def handler(_request):
        nonlocal calls
        calls += 1
        return httpx.Response(
            200,
            json={"choices": [{"finish_reason": "length", "message": {"content": content}}]},
        )

    client = OpenAICompatibleClient(
        {"baseUrl": "http://127.0.0.1:8080", "model": "m", "apiKey": "k"},
        transport=httpx.MockTransport(handler),
    )

    result = await client.complete_structured({"resumeText": "x", "jobDescriptionText": "y", "evidence": []})

    assert result.score.composite == 0
    assert calls == 1


@pytest.mark.asyncio
async def test_interview_provider_accepts_a_complete_feedback_object_when_pro_reports_length(monkeypatch):
    feedback = {
        "feedbackId": "feedback001",
        "answerId": "answer001",
        "state": "FEEDBACK_READY",
        "relevance": "HIGH",
        "completeness": "MEDIUM",
        "technicalAccuracy": "HIGH",
        "factualConsistency": "HIGH",
        "clarity": "MEDIUM",
        "evidenceIds": [],
        "riskFlags": [],
        "claims": [],
        "improvementSuggestion": "补充技术取舍。",
        "suggestedAnswer": "我会说明职责和技术取舍。",
        "answerComparison": "当前回答说明了职责，还需要补充技术取舍。",
        "version": 1,
    }
    seen = {}

    async def handler(request):
        seen["payload"] = json.loads(request.content)
        return httpx.Response(
            200,
            json={
                "choices": [{
                    "finish_reason": "length",
                    "message": {"content": json.dumps(feedback, ensure_ascii=False)},
                }],
            },
        )

    monkeypatch.setattr(OpenAICompatibleClient, "_validate_endpoint", staticmethod(lambda _url: None))
    monkeypatch.setattr(settings, "model_thinking", "auto")
    monkeypatch.setattr(settings, "model_max_tokens", 100000)
    client = OpenAICompatibleClient(
        {"baseUrl": "https://api.deepseek.com", "model": "deepseek-v4-pro", "apiKey": "fixture-api-key"},
        transport=httpx.MockTransport(handler),
    )

    result = await client.complete_interview_structured(
        "Return exactly one interview JSON object.", {"answerId": "answer001"}, InterviewFeedbackPayload,
    )

    assert result.answer_id == "answer001"
    assert seen["payload"]["model"] == "deepseek-v4-pro"
    assert seen["payload"]["thinking"] == {"type": "enabled"}
    assert seen["payload"]["reasoning_effort"] == "high"
    assert "temperature" not in seen["payload"]


@pytest.mark.asyncio
async def test_deepseek_flash_interview_request_disables_thinking_and_keeps_budget(monkeypatch):
    seen = {}
    feedback = {
        "feedbackId": "feedback001",
        "answerId": "answer001",
        "state": "FEEDBACK_READY",
        "relevance": "HIGH",
        "completeness": "MEDIUM",
        "technicalAccuracy": "HIGH",
        "factualConsistency": "HIGH",
        "clarity": "MEDIUM",
        "evidenceIds": [],
        "riskFlags": [],
        "claims": [],
        "improvementSuggestion": "补充技术取舍。",
        "suggestedAnswer": "我会说明职责和技术取舍。",
        "answerComparison": "当前回答说明了职责，还需要补充技术取舍。",
        "version": 1,
    }

    async def handler(request):
        seen["payload"] = json.loads(request.content)
        return httpx.Response(
            200,
            json={"choices": [{"finish_reason": "stop", "message": {"content": json.dumps(feedback, ensure_ascii=False)}}]},
        )

    monkeypatch.setattr(OpenAICompatibleClient, "_validate_endpoint", staticmethod(lambda _url: None))
    monkeypatch.setattr(settings, "model_max_tokens", 100000)
    monkeypatch.setattr(settings, "model_thinking", "auto")
    client = OpenAICompatibleClient(
        {"baseUrl": "https://api.deepseek.com", "model": "deepseek-flash", "apiKey": "fixture-api-key-1234567890"},
        transport=httpx.MockTransport(handler),
    )

    result = await client.complete_interview_structured(
        "Return exactly one interview JSON object.", {"answerId": "answer001"}, InterviewFeedbackPayload,
    )

    assert result.suggested_answer == feedback["suggestedAnswer"]
    assert seen["payload"]["thinking"] == {"type": "disabled"}
    assert seen["payload"]["max_tokens"] == 100000
    assert seen["payload"]["temperature"] == 0


@pytest.mark.asyncio
async def test_interview_feedback_normalizes_provider_claim_labels_without_applying_them(monkeypatch):
    feedback = {
        "feedbackId": "feedback-from-provider",
        "answerId": "answer-from-provider",
        "state": "FEEDBACK_READY",
        "relevance": "HIGH",
        "completeness": "MEDIUM",
        "technicalAccuracy": "HIGH",
        "factualConsistency": "HIGH",
        "clarity": "MEDIUM",
        "evidenceIds": [],
        "riskFlags": [],
        "claims": [{
            "id": "claim1",
            "claimText": "A claim requiring confirmation",
            "state": "NEEDS_USER_CONFIRMATION",
            "evidenceIds": [],
            "applied": True,
        }],
        "improvementSuggestion": "Add one verifiable technical detail.",
        "suggestedAnswer": "I would explain the technical trade-off.",
        "answerComparison": "The answer needs a concrete trade-off.",
        "version": 1,
    }

    async def handler(_request):
        return httpx.Response(200, json={
            "model": "deepseek-v4-pro",
            "choices": [{"finish_reason": "stop", "message": {"content": json.dumps(feedback)}}],
        })

    monkeypatch.setattr(OpenAICompatibleClient, "_validate_endpoint", staticmethod(lambda _url: None))
    monkeypatch.setattr(settings, "model_thinking", "auto")
    client = OpenAICompatibleClient(
        {"baseUrl": "https://api.deepseek.com", "model": "deepseek-v4-pro", "apiKey": "fixture-key"},
        transport=httpx.MockTransport(handler),
    )

    result = await client.complete_interview_structured(
        "Return one feedback object.",
        {"answerAnalysis": {"answerId": "answer009"}},
        InterviewFeedbackPayload,
    )

    assert result.feedback_id == "feedback001"
    assert result.answer_id == "answer009"
    assert result.claims[0].id == "claim001"
    assert result.claims[0].applied is False


@pytest.mark.asyncio
async def test_provider_retries_reasoning_length_with_a_larger_budget(monkeypatch):
    calls = 0
    budgets = []
    valid_content = json.dumps({
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
    })

    async def handler(request):
        nonlocal calls
        calls += 1
        payload = json.loads(request.content)
        budgets.append(payload["max_tokens"])
        if calls == 1:
            return httpx.Response(
                200,
                json={
                    "choices": [{
                        "finish_reason": "length",
                        "message": {"content": "{", "reasoning_content": "r" * 1000},
                    }]
                },
            )
        return httpx.Response(
            200,
            json={"choices": [{"finish_reason": "stop", "message": {"content": valid_content}}]},
        )

    monkeypatch.setattr(settings, "model_max_tokens", 32768)
    client = OpenAICompatibleClient(
        {"baseUrl": "http://127.0.0.1:8080", "model": "reasoning-model", "apiKey": "fixture-api-key"},
        transport=httpx.MockTransport(handler),
    )

    result = await client.complete_structured({"resumeText": "x", "jobDescriptionText": "y", "evidence": []})

    assert result.score.composite == 0
    assert calls == 2
    assert budgets == [8192, 32768]


@pytest.mark.asyncio
async def test_provider_does_not_expand_reasoning_budget_beyond_structured_response_cap(monkeypatch):
    calls = 0
    budgets = []

    async def handler(request):
        nonlocal calls
        calls += 1
        budgets.append(json.loads(request.content)["max_tokens"])
        return httpx.Response(
            200,
            json={"choices": [{"finish_reason": "length", "message": {"content": "{", "reasoning_content": "r"}}]},
        )

    monkeypatch.setattr(settings, "model_max_tokens", 100000)
    client = OpenAICompatibleClient(
        {"baseUrl": "http://127.0.0.1:8080", "model": "reasoning-model", "apiKey": "fixture-api-key"},
        transport=httpx.MockTransport(handler),
    )

    with pytest.raises(ModelOutputInvalid):
        await client.complete_structured({"resumeText": "x", "jobDescriptionText": "y", "evidence": []})

    assert calls == 2
    assert budgets == [8192, 32768]


@pytest.mark.asyncio
async def test_interview_provider_retries_reasoning_length_with_a_larger_budget(monkeypatch):
    calls = 0
    budgets = []
    valid_content = {
        "feedbackId": "feedback001",
        "answerId": "answer001",
        "state": "FEEDBACK_READY",
        "relevance": "HIGH",
        "completeness": "MEDIUM",
        "technicalAccuracy": "HIGH",
        "factualConsistency": "HIGH",
        "clarity": "MEDIUM",
        "evidenceIds": [],
        "riskFlags": [],
        "claims": [],
        "improvementSuggestion": "补充技术取舍。",
        "suggestedAnswer": "我会说明职责和技术取舍。",
        "answerComparison": "当前回答说明了职责，还需要补充技术取舍。",
        "version": 1,
    }

    async def handler(request):
        nonlocal calls
        calls += 1
        payload = json.loads(request.content)
        budgets.append(payload["max_tokens"])
        if calls == 1:
            return httpx.Response(
                200,
                json={
                    "choices": [{
                        "finish_reason": "length",
                        "message": {"content": "{", "reasoning_content": "r" * 1000},
                    }]
                },
            )
        return httpx.Response(
            200,
            json={"choices": [{"finish_reason": "stop", "message": {"content": json.dumps(valid_content, ensure_ascii=False)}}]},
        )

    monkeypatch.setattr(settings, "model_max_tokens", 32768)
    client = OpenAICompatibleClient(
        {"baseUrl": "http://127.0.0.1:8080", "model": "reasoning-model", "apiKey": "fixture-api-key"},
        transport=httpx.MockTransport(handler),
    )

    result = await client.complete_interview_structured(
        "Return exactly one interview JSON object.", {"answerId": "answer001"}, InterviewFeedbackPayload,
    )

    assert result.suggested_answer == valid_content["suggestedAnswer"]
    assert calls == 2
    assert budgets == [8192, 32768]


@pytest.mark.asyncio
async def test_provider_http_error_is_not_retried():
    calls = 0

    async def handler(_request):
        nonlocal calls
        calls += 1
        return httpx.Response(503, json={"error": {"message": "temporarily unavailable"}})

    client = OpenAICompatibleClient(
        {"baseUrl": "http://127.0.0.1:8080", "model": "m", "apiKey": "k"},
        transport=httpx.MockTransport(handler),
    )

    with pytest.raises(ModelUnavailable):
        await client.complete_structured({"resumeText": "x", "jobDescriptionText": "y", "evidence": []})

    assert calls == 1


@pytest.mark.asyncio
async def test_provider_network_error_is_not_retried():
    calls = 0

    async def handler(_request):
        nonlocal calls
        calls += 1
        raise httpx.ConnectError("connection refused")

    client = OpenAICompatibleClient(
        {"baseUrl": "http://127.0.0.1:8080", "model": "m", "apiKey": "k"},
        transport=httpx.MockTransport(handler),
    )

    with pytest.raises(ModelUnavailable):
        await client.complete_structured({"resumeText": "x", "jobDescriptionText": "y", "evidence": []})

    assert calls == 1


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "content",
    [
        '```json\n{"score":{"skills":0,"projectExperience":0,"workContent":0,"educationExperience":0,"softSkills":0,"composite":0},"requirements":[],"suggestions":[]}\n```',
        'Here is the JSON result:\n{"score":{"skills":0,"projectExperience":0,"workContent":0,"educationExperience":0,"softSkills":0,"composite":0},"requirements":[],"suggestions":[]}\n',
        [{"type": "text", "text": '{\"score\":{\"skills\":0,\"projectExperience\":0,\"workContent\":0,\"educationExperience\":0,\"softSkills\":0,\"composite\":0},\"requirements\":[],\"suggestions\":[]}'}]],
)
async def test_provider_accepts_common_json_content_wrappers(content):
    async def handler(_request):
        return httpx.Response(200, json={"choices": [{"message": {"content": content}}]})

    client = OpenAICompatibleClient(
        {"baseUrl": "http://127.0.0.1:8080", "model": "m", "apiKey": "k"},
        transport=httpx.MockTransport(handler),
    )
    result = await client.complete_structured({"resumeText": "x", "jobDescriptionText": "y", "evidence": []})
    assert result.score.composite == 0


@pytest.mark.asyncio
async def test_provider_filters_blank_suggestions_but_keeps_valid_suggestions():
    valid_suggestion = {
        "suggestionId": "suggestion001",
        "requirementId": "requirement001",
        "state": "NEEDS_USER_CONFIRMATION",
        "proposedText": "Add a measurable Java performance result.",
        "evidenceIds": [],
    }
    blank_suggestion = {
        "suggestionId": "suggestion002",
        "requirementId": "requirement001",
        "state": "NEEDS_USER_CONFIRMATION",
        "proposedText": "   ",
        "evidenceIds": [],
    }

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
                                    "requirements": [],
                                    "suggestions": [blank_suggestion, valid_suggestion],
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

    result = await client.complete_structured({"resumeText": "x", "jobDescriptionText": "y", "evidence": []})

    assert [item.proposed_text for item in result.suggestions] == [valid_suggestion["proposedText"]]


@pytest.mark.asyncio
async def test_provider_keeps_rejecting_suggestions_without_proposed_text():
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
                                    "requirements": [],
                                    "suggestions": [
                                        {
                                            "suggestionId": "suggestion001",
                                            "requirementId": "requirement001",
                                            "state": "NEEDS_USER_CONFIRMATION",
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

    with pytest.raises(ModelOutputInvalid):
        await client.complete_structured({"resumeText": "x", "jobDescriptionText": "y", "evidence": []})


@pytest.mark.asyncio
async def test_provider_rejects_length_terminated_json_when_content_is_truly_truncated():
    calls = 0

    async def handler(_request):
        nonlocal calls
        calls += 1
        return httpx.Response(
            200,
            json={
                "choices": [
                    {
                        "finish_reason": "length",
                        "message": {
                            "content": '{"score":{"skills":0,"projectExperience":0'
                        },
                    }
                ]
            },
        )

    client = OpenAICompatibleClient(
        {"baseUrl": "http://127.0.0.1:8080", "model": "m", "apiKey": "k"},
        transport=httpx.MockTransport(handler),
    )

    with pytest.raises(ModelOutputInvalid):
        await client.complete_structured({"resumeText": "x", "jobDescriptionText": "y", "evidence": []})

    assert calls == 1


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
async def test_oversized_provider_response_is_rejected_before_its_unrelated_body_is_parsed():
    provider_marker = "provider-debug-data-must-not-escape"
    calls = 0

    async def handler(_request):
        nonlocal calls
        calls += 1
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
                                    "requirements": [],
                                    "suggestions": [],
                                }
                            )
                        }
                    }
                ],
                "providerDebug": provider_marker + "x" * 1_048_576,
            },
        )

    client = OpenAICompatibleClient(
        {"baseUrl": "http://127.0.0.1:8080", "model": "m", "apiKey": "k"},
        transport=httpx.MockTransport(handler),
    )

    with pytest.raises(ModelOutputInvalid) as error:
        await client.complete_structured({"resumeText": "x", "jobDescriptionText": "y", "evidence": []})

    assert provider_marker not in str(error.value)
    assert calls == 1


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
        "jobDescriptionText": "JD CALLBACK_TOKEN",
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
            "jobDescriptionText": "JD",
            "evidence": [],
        }
    )

    content = json.loads(seen["body"])["messages"][1]["content"]
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
        "jobDescriptionText": "JD",
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
        "jobDescriptionText": "JD",
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
        "jobDescriptionText": "JD",
        "evidence": [],
    })
    assert address not in seen["body"]
    assert "\u5317\u4eac\u5e02\u671d\u9633\u533a" not in seen["body"]
    content = json.loads(seen["body"])["messages"][1]["content"]
    assert json.loads(content)["resumeText"].endswith(".")
