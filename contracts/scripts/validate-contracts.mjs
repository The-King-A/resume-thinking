import Ajv2020 from 'ajv/dist/2020.js';
import addFormats from 'ajv-formats';
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';

const ajv = new Ajv2020({ allErrors: true, strict: false });
addFormats(ajv);
const contractsDir = new URL('..', import.meta.url);
const resolveContractPath = (path) => fileURLToPath(new URL(path, contractsDir));
const readJson = async (path) => JSON.parse(await readFile(path, 'utf8'));

const canonicalizeJson = (value) => {
  if (value === null || typeof value === 'string' || typeof value === 'boolean') return JSON.stringify(value);
  if (typeof value === 'number') {
    if (!Number.isFinite(value)) throw new Error('callback payload contains a non-finite number');
    return JSON.stringify(value);
  }
  if (Array.isArray(value)) return `[${value.map(canonicalizeJson).join(',')}]`;
  if (typeof value === 'object') {
    return `{${Object.keys(value).sort().map((key) => `${JSON.stringify(key)}:${canonicalizeJson(value[key])}`).join(',')}}`;
  }
  throw new Error('callback payload contains an unsupported JSON value');
};

const canonicalPayloadHash = (envelope) => createHash('sha256').update(canonicalizeJson(envelope), 'utf8').digest('hex');

const legacyJavaCallbackPayloadHash = (callback) => {
  const envelope = {
    taskId: callback.taskId,
    attempt: callback.attempt,
    callbackId: callback.callbackId,
    callbackToken: callback.callbackToken,
    outcome: callback.outcome,
    correlationId: callback.correlationId
  };
  if (callback.result != null) envelope.result = callback.result;
  if (callback.errorCode != null) envelope.errorCode = callback.errorCode;
  return canonicalPayloadHash(envelope);
};

const v3CallbackPayloadHash = (callback) => {
  const envelope = { ...callback };
  delete envelope.payloadHash;
  return canonicalPayloadHash(envelope);
};

const v4CallbackPayloadHash = (callback) => {
  const envelope = { ...callback };
  delete envelope.payloadHash;
  return canonicalPayloadHash(envelope);
};

const assertV3CallbackPayloadHash = (fixturePath, callback) => {
  if (callback.payloadHash !== v3CallbackPayloadHash(callback)) {
    throw new Error(`${fixturePath} payloadHash must match the canonical v3 callback envelope`);
  }
};

if (legacyJavaCallbackPayloadHash({
  taskId: 'task001',
  attempt: 1,
  callbackId: 'callback001',
  callbackToken: 'token-token-token-token-token-token',
  outcome: 'FAILED',
  errorCode: 'MODEL_UNAVAILABLE',
  correlationId: '00000000-0000-0000-0000-000000000003'
}) !== '883b9569f1af8ab6d1c61e31307ae8fcd5a811740128486cb17329c695a1cb8a') {
  throw new Error('contract callback canonicalization must remain aligned with Java');
}

const bundleOpenapi = (version) => {
  const cli = resolveContractPath('node_modules/@redocly/cli/bin/cli.js');
  const result = spawnSync(process.execPath, [cli, 'bundle', `openapi/${version}/openapi.yaml`, '--ext', 'json'], {
    cwd: resolveContractPath('.'), encoding: 'utf8', maxBuffer: 10 * 1024 * 1024, windowsHide: true,
  });
  if (result.error) throw new Error(`OpenAPI ${version} bundle failed to start: ${result.error.message}`);
  if (result.status !== 0) throw new Error(`OpenAPI ${version} bundle failed:\n${result.stderr || result.stdout || 'unknown error'}`);
  const jsonStart = (result.stdout || '').indexOf('{');
  if (jsonStart < 0) throw new Error(`OpenAPI ${version} bundle produced no JSON`);
  return JSON.parse(result.stdout.slice(jsonStart));
};

