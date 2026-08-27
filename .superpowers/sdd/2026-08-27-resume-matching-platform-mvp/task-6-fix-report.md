# Task 6 Fix Round 3 Report

## Scope

This round changes only the Python lane under `back/python` and its Python
verification tests. Java and front-end files were not changed.

## Red/green evidence

Before the production fixes, the newly added regression tests reproduced nine
failures:

- callback payload hashes were computed before `None` fields were serialized;
- direct provider calls sent raw email, phone, and identity numbers;
- multicast DNS answers passed the endpoint policy;
- `httpx.ProtocolError` escaped callback delivery without retry;
- Pydantic coerced string and boolean values into numeric scores;
- zero-length model evidence and forged excerpts were accepted;
- labeled Chinese addresses were not redacted.

The focused regression run initially reported `9 failed, 18 passed`. Each
failure was then fixed behind its test. The final full run is:

```text
C:\Users\theking.guo\AppData\Local\Programs\Python\Python311\python.exe -m pytest back/python/tests -q
.........................................                                [100%]
41 passed, 1 warning in 0.52s
```

The warning is the existing Starlette/httpx deprecation warning.

## Changes

- Callback finalization now validates and serializes one exact payload with
  `exclude_none=True`, computes RFC 8785/JCS SHA-256 over that payload with
  only `payloadHash` omitted, and returns the same shape. Invalid
  canonicalization falls back to a contractual `MODEL_OUTPUT_INVALID`
  callback.
- The provider adapter redacts resume text, job text, every evidence excerpt,
  the provider API key if it appears in content, and caller-supplied blocked
  secrets before constructing the outbound request. The analysis service
  blocks its callback token at this boundary.
- HTTPS DNS validation now evaluates every resolved address and explicitly
  rejects non-global, multicast, unspecified, reserved, private, loopback,
  link-local, ULA, CGNAT, and documentation ranges. Explicit loopback HTTP
  remains limited to `127.0.0.1`.
- Callback delivery catches `httpx.TransportError`, including
  `ProtocolError`, and preserves the existing retry/stop behavior.
- Contract-facing numeric fields use strict numeric types; evidence ranges
  require `sourceStart < sourceEnd`.
- Model evidence offsets must remain inside the Java-issued allowed range and
  the returned excerpt must exactly equal the freshly redacted source slice.
- Address redaction accepts common `地址：...` and `住址：...` labels.
- Added real `MockTransport` outbound-body assertions, DNS address coverage,
  JCS exceptional-number checks, protocol retry coverage, exact callback hash
  assertions, evidence-boundary checks, strict-type checks, and labeled-address
  checks.

## Additional verification

```text
C:\Users\theking.guo\AppData\Local\Programs\Python\Python311\python.exe -c "import pathlib,py_compile; [py_compile.compile(str(p), doraise=True) for p in pathlib.Path('back/python/app').glob('*.py')]; print('py_compile ok')"
py_compile ok

pnpm --dir contracts validate
... all frozen fixtures valid / expected invalid fixture accepted ...
```

## Remaining risks

- Actual Java callback acceptance, lifecycle races, token hashing, and
  persistence remain Java-owned and require the Java integration suite.
- Provider API authentication still necessarily uses the configured API key
  in the provider Authorization header; the key is excluded from the model
  body and from text fields.
- DNS validation is performed before the request; production network policy
  should also prevent DNS rebinding between validation and connection.
- The Python test suite uses deterministic transports and does not contact a
  real external model provider.
