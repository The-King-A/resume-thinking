import { expect, test } from '@playwright/test'
import { randomBytes } from 'node:crypto'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const live = process.env.E2E_LIVE === '1'
const apiBase = (process.env.E2E_API_BASE_URL || 'http://127.0.0.1:8080').replace(/\/$/, '')
const providerUrl = (process.env.E2E_PROVIDER_URL || '').replace(/\/$/, '')
const fixtureRoot = resolve(dirname(fileURLToPath(import.meta.url)), '../../tests/integration/fixtures')
const confirmation = '确认删除简历'
const jobDescription = 'Java backend developer. Required: Java, Spring Boot, MySQL. Preferred: Redis. Build and test a reliable REST API.'

function uniquePart(): string {
  return randomBytes(5).toString('hex')
}

test.describe('resume matching lifecycle', () => {
  test.beforeEach(() => {
    test.skip(!live, 'Set E2E_LIVE=1 after starting the Java API, Python worker, and frontend.')
    test.skip(!providerUrl, 'Set E2E_PROVIDER_URL to a permitted OpenAI-compatible test provider.')
    let parsed: URL
    try { parsed = new URL(providerUrl) } catch { test.skip(true, 'E2E_PROVIDER_URL must be an absolute URL.'); return }
    test.skip(Boolean(parsed.search || parsed.hash), 'E2E_PROVIDER_URL must not contain query or fragment credentials.')
  })

  test('uploads, matches with evidence, soft-deletes, and restores a resume', async ({ page, request }) => {
    const suffix = uniquePart()
    const username = `e2e_user_${suffix}`
    const email = `${username}@example.test`
    const password = `E2ePassphrase-${suffix}-safe`
    const providerKey = process.env.E2E_PROVIDER_API_KEY || (providerUrl.startsWith('http://127.0.0.1') ? randomBytes(24).toString('base64url') : '')
    test.skip(!providerKey, 'Set E2E_PROVIDER_API_KEY for a non-loopback provider.')

    await test.step('register a user through the public UI', async () => {
      await page.goto('/register')
      await page.getByLabel('Username').fill(username)
      await page.getByLabel('Email').fill(email)
      await page.getByLabel('Password').fill(password)
      await page.getByTestId('register-submit').click()
      await expect(page).toHaveURL(/\/profiles$/)
    })

    const accessToken = await page.evaluate(() => localStorage.getItem('resume-matching.token'))
    expect(accessToken).toBeTruthy()
    const profileResponse = await request.post(`${apiBase}/api/v1/llm-profiles`, {
      headers: { Authorization: `Bearer ${accessToken}` },
      data: {
        displayName: 'E2E loopback provider',
        endpointUrl: providerUrl,
        modelName: 'mvp-fixture-model',
        apiKey: providerKey,
        selected: true,
      },
    })
    expect(profileResponse.status()).toBe(201)
    const profile = await profileResponse.json() as { id: string }
    expect(profile.id).toBeTruthy()

    await page.goto('/match')
    const fileInput = page.locator('input[type="file"]')
    await fileInput.setInputFiles(resolve(fixtureRoot, 'invalid-resume.pdf'))
    await expect(page.getByRole('alert')).toContainText('PDF')
    await fileInput.setInputFiles(resolve(fixtureRoot, 'student-resume.txt'))
    await page.getByLabel('Resume title (optional)').fill('E2E lifecycle resume')
    await expect(page.getByLabel('Selected model profile')).toBeVisible()
    await page.getByLabel('Selected model profile').selectOption(profile.id)
    await page.getByLabel('Java backend job description').fill(jobDescription)
    await page.getByTestId('start-match').click()

    await expect(page.getByRole('link', { name: /View evidence result/i })).toBeVisible({ timeout: 60_000 })
    await page.getByRole('link', { name: /View evidence result/i }).click()
    await expect(page).toHaveURL(/\/matches\//)
    await expect(page.getByRole('heading', { name: 'Match result' })).toBeVisible()
    await expect(page.getByText('Requirement evidence')).toBeVisible()
    await expect(page.getByText('Java backend development')).toBeVisible()
    await expect(page.getByText(/Characters \d+-\d+/)).toBeVisible()

    await page.goto('/resumes')
    const row = page.getByRole('article').filter({ hasText: 'E2E lifecycle resume' })
    await expect(row).toBeVisible()
    await row.getByRole('button', { name: 'Delete' }).click()
    const dialog = page.getByRole('dialog', { name: 'Remove this resume?' })
    await expect(dialog).toBeVisible()
    const confirmButton = dialog.getByTestId('confirm-delete')
    await expect(confirmButton).toBeDisabled()
    await dialog.locator('input').fill(confirmation)
    await expect(confirmButton).toBeEnabled()
    await confirmButton.click()
    await expect(row).toHaveCount(0)

    await page.goto('/recovery')
    const recoveryRow = page.getByRole('article').filter({ hasText: 'E2E lifecycle resume' })
    await expect(recoveryRow).toBeVisible()
    await recoveryRow.getByRole('button', { name: 'Restore' }).click()
    const recoveryDialog = page.getByRole('dialog', { name: /Restore E2E lifecycle resume\?/i })
    await expect(recoveryDialog).toBeVisible()
    await recoveryDialog.getByTestId('confirm-restore').click()
    await expect(recoveryRow).toHaveCount(0)

    await page.goto('/resumes')
    await expect(page.getByRole('article').filter({ hasText: 'E2E lifecycle resume' })).toBeVisible()
  })
})