const versions = {};
for (const version of ['v1', 'v2', 'v3']) {
  const openapiId = `https://resume-thinking.local/contracts/openapi/${version}/openapi.json`;
  const openapi = { ...bundleOpenapi(version), $id: openapiId };
  ajv.addSchema(openapi, openapiId);
  versions[version] = {
    openapi,
    validate: (name) => {
      const validator = ajv.getSchema(`${openapiId}#/components/schemas/${name}`);
      if (!validator) throw new Error(`${version} OpenAPI schema not found: ${name}`);
      return validator;
    },
    validateCallback: ajv.compile(await readJson(resolveContractPath(`internal/${version}/analysis-callback.schema.json`))),
    validateAnalysisJob: ajv.compile(await readJson(resolveContractPath(`internal/${version}/analysis-job.schema.json`)))
  };
}

const assertValid = async (version, fixturePath, validator) => {
  const fixture = await readJson(resolveContractPath(`fixtures/${version}/${fixturePath}`));
  if (!validator(fixture)) throw new Error(`${version}/${fixturePath} must be valid: ${ajv.errorsText(validator.errors)}`);
  console.log(`valid: ${version}/${fixturePath}`);
  return fixture;
};
const assertInvalid = async (version, fixturePath, validator) => {
  const fixture = await readJson(resolveContractPath(`fixtures/${version}/${fixturePath}`));
  if (validator(fixture)) throw new Error(`${version}/${fixturePath} must be rejected`);
  console.log(`expected invalid: ${version}/${fixturePath} (${ajv.errorsText(validator.errors)})`);
};
const assertCacheArchivedResume = async (version, fixturePath, visibilityState) => {
  const resume = await assertValid(version, fixturePath, versions[version].validate('Resume'));
  if (resume.status !== 0 || resume.softDeletedAt !== null || resume.visibilityState !== visibilityState) throw new Error(`${version}/${fixturePath} must retain active deletion status while cache archived`);
};
const assertCallbackAfterSoftDeleteContext = async (version) => {
  const context = await readJson(resolveContractPath(`fixtures/${version}/callback-after-soft-delete-context.json`));
  const callback = await readJson(resolveContractPath(`fixtures/${version}/callback-after-soft-delete.json`));
  if (!versions[version].validate('MatchTask')(context.task)) throw new Error(`${version} soft-delete task must be valid`);
  if (!versions[version].validate('Resume')(context.resume)) throw new Error(`${version} soft-delete resume must be valid`);
  if (context.task.id !== callback.taskId || context.resume.id !== context.task.resumeId) throw new Error(`${version} soft-delete callback context IDs must match`);
  if (context.resume.status !== 1 || context.resume.visibilityState !== 'USER_SOFT_DELETED' || context.task.state !== 'BLOCKED') throw new Error(`${version} soft-delete context must record blocked user deletion`);
  if (context.expectedJavaRejection !== 'TASK_GONE') throw new Error(`${version} soft-delete context must expect TASK_GONE`);
  console.log(`valid: ${version}/callback-after-soft-delete-context.json`);
};

