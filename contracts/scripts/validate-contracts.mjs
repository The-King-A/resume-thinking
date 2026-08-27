import Ajv2020 from 'ajv/dist/2020.js';
import addFormats from 'ajv-formats';
import { execFile as execFileCallback } from 'node:child_process';
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { promisify } from 'node:util';

const execFile = promisify(execFileCallback);
const ajv = new Ajv2020({ allErrors: true, strict: false });
addFormats(ajv);

const readJson = async (path) => JSON.parse(await readFile(path, 'utf8'));
const contractsDir = new URL('..', import.meta.url);
const resolveContractPath = (path) => fileURLToPath(new URL(path, contractsDir));

const { stdout } = await execFile(
  process.execPath,
  [
    'node_modules/@redocly/cli/bin/cli.js',
    'bundle',
    'openapi/v1/openapi.yaml',
    '--ext',
    'json'
  ],
  { cwd: resolveContractPath('.'), maxBuffer: 10 * 1024 * 1024 }
);
const openapi = JSON.parse(stdout.slice(stdout.indexOf('{')));
const openapiId = 'https://resume-thinking.local/contracts/openapi/v1/openapi.json';
ajv.addSchema({ ...openapi, $id: openapiId });

const callbackSchema = await readJson(resolveContractPath('internal/v1/analysis-callback.schema.json'));
const analysisJobSchema = await readJson(resolveContractPath('internal/v1/analysis-job.schema.json'));
const validateCallback = ajv.compile(callbackSchema);
ajv.compile(analysisJobSchema);

const schema = (name) => ajv.getSchema(`${openapiId}#/components/schemas/${name}`);
const validate = (name) => {
  const validator = schema(name);
  if (!validator) throw new Error(`OpenAPI schema not found: ${name}`);
  return validator;
};
const assertValid = async (fixturePath, validator) => {
  const fixture = await readJson(resolveContractPath(`fixtures/v1/${fixturePath}`));
  if (!validator(fixture)) {
    throw new Error(`${fixturePath} must be valid: ${ajv.errorsText(validator.errors)}`);
  }
  console.log(`valid: ${fixturePath}`);
  return fixture;
};

await assertValid('auth-register-valid.json', validate('RegisterRequest'));
await assertValid('llm-profile-valid.json', validate('CreateLlmProfileRequest'));
await assertValid('match-request-valid.json', validate('CreateMatchTaskRequest'));
await assertValid('archive-user.json', validate('Resume'));
await assertValid('archive-admin.json', validate('Resume'));
await assertValid('error-envelope.json', validate('ApiError'));

for (const fixturePath of [
  'callback-valid.json',
  'callback-duplicate.json',
  'callback-stale.json',
  'callback-after-soft-delete.json'
]) {
  await assertValid(fixturePath, validateCallback);
}

const invalidMatchRequest = await readJson(resolveContractPath('fixtures/v1/match-request-invalid.json'));
const validateMatchRequest = validate('CreateMatchTaskRequest');
if (validateMatchRequest(invalidMatchRequest)) {
  throw new Error('match-request-invalid.json must be rejected by CreateMatchTaskRequest');
}
console.log(`expected invalid: match-request-invalid.json (${ajv.errorsText(validateMatchRequest.errors)})`);
