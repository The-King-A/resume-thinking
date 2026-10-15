import { expect, test } from '@playwright/test'

test('a successful login renders model configuration without browser errors', async ({ page }) => {
  const runtimeErrors: string[] = []
  page.on('pageerror', (error) => runtimeErrors.push(error.message))
  page.on('console', (message) => {
    if (message.type() === 'error') runtimeErrors.push(message.text())
  })

  await page.route('**/api/v2/auth/login', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        accessToken: 'browser-test-token',
        tokenType: 'Bearer',
        expiresIn: 3600,
        user: {
          id: 'user001',
          username: 'browser-test-user',
          email: 'browser-test@example.test',
          role: 'USER',
          createdAt: '2026-09-01T00:00:00Z',
        },
      }),
    })
  })
  await page.route('**/api/v2/llm-profiles', async (route) => {
    if (route.request().method() !== 'GET') return route.fallback()
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: '[]',
    })
  })

  await page.goto('/login')
  await page.locator('input[autocomplete="username"]').fill('browser-test-user')
  await page.locator('input[autocomplete="current-password"]').fill('browser-test-password')
  await page.getByRole('button', { name: '登录' }).click()

  await expect(page).toHaveURL(/\/profiles$/)
  await expect(page.getByRole('heading', { name: '模型配置', level: 1 })).toBeVisible()
  await expect(page.getByText('尚未配置模型。')).toBeVisible()
  expect(runtimeErrors).toEqual([])

  await page.getByRole('button', { name: '新建模型配置' }).click()
  const dialog = page.getByRole('dialog', { name: '新建模型配置' })
  await expect(dialog).toBeVisible()

  const provider = dialog.getByTestId('provider-preset')
  await expect(provider).toBeVisible()
  await expect(provider.locator('option')).toHaveText(['GPT / OpenAI', 'Claude（OpenAI 兼容）', '自定义（OpenAI 兼容）'])
  await provider.selectOption('CLAUDE')
  await expect(dialog.getByTestId('endpoint-url')).toHaveValue('https://api.anthropic.com/v1')
  await provider.selectOption('CUSTOM')
  await expect(dialog.getByTestId('endpoint-url')).toHaveValue('https://api.anthropic.com/v1')

  await expect(dialog.getByTestId('endpoint-url')).toBeEditable()
  await expect(dialog.getByTestId('model-name')).toBeEditable()
  await expect(dialog.getByTestId('api-key-input')).toHaveAttribute('type', 'password')
  await expect(dialog.getByTestId('api-key-input')).toBeEditable()
  await expect(dialog.getByTestId('selected-profile')).toBeVisible()
  await expect(dialog.getByTestId('advanced-toml')).not.toHaveAttribute('open')
  await expect(dialog.getByTestId('show-advanced-toml')).toBeVisible()

  expect(runtimeErrors).toEqual([])
})

test('a failed profile route load keeps the signed-in user on a recovery page', async ({ page }) => {
  await page.route('**/api/v2/auth/login', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        accessToken: 'browser-test-token',
        tokenType: 'Bearer',
        expiresIn: 3600,
        user: {
          id: 'user001',
          username: 'browser-test-user',
          email: 'browser-test@example.test',
          role: 'USER',
          createdAt: '2026-09-01T00:00:00Z',
        },
      }),
    })
  })
  await page.route('**/src/views/ModelProfilesView.vue*', (route) => route.abort('failed'))

  await page.goto('/login')
  await page.locator('input[autocomplete="username"]').fill('browser-test-user')
  await page.locator('input[autocomplete="current-password"]').fill('browser-test-password')
  await page.getByRole('button', { name: '登录' }).click()

  await expect(page).toHaveURL(/\/route-unavailable\?retry=\/profiles$/)
  await expect(page.getByRole('heading', { name: '页面暂时无法加载', level: 1 })).toBeVisible()
  await expect(page.getByText('登录状态仍然保留。请稍后重试，或返回简历列表继续操作。')).toBeVisible()
  await expect(page.getByText('browser-test-user · user001')).toBeVisible()
})
