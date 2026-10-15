# Model Profile Form And Feedback Design

## Outcome

The model-profile editor uses normal form controls as its primary workflow:
configuration name, provider preset, endpoint URL, editable model name, a
separate API-key password input, and default-profile selection. The provider
preset offers `OpenAI` and `Custom`; it is UI-only and is not stored in the
profile contract. Selecting OpenAI sets `https://api.openai.com/v1`.

The editor retains a collapsed advanced TOML section. It is a strict
non-secret projection of `display_name`, `endpoint_url`, `model_name`, and
`selected`, and can be applied back to the normal form. It does not replace
the normal workflow, does not persist raw TOML, and never includes an API key.

Every terminal model-profile operation has a Chinese top notification. Inline
form validation remains for invalid local input. Provider failures use a new,
stable response field named `diagnosticCode`; the frontend never interprets or
renders provider diagnostic text.

## Contract And Security

The product source is `E:/resume_thinking/ai-resume-job-matching-project.md.docx`.
The public interface source is `contracts/openapi/v2/openapi.yaml`, upgraded
compatibly from `2.1.1` to `2.1.2`. Shared fixtures are in
`contracts/fixtures/v2/`.

`LlmProfileTestResponse` gains optional nullable `diagnosticCode` with these
values: `INVALID_API_KEY`, `PROVIDER_FORBIDDEN`,
`PROVIDER_ENDPOINT_NOT_FOUND`, `PROVIDER_RATE_LIMITED`,
`PROVIDER_UNAVAILABLE`, and `PROVIDER_RESPONSE_INVALID`. Existing fields and
all request payloads remain compatible.

The API key remains write-only, is stored only by the existing encrypted
profile flow, is never included in TOML, a notification, Pinia state, or a
provider diagnostic. This change has no MySQL, Redis, retention, resume, or
authorization behavior change. Java remains the only provider-network caller.

## Error Behavior

HTTP 401 from a provider maps to `INVALID_API_KEY`; 403 maps to
`PROVIDER_FORBIDDEN`; 404 maps to `PROVIDER_ENDPOINT_NOT_FOUND`; 429 maps to
`PROVIDER_RATE_LIMITED`; malformed provider JSON maps to
`PROVIDER_RESPONSE_INVALID`; transport and other provider failures map to
`PROVIDER_UNAVAILABLE`. The frontend translates only these stable codes into
Chinese messages, with a safe generic fallback.

Notifications are isolated behind the existing `showTopNotification` wrapper.
A notification rendering failure must not turn a successful save, scan, or
login state into a failure. A provider failure is an operation failure, not an
authentication failure of this platform, and must not clear the user session.

## Acceptance Evidence

- A new editor shows form controls and the OpenAI/Custom selector; TOML is an
  advanced optional section, and no API key appears in its text.
- An existing DeepSeek-style endpoint opens as Custom without losing endpoint,
  model, or default-selection values.
- A scanned model can populate the model input while manual typing remains
  available.
- Scan/save success and every safe failure path call a top notification with
  no secret or provider diagnostic text.
- Provider 401 results in the API-key-specific Chinese message; other failures
  use their corresponding safe messages.
- Contract validation, focused Java/Vitest tests, frontend build, and the
  login-to-profiles browser regression pass.
