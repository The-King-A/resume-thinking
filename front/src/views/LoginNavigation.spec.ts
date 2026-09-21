// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia, type Pinia } from 'pinia'

const mocks = vi.hoisted(() => ({
  request: vi.fn(),
  notify: vi.fn(),
  registerAuthSessionClearer: vi.fn(),
  registerAuthSessionExpiredHandler: vi.fn(),
  token: null as string | null,
}))

vi.mock('../api/http', () => ({
  request: mocks.request,
  registerAuthSessionClearer: mocks.registerAuthSessionClearer,
  registerAuthSessionExpiredHandler: mocks.registerAuthSessionExpiredHandler,
  tokenStorage: {
    get: () => mocks.token,
    set: (token: string) => { mocks.token = token },
    clear: () => { mocks.token = null },
  },
}))
vi.mock('../ui/notifications', () => ({ showTopNotification: mocks.notify }))

import LoginView from './LoginView.vue'
import { router } from '../router'

const response = {
  accessToken: 'token', tokenType: 'Bearer' as const, expiresIn: 3600,
  user: { id: 'user001', username: 'alice', email: 'alice@example.com', role: 'USER' as const, createdAt: '2026-09-01T00:00:00Z' },
}

describe('login navigation', () => {
  let pinia: Pinia

  beforeEach(async () => {
    localStorage.clear()
    mocks.token = null
    vi.clearAllMocks()
    pinia = createPinia()
    setActivePinia(pinia)
    await router.push('/login')
    await router.isReady()
  })

  it('enters the protected profile page after a successful login even when the top notice fails', async () => {
    mocks.request.mockResolvedValue(response)
    mocks.notify.mockImplementationOnce(() => { throw new Error('notification unavailable') })
    const wrapper = mount(LoginView, { global: { plugins: [pinia, router] } })

    await wrapper.get('input[autocomplete="username"]').setValue('alice')
    await wrapper.get('input[autocomplete="current-password"]').setValue('long-enough-password')
    await wrapper.get('button[type="submit"]').trigger('click')
    await flushPromises()

    await vi.waitFor(() => expect(router.currentRoute.value.path).toBe('/profiles'))
  })
})