for (const version of ['v1', 'v2']) {
  const v = versions[version];
  await assertValid(version, 'auth-register-valid.json', v.validate('RegisterRequest'));
  await assertValid(version, 'llm-profile-valid.json', v.validate('CreateLlmProfileRequest'));
  if (version === 'v1') await assertValid(version, 'llm-profile-update-valid.json', v.validate('UpdateLlmProfileRequest'));
  if (version === 'v2') await assertValid(version, 'llm-profile-update-blank-key-valid.json', v.validate('UpdateLlmProfileRequest'));
  if (version === 'v2') await assertInvalid(version, 'llm-profile-update-null-key-invalid.json', v.validate('UpdateLlmProfileRequest'));
  await assertValid(version, 'match-request-valid.json', v.validate('CreateMatchTaskRequest'));
   await assertValid(version, 'analysis-job-valid.json', v.validateAnalysisJob);
   await assertValid(version, 'error-envelope.json', v.validate('ApiError'));
   if (version === 'v2') {
     const duplicateTitle = await assertValid(version, 'error-duplicate-resume-title.json', v.validate('ApiError'));
     if (duplicateTitle.code !== 'DUPLICATE_RESOURCE' || duplicateTitle.detailCode !== 'DUPLICATE_RESUME_TITLE') {
       throw new Error('v2 title duplicate must preserve its field-specific detailCode');
     }
     const resumeNotEffective = await assertValid(version, 'error-resume-not-effective.json', v.validate('ApiError'));
     if (resumeNotEffective.code !== 'RESUME_NOT_EFFECTIVE' || resumeNotEffective.retryable !== false) {
       throw new Error('v2 resume-not-effective fixture must use the public RESUME_NOT_EFFECTIVE code');
     }
   }
   for (const fixturePath of ['callback-valid.json', 'callback-duplicate.json', 'callback-stale.json', 'callback-after-soft-delete.json']) await assertValid(version, fixturePath, v.validateCallback);
  await assertCallbackAfterSoftDeleteContext(version);
  await assertInvalid(version, 'match-request-invalid.json', v.validate('CreateMatchTaskRequest'));
  if (version === 'v1') {
    await assertCacheArchivedResume(version, 'archive-user.json', 'USER_CACHE_ARCHIVED');
    await assertCacheArchivedResume(version, 'archive-admin.json', 'ADMIN_CACHE_ARCHIVED');
  } else {
    const invalidCredentials = await assertValid(version, 'auth-login-invalid-credentials.json', v.validate('InvalidCredentialsError'));
    if (invalidCredentials.code !== 'INVALID_CREDENTIALS' || invalidCredentials.retryable !== false) throw new Error('v2 login credential rejection fixture must remain generic and non-retryable');
    console.log('valid: v2 generic invalid-credentials login contract');
    await assertValid(version, 'password-reset-valid.json', v.validate('PasswordResetRequest'));
    await assertInvalid(version, 'password-reset-invalid.json', v.validate('PasswordResetRequest'));
    await assertValid(version, 'match-task-python-service-unavailable.json', v.validate('MatchTask'));
    await assertValid(version, 'match-task-python-service-authentication-failed.json', v.validate('MatchTask'));
    await assertValid(version, 'match-task-callback-delivery-failed.json', v.validate('MatchTask'));
    await assertValid(version, 'llm-profile-scan-valid.json', v.validate('LlmProfileScanRequest'));
    await assertInvalid(version, 'llm-profile-scan-invalid.json', v.validate('LlmProfileScanRequest'));
    await assertInvalid(version, 'llm-profile-scan-blank-key-invalid.json', v.validate('LlmProfileScanRequest'));
    await assertValid(version, 'llm-profile-scan-available.json', v.validate('LlmProfileTestResponse'));
    const unavailableScan = await assertValid(version, 'llm-profile-scan-unavailable.json', v.validate('LlmProfileTestResponse'));
    const diagnosticCodeSchema = v.openapi.components.schemas.LlmProfileTestResponse?.properties?.diagnosticCode;
    if (!diagnosticCodeSchema || !Array.isArray(diagnosticCodeSchema.type) || !diagnosticCodeSchema.type.includes('null')) throw new Error('v2 LLM profile diagnosticCode must exist and be nullable');
    if (unavailableScan.diagnosticCode !== 'PROVIDER_UNAVAILABLE') throw new Error('v2 unavailable scan fixture must use PROVIDER_UNAVAILABLE');
    if (v.validate('LlmProfileTestResponse')({ ...unavailableScan, diagnosticCode: 'UNSAFE_PROVIDER_DETAIL' })) throw new Error('v2 LLM profile diagnosticCode must reject undocumented values');
    console.log('valid: v2 nullable stable LLM profile diagnostic code contract');
    await assertInvalid(version, 'callback-invalid.json', v.validateCallback);
    await assertInvalid(version, 'match-request-invalid-id.json', v.validate('CreateMatchTaskRequest'));
    await assertInvalid(version, 'callback-authority-invalid.json', v.validateCallback);
  }
}

