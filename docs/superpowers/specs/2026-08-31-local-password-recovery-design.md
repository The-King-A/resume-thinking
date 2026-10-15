# Local Password Recovery Design

## Outcome

Users running the application locally can replace a forgotten password without email delivery. Registration requires the password to be entered twice. Login and registration operations display a top-of-page success or failure notification.

## Scope And Boundary

The authoritative public contract is `contracts/openapi/v2/openapi.yaml`; the product source remains `ai-resume-job-matching-project.md.docx`. The additive v2 route is `POST /api/v2/auth/password-reset` with `identifier` and `newPassword`, returning `204 No Content` on success.

Direct recovery is intentionally limited to local development: the Java service must require the `local` profile's explicit enable flag and a loopback caller. A request from another address, or any normal deployment without that flag, receives the same safe not-found response as an unknown account. The endpoint returns no password, hash, token, or account information. No external model, resume record, Redis resume cache, or retention behavior is affected.

## Backend Behavior

`identifier` is an exact trimmed username or a trimmed, lower-case email. The service finds the existing user, BCrypt-hashes `newPassword`, and persists only the replacement hash in one transaction. Unknown identifiers and unavailable local recovery produce `RESOURCE_NOT_FOUND`; invalid JSON or password length produce `VALIDATION_ERROR`. The API remains unauthenticated because this is a local-machine recovery path, and it is never enabled outside the explicit local boundary.

This focused slice does not add email delivery, password-reset tokens, a password-history table, or an external identity provider. Existing JWTs retain their current expiry behavior; a later non-local recovery feature must introduce verified delivery and session revocation.

## Frontend Behavior

Registration has `password` and `confirmPassword` inputs. The request is blocked locally unless both values match, and only `password` is sent to the server.

The login screen links to a new password-recovery page. That page accepts a username or email, a new password, and confirmation. It sends only the contract fields, clears local auth state on success, shows a top notification, and returns to login.

The authentication store owns top notifications through Element Plus `ElMessage`: successful registration/login shows a success notification; failed registration/login shows a safe failure notification. Inline validation remains available for field-specific errors.

## Acceptance Evidence

- Java tests prove a loopback local request changes the BCrypt hash, accepts login with the replacement password, rejects the old password, rejects non-local/direct-disabled requests, and maps absent users to the safe response.
- Frontend tests prove confirmation mismatch blocks registration, only one role can be selected, the recovery page omits confirmation from its API payload, and authentication success/failure emits the expected top notification.
- Contract validation accepts the valid reset fixture and rejects a payload containing client-only `confirmPassword`.
- The running local service resets `testtest002` and accepts a username login with its replacement password.
