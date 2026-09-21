import { expect, test, type Page } from '@playwright/test'
import { randomBytes } from 'node:crypto'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const live = process.env.E2E_LIVE === '1'
const apiBase = process.env.E2E_API_BASE_URL || 'http://127.0.0.1:8080'
const providerUrl = (process.env.E2E_PROVIDER_URL || '').replace(/\/$/, '')
const failureProviderUrl = (process.env.E2E_FAILURE_PROVIDER_URL || '').replace(/\/$/, '')
const fixtureRoot = resolve(dirname(fileURLToPath(import.meta.url)), '../../tests/integration/fixtures')
const jobDescription = 'Java backend developer. Required: Java, Spring Boot, MySQL. Preferred: Redis. Build and test a reliable REST API.'

function uniquePart(): string {
  return randomBytes(5).toString('hex')
}

function isExactLoopbackHttpUrl(value: string): boolean {
  try {
    const url = new URL(value)
    return url.protocol === 'http:' && url.hostname === '127.0.0.1' && url.port !== '' && url.pathname === '/' && !url.username && !url.password && !url.search && !url.hash
  } catch { return false }
}

const frontendApiBase = isExactLoopbackHttpUrl(apiBase) ? apiBase.replace(/\/$/, '') : 'http://127.0.0.1:8080'

function escapeRegExp(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}

function exactEffectiveResumeRow(page: Page, title: string) {
  return page.getByRole('article').filter({
    has: page.locator('strong', { hasText: new RegExp(`^resume[0-9]+\\s*${escapeRegExp(title)}$`) }),
  })
}

test('lifecycle live guard rejects remote and deceptive URLs before browser traffic', () => {
  expect(isExactLoopbackHttpUrl('http://127.0.0.1:8080')).toBeTruthy()
  expect(isExactLoopbackHttpUrl('http://127.0.0.1.example.test:8080')).toBeFalsy()
  expect(isExactLoopbackHttpUrl('https://127.0.0.1:8080')).toBeFalsy()
})

test('frontend registration uses the validated API origin configured for the browser server', async ({ page }) => {
  let requestUrl = ''
  await page.route('**/api/v2/auth/register', async (route) => {
    requestUrl = route.request().url()
    await route.fulfill({
      status: 400,
      contentType: 'application/json',
      body: JSON.stringify({ code: 'VALIDATION_FAILED', message: 'fixture response', correlationId: 'e2e001', retryable: false }),
    })
  })
  await page.goto('/register')
  await page.locator('input[autocomplete="username"]').fill('browser-origin-user')
  await page.locator('input[autocomplete="email"]').fill('browser-origin@example.test')
  await page.locator('input[autocomplete="new-password"]').fill('BrowserOriginPassphrase')
  await page.locator('input[autocomplete="new-password-confirmation"]').fill('BrowserOriginPassphrase')
  await page.locator('[data-test="register-submit"]').click()
  await expect.poll(() => requestUrl).toBe(`${frontendApiBase}/api/v2/auth/register`)
})

test('effective resume row selection excludes a changed replacement title', async ({ page }) => {
  await page.setContent('<article><strong><code>resume001</code>Original title replacement</strong></article>')
  await expect(exactEffectiveResumeRow(page, 'Original title')).toHaveCount(0)
})