const passwordReset = versions.v2.openapi.paths['/api/v2/auth/password-reset']?.post;
if (!passwordReset || passwordReset.security?.length !== 0) throw new Error('v2 password reset must be a public POST operation');
if (!passwordReset.responses['204'] || passwordReset.responses['204'].content) throw new Error('v2 password reset success must be empty 204');
if (passwordReset.responses['404']?.$ref !== '#/components/responses/ResourceNotFound') throw new Error('v2 password reset must use generic resource-not-found for unknown or non-local accounts');
const v2Login = versions.v2.openapi.paths['/api/v2/auth/login']?.post;
if (v2Login?.responses?.['401']?.$ref !== '#/components/responses/InvalidCredentials') throw new Error('v2 login must use the generic invalid-credentials response');
const v2Restore = versions.v2.openapi.paths['/api/v2/recovery/resumes/{resumeId}/restore']?.post;
const v2AdminRestore = versions.v2.openapi.paths['/api/v2/admin/recovery/resumes/{resumeId}/restore']?.post;
if (v2Restore?.responses?.['409']?.$ref !== '#/components/responses/RestoreConflict'
    || v2AdminRestore?.responses?.['409']?.$ref !== '#/components/responses/RestoreConflict'
    || !versions.v2.openapi.components.responses.RestoreConflict) {
  throw new Error('v2 restore operations must document both stale-version and duplicate-title conflicts');
}
const registerWithConfirmation = { ...(await readJson(resolveContractPath('fixtures/v2/auth-register-valid.json'))), confirmPassword: 'client-only-confirmation' };
if (versions.v2.validate('RegisterRequest')(registerWithConfirmation)) throw new Error('v2 register confirmation must remain client-only');
console.log('valid: v2 password-reset response privacy and client-only confirmation');

const v2Scan = versions.v2.openapi.paths['/api/v2/llm-profiles/scan']?.post;
const v2ScanSecurity = v2Scan?.security ?? versions.v2.openapi.security;
if (!v2Scan || !Array.isArray(v2ScanSecurity) || !v2ScanSecurity.some((requirement) => Object.hasOwn(requirement, 'bearerAuth'))) throw new Error('v2 model scan must require bearer authentication');
if (v2Scan.responses['401']?.$ref !== '#/components/responses/Unauthorized') throw new Error('v2 model scan must retain the protected-resource unauthorized response');
const v2ScanKey = versions.v2.openapi.components.schemas.LlmProfileScanRequest?.properties?.apiKey;
if (!v2ScanKey?.writeOnly) throw new Error('v2 model scan API key must be write-only');
const v2SavedProfileTest = versions.v2.openapi.paths['/api/v2/llm-profiles/{profileId}/test']?.post;
if (!v2SavedProfileTest?.responses?.['200'] || v2SavedProfileTest.responses?.['503']) throw new Error('v2 saved-profile test must report provider failures through its safe 200 response');
console.log('valid: v2 transient model scan authentication, write-only key, and safe provider-failure contract');

const v2Job = await readJson(resolveContractPath('fixtures/v2/analysis-job-valid.json'));
const v2Callback = await readJson(resolveContractPath('fixtures/v2/callback-valid.json'));
if (v2Job.callbackId !== v2Callback.callbackId) throw new Error('v2 callbackId must round-trip unchanged');
const v2Duplicate = await readJson(resolveContractPath('fixtures/v2/callback-duplicate.json'));
if (v2Duplicate.callbackId !== v2Callback.callbackId || v2Duplicate.payloadHash !== v2Callback.payloadHash) throw new Error('v2 duplicate callback must reuse callbackId and payloadHash');
console.log('valid: v2 callback idempotency and callbackId passthrough');

const v2Mismatch = await readJson(resolveContractPath('fixtures/v2/callback-id-mismatch.json'));
const validateV2CallbackForJob = (callback, job) => versions.v2.validateCallback(callback) && callback.callbackId === job.callbackId;
if (!validateV2CallbackForJob(v2Callback, v2Job)) throw new Error('v2 callbackId must match its analysis job');
if (!versions.v2.validateCallback(v2Mismatch)) throw new Error('v2 callbackId mismatch fixture must remain schema-valid');
if (validateV2CallbackForJob(v2Mismatch, v2Job)) throw new Error('v2 callbackId mismatch must be rejected');
console.log('expected invalid: v2/callback-id-mismatch.json (callbackId is not the Java-issued job callbackId)');

