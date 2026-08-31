import Ajv2020 from 'ajv/dist/2020.js';
import addFormats from 'ajv-formats';
import { spawnSync } from 'node:child_process';
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';

const ajv = new Ajv2020({ allErrors: true, strict: false });
addFormats(ajv);
const contractsDir = new URL('..', import.meta.url);
const resolveContractPath = (path) => fileURLToPath(new URL(path, contractsDir));
const readJson = async (path) => JSON.parse(await readFile(path, 'utf8'));

const bundleOpenapi = (version) => {
  const result = spawnSync(process.execPath, ['node_modules/@redocly/cli/bin/cli.js', 'bundle', `openapi/${version}/openapi.yaml`, '--ext', 'json'], { cwd: resolveContractPath('.'), encoding: 'utf8', maxBuffer: 10 * 1024 * 1024 });
  if (result.status !== 0) throw new Error(`OpenAPI ${version} bundle failed:\n${result.stderr}`);
  const jsonStart = result.stdout.indexOf('{');
  if (jsonStart < 0) throw new Error(`OpenAPI ${version} bundle produced no JSON`);
  return JSON.parse(result.stdout.slice(jsonStart));
};

const versions = {};
for (const version of ['v1', 'v2']) {
  const openapiId = `https://resume-thinking.local/contracts/openapi/${version}/openapi.json`;
  ajv.addSchema({ ...bundleOpenapi(version), $id: openapiId }, openapiId);
  versions[version] = {
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
  await assertValid(version, 'match-request-valid.json', v.validate('CreateMatchTaskRequest'));
  await assertValid(version, 'analysis-job-valid.json', v.validateAnalysisJob);
  await assertValid(version, 'error-envelope.json', v.validate('ApiError'));
  for (const fixturePath of ['callback-valid.json', 'callback-duplicate.json', 'callback-stale.json', 'callback-after-soft-delete.json']) await assertValid(version, fixturePath, v.validateCallback);
  await assertCallbackAfterSoftDeleteContext(version);
  await assertInvalid(version, 'match-request-invalid.json', v.validate('CreateMatchTaskRequest'));
  if (version === 'v1') {
    await assertCacheArchivedResume(version, 'archive-user.json', 'USER_CACHE_ARCHIVED');
    await assertCacheArchivedResume(version, 'archive-admin.json', 'ADMIN_CACHE_ARCHIVED');
  } else {
    await assertInvalid(version, 'callback-invalid.json', v.validateCallback);
    await assertInvalid(version, 'match-request-invalid-id.json', v.validate('CreateMatchTaskRequest'));
    await assertInvalid(version, 'callback-authority-invalid.json', v.validateCallback);
  }
}

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