test.describe('resume matching lifecycle', () => {
  test.beforeEach(() => {
    test.skip(!live, 'Set E2E_LIVE=1 after starting the Java API, Python worker, and frontend.')
    test.skip(!isExactLoopbackHttpUrl(apiBase), 'E2E_API_BASE_URL must be an exact loopback HTTP origin.')
    test.skip(!providerUrl, 'Set E2E_PROVIDER_URL to a permitted OpenAI-compatible test provider.')
    test.skip(!isExactLoopbackHttpUrl(providerUrl), 'E2E_PROVIDER_URL must be an exact loopback HTTP origin.')
    test.skip(!failureProviderUrl, 'Set E2E_FAILURE_PROVIDER_URL to a permitted loopback provider that fails analyses.')
    test.skip(!isExactLoopbackHttpUrl(failureProviderUrl), 'E2E_FAILURE_PROVIDER_URL must be an exact loopback HTTP origin.')
  })

  test('preserves the effective v3 report through failed replacement, duplicate, delete, reuse, and restore conflict', async ({ page }) => {
    const suffix = uniquePart()
    const username = `e2e_user_${suffix}`
    const email = `${username}@example.test`
    const password = `E2ePassphrase-${suffix}-safe`
    const providerKey = process.env.E2E_PROVIDER_API_KEY || randomBytes(24).toString('base64url')
    const failureProviderKey = process.env.E2E_FAILURE_PROVIDER_API_KEY || providerKey
    const failureProviderModel = process.env.E2E_FAILURE_PROVIDER_MODEL || 'mvp-fixture-model'
    const title = `E2E effective lifecycle ${suffix}`
    let originalReportPath = ''

    await test.step('register a user through the public UI', async () => {
      await page.goto('/register')
      await page.locator('input[autocomplete="username"]').fill(username)
      await page.locator('input[autocomplete="email"]').fill(email)
      await page.locator('input[autocomplete="new-password"]').fill(password)
      await page.locator('input[autocomplete="new-password-confirmation"]').fill(password)
      const registration = page.waitForRequest((request) => request.method() === 'POST' && request.url() === `${frontendApiBase}/api/v2/auth/register`)
      await page.locator('[data-test="register-submit"]').click()
      await registration
      await expect(page).toHaveURL(/\/profiles$/)
    })

    await test.step('save a selected test-provider profile through the public UI', async () => {
      await page.getByTestId('new-profile').click()
      const dialog = page.getByRole('dialog', { name: '新建模型配置' })
      await dialog.getByTestId('provider-preset').selectOption('CUSTOM')
      await dialog.getByTestId('profile-name').fill('E2E test provider')
      await dialog.getByTestId('endpoint-url').fill(providerUrl)
      await dialog.getByTestId('model-name').fill('mvp-fixture-model')
      await dialog.getByTestId('api-key-input').fill(providerKey)
      await dialog.getByTestId('selected-profile').check()
      await dialog.getByTestId('save-and-test').click()
      await expect(dialog).toHaveCount(0, { timeout: 15_000 })
    })

    await test.step('save an unselected loopback-only failure profile through the public UI', async () => {
      await page.getByTestId('new-profile').click()
      const dialog = page.getByRole('dialog', { name: '新建模型配置' })
      await dialog.getByTestId('provider-preset').selectOption('CUSTOM')
      await dialog.getByTestId('profile-name').fill('E2E controlled failure provider')
      await dialog.getByTestId('endpoint-url').fill(failureProviderUrl)
      await dialog.getByTestId('model-name').fill(failureProviderModel)
      await dialog.getByTestId('api-key-input').fill(failureProviderKey)
      await expect(dialog.getByTestId('selected-profile')).not.toBeChecked()
      await dialog.getByTestId('save-and-test').click()
      await expect(dialog).toHaveCount(0, { timeout: 15_000 })
    })

    await test.step('publish the initial v3 report and effective list entry', async () => {
      await page.goto('/match')
      const fileInput = page.locator('input[type="file"]')
      await fileInput.setInputFiles(resolve(fixtureRoot, 'invalid-resume.pdf'))
      await expect(page.getByRole('alert')).toContainText('PDF')
      await fileInput.setInputFiles(resolve(fixtureRoot, 'student-resume.txt'))
      await page.getByLabel('简历标题（可选）').fill(title)
      await expect(page.getByLabel('已选模型配置')).toBeVisible()
      await page.getByLabel('Java 后端岗位描述').fill(jobDescription)
      await page.locator('[data-test="start-match"]').click()
      await expect(page.getByRole('link', { name: /查看证据结果/ })).toBeVisible({ timeout: 60_000 })
      await page.goto('/resumes')
      const originalRow = exactEffectiveResumeRow(page, title)
      await expect(originalRow).toHaveCount(1)
      const originalReport = originalRow.getByRole('link', { name: '查看匹配报告' })
      await expect(originalReport).toHaveAttribute('href', /^\/matches\/task[0-9]+$/)
      originalReportPath = await originalReport.getAttribute('href') || ''
    })

    await test.step('a changed-title DOCX replacement fails without changing the old effective row or report', async () => {
      const effectiveRow = exactEffectiveResumeRow(page, title)
      await effectiveRow.getByRole('link', { name: '重新匹配' }).click()
      await expect(page.getByRole('heading', { name: '重新匹配' })).toBeVisible()
      await page.locator('[data-test="resume-title"]').fill(`${title} replacement`)
      await page.locator('input[type="file"]').setInputFiles(resolve(fixtureRoot, 'student-resume.docx'))
      await page.getByLabel('模型配置').selectOption({ label: 'E2E controlled failure provider' })
      await page.getByRole('button', { name: '提交重新匹配' }).click()
      await expect(page.getByRole('heading', { name: '匹配结果' })).toBeVisible({ timeout: 30_000 })
      await expect(page.getByRole('heading', { name: '失败' })).toBeVisible({ timeout: 60_000 })
      await page.goto('/resumes')
      const preservedRow = exactEffectiveResumeRow(page, title)
      await expect(preservedRow).toHaveCount(1)
      await expect(exactEffectiveResumeRow(page, `${title} replacement`)).toHaveCount(0)
      await preservedRow.getByRole('link', { name: '查看匹配报告' }).click()
      expect(new URL(page.url()).pathname).toBe(originalReportPath)
      await expect(page.getByRole('heading', { name: '匹配结果' })).toBeVisible()
      await expect(page.getByText('岗位要求证据')).toBeVisible()
    })

    await test.step('a same-title duplicate keeps its report readable but does not become effective', async () => {
      await page.goto('/match')
      await page.locator('input[type="file"]').setInputFiles(resolve(fixtureRoot, 'student-resume.docx'))
      await page.getByLabel('简历标题（可选）').fill(title)
      await page.getByLabel('Java 后端岗位描述').fill(jobDescription)
      await page.locator('[data-test="start-match"]').click()
      await expect(page.getByRole('heading', { name: '简历未生效' })).toBeVisible({ timeout: 60_000 })
      await expect(page.getByText('报告有效，但该候选简历未成为有效简历：简历标题重复。')).toBeVisible()
      await page.getByRole('link', { name: '查看匹配报告' }).click()
      await expect(page.getByRole('heading', { name: '匹配结果' })).toBeVisible()
      await expect(page.getByRole('heading', { name: '简历未生效' })).toBeVisible()
      await expect(page.getByText('报告有效，但该候选简历未成为有效简历：同一账号下已存在相同标题的有效简历。')).toBeVisible()
      await page.goto('/resumes')
      await expect(exactEffectiveResumeRow(page, title)).toHaveCount(1)
    })

    await test.step('soft deletion allows same-title publication but recovery of the old record reports a duplicate conflict', async () => {
      const oldRow = exactEffectiveResumeRow(page, title)
      await oldRow.getByRole('button', { name: '删除' }).click()
      const deleteDialog = page.getByRole('dialog', { name: '删除这份简历？' })
      await deleteDialog.locator('input').fill('确认删除简历')
      await deleteDialog.locator('[data-test="confirm-delete"]').click()
      await expect(exactEffectiveResumeRow(page, title)).toHaveCount(0)

      await page.goto('/match')
      await page.locator('input[type="file"]').setInputFiles(resolve(fixtureRoot, 'student-resume.txt'))
      await page.getByLabel('简历标题（可选）').fill(title)
      await page.getByLabel('Java 后端岗位描述').fill(jobDescription)
      await page.locator('[data-test="start-match"]').click()
      await expect(page.getByRole('link', { name: /查看证据结果/ })).toBeVisible({ timeout: 60_000 })
      await page.goto('/resumes')
      await expect(exactEffectiveResumeRow(page, title)).toHaveCount(1)

      await page.goto('/recovery')
      const recoveryRow = exactEffectiveResumeRow(page, title)
      await expect(recoveryRow).toHaveCount(1)
      await recoveryRow.getByRole('button', { name: '恢复' }).click()
      const restoreDialog = page.getByRole('dialog', { name: `恢复“${title}”？` })
      await restoreDialog.locator('[data-test="confirm-restore"]').click()
      await expect(page.getByRole('alert')).toContainText('无法恢复此简历')
      await expect(recoveryRow).toHaveCount(1)
      await page.goto('/resumes')
      await expect(exactEffectiveResumeRow(page, title)).toHaveCount(1)
    })
  })
})