const v2User = versions.v2.validate('User');
if (!v2User({ id: 'user001', username: 'u', email: 'u@example.test', role: 'USER', createdAt: new Date().toISOString() })) throw new Error('v2 user001 must be valid');
if (v2User({ id: '550e8400-e29b-41d4-a716-446655440000', username: 'u', email: 'u@example.test', role: 'USER', createdAt: new Date().toISOString() })) throw new Error('UUID user id must be rejected by v2');
console.log('valid: v2 string ID assertions');

const v3 = versions.v3;
await assertValid('v3', 'match-submission-initial-valid.json', v3.validate('InitialMatchSubmissionRequest'));
await assertValid('v3', 'match-submission-rematch-valid.json', v3.validate('RematchSubmissionRequest'));
await assertInvalid('v3', 'match-submission-invalid.json', v3.validate('InitialMatchSubmissionRequest'));
await assertInvalid('v3', 'match-submission-rematch-invalid.json', v3.validate('RematchSubmissionRequest'));
const v3EffectiveResume = await assertValid('v3', 'resume-effective.json', v3.validate('Resume'));
const v3PendingResume = await assertValid('v3', 'resume-pending.json', v3.validate('Resume'));
if (v3EffectiveResume.pendingRevisionId !== null || v3PendingResume.pendingRevisionId !== 'revision002') {
  throw new Error('v3 resumes must distinguish effective and pending revisions');
}
if (!v3.validate('EffectiveResumeListItem')(v3EffectiveResume)) throw new Error('v3 effective resume must validate as an effective list item');
if (v3.validate('EffectiveResumeListItem')(v3PendingResume)) throw new Error('v3 pending resume must not validate as an effective list item');
if (v3.validate('EffectiveResumePage')({ items: [v3PendingResume], page: 1, pageSize: 20, totalItems: 1, totalPages: 1 })) {
  throw new Error('v3 pending resume must not validate inside the effective resume list response');
}
console.log('expected invalid: v3/resume-pending.json (not an effective list item)');
const v3Context = await assertValid('v3', 'match-context-valid.json', v3.validate('MatchContext'));
if (v3Context.title !== 'Fixture platform engineer' || v3Context.effectiveRevisionId !== 'revision001' || v3Context.pendingRevisionId !== 'revision002' || v3Context.latestSuccessfulTaskId !== 'task001' || v3Context.llmProfileId !== 'profile001' || v3Context.jobDescriptionText !== 'Build reliable Java services with clear tests and operational ownership.') {
  throw new Error('v3 match context must expose direct pending-candidate prefill metadata');
}
if (v3Context.pendingRevisionId === null) throw new Error('v3 match context must prefer its pending candidate when one exists');
console.log('valid: v3 match-context pending-candidate prefill contract');
const v3EffectiveOnlyContext = await assertValid('v3', 'match-context-effective-only-valid.json', v3.validate('MatchContext'));
if (v3EffectiveOnlyContext.pendingRevisionId !== null || v3EffectiveOnlyContext.latestSuccessfulTaskId !== 'task001' || v3EffectiveOnlyContext.llmProfileId !== 'profile001') throw new Error('v3 match context must fall back to the latest successful candidate');
const v3PendingOnlyContext = await assertValid('v3', 'match-context-pending-only-valid.json', v3.validate('MatchContext'));
if (v3PendingOnlyContext.effectiveRevisionId !== null || v3PendingOnlyContext.pendingRevisionId !== 'revision003' || v3PendingOnlyContext.latestSuccessfulTaskId !== null || v3PendingOnlyContext.llmProfileId !== 'profile002') throw new Error('v3 match context must support a pending-only candidate');
console.log('valid: v3 match-context effective fallback and pending-only prefill contracts');
await assertValid('v3', 'task-publication-rejected-duplicate-title.json', v3.validate('MatchTask'));
await assertInvalid('v3', 'task-publication-invalid-failed.json', v3.validate('MatchTask'));
await assertInvalid('v3', 'task-publication-invalid-queued.json', v3.validate('MatchTask'));
const v3SuccessfulCallback = await assertValid('v3', 'callback-valid.json', v3.validateCallback);
const v3DuplicateCallback = await assertValid('v3', 'callback-duplicate.json', v3.validateCallback);
const v3SupersededCallback = await assertValid('v3', 'callback-superseded.json', v3.validateCallback);
const v3DeletedCallback = await assertValid('v3', 'callback-after-soft-delete.json', v3.validateCallback);
await assertValid('v3', 'callback-timeout.json', v3.validateCallback);
if (v3SuccessfulCallback.outcome !== 'SUCCEEDED' || v3SuccessfulCallback.result.requirements[0].evidence.length !== 1 || v3SuccessfulCallback.result.suggestions[0].state !== 'SUPPORTED_FACT') throw new Error('v3 successful callback must carry safe evidence-backed result data');
const v3Score = v3SuccessfulCallback.result.score;
const expectedV3Composite = Math.round((
  0.40 * v3Score.skills
  + 0.25 * v3Score.projectExperience
  + 0.15 * v3Score.workContent
  + 0.10 * v3Score.educationExperience
  + 0.10 * v3Score.softSkills
  + Number.EPSILON
) * 10_000) / 10_000;
if (v3Score.composite !== expectedV3Composite) throw new Error('v3 successful callback composite score must match the documented weighted formula');
assertV3CallbackPayloadHash('v3/callback-valid.json', v3SuccessfulCallback);
assertV3CallbackPayloadHash('v3/callback-duplicate.json', v3DuplicateCallback);
if (v3CallbackPayloadHash({ ...v3SuccessfulCallback, revisionId: 'revision999' }) === v3CallbackPayloadHash(v3SuccessfulCallback)) {
  throw new Error('v3 callback payloadHash must bind revisionId');
}
console.log('valid: v3 successful callback canonical hash and weighted score semantics');
if (v3DuplicateCallback.callbackId !== v3SuccessfulCallback.callbackId || v3DuplicateCallback.payloadHash !== v3SuccessfulCallback.payloadHash) throw new Error('v3 duplicate callback must freeze the successful callback identity');
const v3SupersededContext = await readJson(resolveContractPath('fixtures/v3/callback-superseded-context.json'));
if (v3SupersededContext.taskId !== v3SupersededCallback.taskId || v3SupersededContext.callbackRevisionId !== v3SupersededCallback.revisionId || v3SupersededContext.callbackAttempt !== v3SupersededCallback.attempt || v3SupersededContext.currentRevisionId === v3SupersededCallback.revisionId || v3SupersededContext.currentAttempt === v3SupersededCallback.attempt || v3SupersededContext.expectedJavaRejection !== 'STALE_ATTEMPT') throw new Error('v3 superseded callback fixture must freeze stale-attempt rejection semantics');
const v3DeletedContext = await readJson(resolveContractPath('fixtures/v3/callback-after-soft-delete-context.json'));
if (v3DeletedContext.taskId !== v3DeletedCallback.taskId || v3DeletedContext.revisionId !== v3DeletedCallback.revisionId || v3DeletedContext.resumeStatus !== 1 || v3DeletedContext.expectedJavaRejection !== 'TASK_GONE') throw new Error('v3 soft-delete callback fixture must freeze TASK_GONE rejection semantics');
console.log('valid: v3 successful, duplicate, timeout, stale, and soft-delete callback lifecycle contracts');
const v3Duplicate = await assertValid('v3', 'error-duplicate-resume-title.json', v3.validate('ApiError'));
await assertValid('v3', 'error-invalid-confirmation.json', v3.validate('ApiError'));
const v3ResumeNotEffective = await assertValid('v3', 'error-resume-not-effective.json', v3.validate('ApiError'));
if (v3ResumeNotEffective.code !== 'RESUME_NOT_EFFECTIVE' || v3ResumeNotEffective.detailCode !== null) {
  throw new Error('v3 resume-not-effective fixture must remain a nullable-detail error envelope');
}
if (v3Duplicate.code !== 'DUPLICATE_RESOURCE' || v3Duplicate.detailCode !== 'DUPLICATE_RESUME_TITLE') {
  throw new Error('v3 title duplicate must be stable and field-specific');
}
const v3Resume = v3.openapi.components.schemas.Resume;
if (v3Resume.properties.effectiveRevisionId?.pattern !== '^revision[0-9]{3,}$') {
  throw new Error('v3 effective revision ID is required');
}
const v3Task = v3.openapi.components.schemas.MatchTask;
if (v3Task.properties.publicationState?.$ref !== '#/components/schemas/PublicationState') {
  throw new Error('v3 task publication state is required');
}
const missingV3ErrorCodes = versions.v2.openapi.components.schemas.ApiError.properties.code.enum
  .filter((code) => !v3.openapi.components.schemas.ApiError.properties.code.enum.includes(code));
