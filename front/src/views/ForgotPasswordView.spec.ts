// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ForgotPasswordView from './ForgotPasswordView.vue'
import { useAuthStore } from '../stores/auth'

const mocks = vi.hoisted(() => ({
  request: vi.fn(),
  notify: vi.fn(),
  push: vi.fn(),
  registerAuthSessionClearer: vi.fn(),
  tokenClear: vi.fn(),
}))

vi.mock('../api/http', () => ({
  request: mocks.request,
  registerAuthSessionClearer: mocks.registerAuthSessionClearer,
  tokenStorage: { get: () => 'token', set: vi.fn(), clear: mocks.tokenClear },
}))
vi.mock('../ui/notifications', () => ({ showTopNotification: mocks.notify }))
vi.mock('vue-router', () => ({ useRouter: () => ({ push: mocks.push }), RouterLink: { template: '<a><slot /></a>' } }))

describe('ForgotPasswordView', () => {
  beforeEach(() => { vi.clearAllMocks(); localStorage.clear() })

  it('sends only the password reset contract fields', async () => {
    mocks.request.mockResolvedValue(undefined)
    const pinia = createPinia()
    setActivePinia(pinia)
    const auth = useAuthStore()
    auth.user = { id: 'user001', username: 'alice', email: 'alice@example.com', role: 'USER', createdAt: '2026-08-31T00:00:00Z' }
    const wrapper = mount(ForgotPasswordView, { global: { plugins: [pinia] } })
    await wrapper.get('input[autocomplete="username"]').setValue('alice')
    await wrapper.get('input[autocomplete="new-password"]').setValue('replacement-password')
    await wrapper.get('input[autocomplete="new-password-confirmation"]').setValue('replacement-password')
    await wrapper.get('button[type="submit"]').trigger('click')
    await flushPromises()
    expect(mocks.request).toHaveBeenCalledWith({
      method: 'POST',
      url: '/api/v2/auth/password-reset',
      data: { identifier: 'alice', newPassword: 'replacement-password' },
    })
    expect(auth.user).toBeNull()
    expect(mocks.tokenClear).toHaveBeenCalledOnce()
    expect(mocks.push).toHaveBeenCalledWith('/login')
  })

  it('does not render an API failure inline', async () => {
    mocks.request.mockRejectedValue(new Error('network failure'))
    const pinia = createPinia()
    setActivePinia(pinia)
    const wrapper = mount(ForgotPasswordView, { global: { plugins: [pinia] } })
    await wrapper.get('input[autocomplete="username"]').setValue('alice')
    await wrapper.get('input[autocomplete="new-password"]').setValue('replacement-password')
    await wrapper.get('input[autocomplete="new-password-confirmation"]').setValue('replacement-password')
    await wrapper.get('button[type="submit"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).not.toContain('密码重置失败，请稍后重试。')
  })
})
