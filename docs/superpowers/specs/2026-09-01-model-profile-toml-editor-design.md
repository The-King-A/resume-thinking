# Model Profile TOML Editor Design

## Outcome

The model-profile page will provide an application-local TOML editing
experience for OpenAI-compatible providers. Profile rows expose `Edit model
configuration`; the only persistence command is `Save and test connection`.
Users can scan a provider's model list before creating a profile, choose a
model, and then save the profile.

The API key is a separate password input. It is never part of the TOML text.
After `Save and test connection`, Java stores the key in the current
authenticated user's LLM-profile database record using the existing AES-GCM
encryption fields. The key is never returned, rendered, stored in browser
storage, included in notices, or written to logs.

## Scope And Sources Of Truth

The product source of truth is
`E:/resume_thinking/ai-resume-job-matching-project.md.docx`. It requires Java
to own public authorization, database writes, and external-service boundaries.
The public interface source of truth for this slice is
`contracts/openapi/v2/openapi.yaml`, currently at `2.1.1` after the additive
v2 model-scan change and a compatible key-retention clarification. Shared
fixtures live in `contracts/fixtures/v2/`.

This feature is per authenticated user and is limited to the current
OpenAI-compatible profile model. It does not read, write, import, or expose a
CCSwitch or Codex configuration file. It does not persist raw TOML, add
arbitrary provider options, change the LLM-profile database schema, or give
Python a profile-management or secret-management path.

## TOML Projection And Secret Boundary

TOML is a strict projection of these non-secret fields only:

```toml
[profile]
display_name = "DeepSeek"
endpoint_url = "https://api.deepseek.com/v1"
model_name = "deepseek-chat"
selected = true
```

The frontend uses a standard TOML parser and a whitelist mapper. It rejects
malformed TOML, sections other than `[profile]`, keys other than
`display_name`, `endpoint_url`, `model_name`, and `selected`, incorrect value
types, and an `api_key` key. Rejection is visible before any network request;
unknown content is never silently dropped.

The API-key field is separate from the TOML editor and has `type=password`.
The transient scan action always requires a non-empty separately entered key,
whether the profile is new or already saved; it does not persist that key. For
an existing profile, an omitted or blank key on save means retain the encrypted
key already stored for that profile; a non-empty key replaces it. On a
successful save, the browser clears the input from component state. No raw API key is
copied to Pinia state or browser storage.

`current database record` means the `llm_profiles` row whose `owner_id` is
derived from the current JWT actor. Java encrypts the supplied key into
`api_key_ciphertext` and `api_key_nonce`; the API response exposes only
`hasApiKey`. No MySQL migration is needed because these columns already exist.

## Public Contract

The additive protected endpoint is:

```text
POST /api/v2/llm-profiles/scan
```

Request body:

```json
{
  "endpointUrl": "https://api.deepseek.com/v1",
  "apiKey": "write-only"
}
```

`endpointUrl` and `apiKey` follow the existing profile length limits. The
OpenAPI schema marks `apiKey` as `writeOnly: true`. The endpoint does not
create, update, select, test, or otherwise persist a profile. It validates the
endpoint using the same DNS, SSRF, HTTPS, redirect, and local-development
policy as saved-profile testing. Java calls the provider's `.../models`
endpoint; the browser never calls the provider directly.

The successful response reuses `LlmProfileTestResponse`:

```json
{
  "available": true,
  "testedAt": "2026-09-01T10:00:00Z",
  "models": ["deepseek-chat", "deepseek-reasoner"],
  "diagnostic": null
}
```

Provider network errors, timeouts, non-2xx responses, malformed provider JSON,
and an empty model list are normal `200` responses with `available: false` or
an empty `models` list and a safe diagnostic. They never expose the provider
response body, Authorization header, API key, or stack trace. Invalid request
shape uses `400`, a rejected endpoint uses the established safe `422
MODEL_ENDPOINT_REJECTED` envelope, and missing authentication uses `401`.

The existing protected `POST /api/v2/llm-profiles/{profileId}/test` remains
unchanged for a saved profile. Its documented provider-failure behavior is
corrected to the existing `200` response with `available: false`; its
misleading `503` response declaration is removed. Existing create and update
contracts remain compatible: create accepts a write-only key, and update
continues to omit a key to retain the encrypted stored value.

## Interaction Design

The new-profile command opens the same configuration editor used by profile
rows. The dialog contains:

1. a raw TOML editor for the non-secret profile projection;
2. a separate API-key password field with a clear write-only explanation;
3. a model-selection control beside `Scan model list`;
4. an available-model list populated only for the active editor session; and
5. one primary `Save and test connection` action.

Scanning parses and validates the current TOML plus the separate key, calls
the transient scan endpoint, and leaves the chosen model editable by hand.
Selecting a scanned model updates `model_name` in the TOML projection. Failed
scans preserve the editor contents and any selected model, and show a safe
actionable message.

Saving parses the TOML, maps the values plus the separate key to the existing
create or update request, persists the profile for the current actor, and then
uses the existing saved-profile test endpoint. If persistence fails, no test
request occurs. If the post-save test fails, the saved profile stays available
and the page makes the two outcomes distinguishable. Closing a dirty editor
requires a discard confirmation.

## Authorization, Retention, And Failure Rules

Only the authenticated caller can list, read, update, delete, save, or test a
profile identified by its profile ID. A foreign profile ID remains
indistinguishable from a missing profile. The scan endpoint has no resource
ID, no profile side effect, and requires only a valid authenticated user.

The scan request's API key exists only in the HTTPS request body and the
server's short-lived method scope. The save request's key persists only as the
encrypted fields of the current user's profile. Provider model metadata is not
persisted; only the selected `modelName` is saved. The component clears the
key only after save success, and never after a failed request so the user can
correct the remaining fields.

## Fixtures And Acceptance Evidence

The frozen v2 fixture set contains a valid scan request, an invalid scan
request, an available scan response, and an unavailable scan response. The
contract validator proves that the scan endpoint remains authenticated, the
request key is write-only, provider failures use the safe response shape, and
the existing saved-profile test remains compatible.

Tests must fail first for:

- strict TOML mapping and rejection of malformed, unknown, and `api_key`
  entries;
- a new profile requiring a separately supplied key, while an existing profile
  preserves its encrypted key when the field is blank;
- a transient scan that returns safe model IDs and leaves the repository
  unchanged;
- rejected endpoints, unavailable providers, and provider data containing the
  submitted key without secret leakage;
- a scanned model updating TOML, manual model entry remaining usable, and
  save-before-test behavior;
- no saved key appearing in TOML, the DOM, Pinia state, notices, responses, or
  API diagnostics; and
- owner isolation for saved-profile testing.

Verification runs the contract linter, focused Java profile tests, focused
Vitest tests, the full frontend test suite, the frontend production build, and
desktop/mobile browser checks of the editor and dialog states.