if (missingV3ErrorCodes.length > 0) throw new Error(`v3 API errors must retain the complete v2 error vocabulary; missing: ${missingV3ErrorCodes.join(', ')}`);
const missingV3FailureCodes = versions.v2.openapi.components.schemas.MatchTask.properties.failureCode.enum
  .filter((code) => code !== null && !v3Task.properties.failureCode.enum.includes(code));
if (missingV3FailureCodes.length > 0) throw new Error(`v3 task failure codes must retain the complete v2 failure vocabulary; missing: ${missingV3FailureCodes.join(', ')}`);
const v3InvalidCredentialsResponse = v3.openapi.components.responses.InvalidCredentials;
if (v3InvalidCredentialsResponse?.content?.['application/json']?.schema?.$ref !== '#/components/schemas/InvalidCredentialsError') {
  throw new Error('v3 must retain the standalone generic invalid-credentials response component without adding auth routes');
}
const v3InvalidCredentialsSchema = v3.openapi.components.schemas.InvalidCredentialsError;
if (v3InvalidCredentialsSchema?.allOf?.[1]?.properties?.code?.const !== 'INVALID_CREDENTIALS') {
  throw new Error('v3 invalid-credentials error must constrain the public code to INVALID_CREDENTIALS');
}
const v3Delete = v3.openapi.paths['/api/v3/resumes/{resumeId}']?.delete;
if (v3Delete?.responses?.['400']?.$ref !== '#/components/responses/BadRequest') throw new Error('v3 confirmed delete must expose a safe invalid-confirmation response');
const v3Callback = await readJson(resolveContractPath('fixtures/v3/callback-valid.json'));
const v3JobSchema = await readJson(resolveContractPath('internal/v3/analysis-job.schema.json'));
const v3CallbackSchema = await readJson(resolveContractPath('internal/v3/analysis-callback.schema.json'));
if (!v3JobSchema.required?.includes('revisionId') || !v3CallbackSchema.required?.includes('revisionId') || v3Callback.revisionId !== 'revision002') {
  throw new Error('v3 Java/Python messages must require the effective revision ID');
}
console.log('valid: v3 effective-resume lifecycle and revision ownership contract');

// Interview practice is a new public boundary. Keep it in a separate v4
// validator so the released v1-v3 analysis contracts remain untouched.
const v4OpenapiId = 'https://resume-thinking.local/contracts/openapi/v4/openapi.json';
const v4Openapi = { ...bundleOpenapi('v4'), $id: v4OpenapiId };
ajv.addSchema(v4Openapi, v4OpenapiId);
const v4 = {
  openapi: v4Openapi,
  validate: (name) => {
    const validator = ajv.getSchema(`${v4OpenapiId}#/components/schemas/${name}`);
    if (!validator) throw new Error(`v4 OpenAPI schema not found: ${name}`);
    return validator;
  },
  validateJob: ajv.compile(await readJson(resolveContractPath('internal/v4/interview-job.schema.json'))),
  validateCallback: ajv.compile(await readJson(resolveContractPath('internal/v4/interview-callback.schema.json'))),
};
const assertValidV4 = async (fixturePath, validator) => {
  const fixture = await readJson(resolveContractPath(`fixtures/v4/${fixturePath}`));
  if (!validator(fixture)) throw new Error(`v4/${fixturePath} must be valid: ${ajv.errorsText(validator.errors)}`);
  console.log(`valid: v4/${fixturePath}`);
  return fixture;
};
const assertInvalidV4 = async (fixturePath, validator) => {
  const fixture = await readJson(resolveContractPath(`fixtures/v4/${fixturePath}`));
  if (validator(fixture)) throw new Error(`v4/${fixturePath} must be rejected`);
  console.log(`expected invalid: v4/${fixturePath} (${ajv.errorsText(validator.errors)})`);
};

const v4Create = await assertValidV4('interview/session-create-valid.json', v4.validate('CreateInterviewSessionRequest'));
if (v4Create.matchTaskId !== 'task001' || 'matchResultId' in v4Create) {
  throw new Error('v4 interview creation must bind a matching task without exposing a result id');
}
await assertInvalidV4('interview/session-create-invalid.json', v4.validate('CreateInterviewSessionRequest'));
await assertInvalidV4('interview/answer-submit-invalid.json', v4.validate('SubmitInterviewAnswerRequest'));
const v4Session = await assertValidV4('interview/session-valid.json', v4.validate('InterviewSession'));
if (v4Session.activeQuestionId !== 'question001' || v4Session.answeredQuestionId !== null) {
  throw new Error('v4 interview session must distinguish the active question from an answered question');
}
const v4Questions = await assertValidV4('interview/questions-valid.json', v4.validate('InterviewQuestionSet'));
if (v4Questions.questions.some((question) => typeof question.answered !== 'boolean')) {
  throw new Error('v4 interview question state must explicitly identify answered questions');
}
const v4Feedback = await assertValidV4('interview/feedback-valid.json', v4.validate('InterviewFeedback'));
if (!v4Feedback.submittedAnswer || !Object.hasOwn(v4Feedback, 'nextQuestionId')) {
  throw new Error('v4 interview feedback must expose the submitted answer and next-question state');
}
const v4QuestionJob = await assertValidV4('interview/job-question-generation-valid.json', v4.validateJob);
if (v4QuestionJob.workType !== 'QUESTION_GENERATION') throw new Error('v4 question job must declare QUESTION_GENERATION');
const v4AnswerJob = await assertValidV4('interview/job-answer-analysis-valid.json', v4.validateJob);
if (v4AnswerJob.workType !== 'ANSWER_ANALYSIS') throw new Error('v4 answer job must declare ANSWER_ANALYSIS');
const v4QuestionCallback = await assertValidV4('interview/callback-questions-valid.json', v4.validateCallback);
if (v4QuestionCallback.payloadHash !== v4CallbackPayloadHash(v4QuestionCallback)) {
  throw new Error('v4 interview callback payloadHash must cover the complete callback envelope');
}
const v4FeedbackCallback = await assertValidV4('interview/callback-feedback-valid.json', v4.validateCallback);
if (v4FeedbackCallback.payloadHash !== v4CallbackPayloadHash(v4FeedbackCallback)) {
  throw new Error('v4 feedback callback payloadHash must cover the complete callback envelope');
}
const v4QuestionTypes = new Set(v4QuestionCallback.questions.map((question) => question.questionType));
if (v4QuestionCallback.questions.length !== 4 || v4QuestionTypes.size !== 4
    || !['BASIC_CONFIRMATION', 'PROJECT_DEEP_DIVE', 'JOB_SCENARIO', 'SYNTHESIS_FOLLOW_UP']
      .every((type) => v4QuestionTypes.has(type))) {
  throw new Error('v4 question callback must contain exactly one of each required question type');
}
if (v4FeedbackCallback.feedback.claims.some((claim) => claim.applied === true)) {
  throw new Error('v4 feedback claims must not be applied to a resume');
}
const v4DuplicateCallback = await assertValidV4('interview/callback-duplicate.json', v4.validateCallback);
if (v4DuplicateCallback.callbackId !== v4QuestionCallback.callbackId
    || v4DuplicateCallback.payloadHash !== v4QuestionCallback.payloadHash) {
  throw new Error('v4 duplicate callback must reuse callbackId and payloadHash');
}
await assertValidV4('interview/callback-timeout.json', v4.validateCallback);
await assertValidV4('interview/callback-stale.json', v4.validateCallback);
await assertValidV4('interview/callback-after-delete.json', v4.validateCallback);
console.log('valid: v4 interview contract, callback hash, question-type, and deletion fixtures');
