// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import LoginView from './LoginView.vue'

const mockLogin = vi.fn().mockResolvedValue(undefined)
vi.mock('../stores/auth', () => ({
  useAuthStore: () => ({ login: mockLogin, loading: false, error: null }),
}))

describe('LoginView', () => {
  beforeEach(() => mockLogin.mockClear())

  it('submits a valid LoginRequest', async () => {
    const wrapper = mount(LoginView)
    await wrapper.get('input[autocomplete="username"]').setValue('user@example.com')
    await wrapper.get('input[autocomplete="current-password"]').setValue('password')
    await wrapper.get('button[type="submit"]').trigger('click')
    await flushPromises()
    expect(mockLogin).toHaveBeenCalledWith({ identifier: 'user@example.com', password: 'password' })
  })

  it('rejects an identifier longer than the LoginRequest limit', async () => {
    const wrapper = mount(LoginView)
    await wrapper.get('input[autocomplete="username"]').setValue('x'.repeat(255))
    await wrapper.get('input[autocomplete="current-password"]').setValue('password')
    await wrapper.get('button[type="submit"]').trigger('click')
    await flushPromises()
    expect(mockLogin).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('用户名或邮箱长度不能超过 254 个字符')
  })

  it('rejects a password longer than the LoginRequest limit', async () => {
    const wrapper = mount(LoginView)
    await wrapper.get('input[autocomplete="username"]').setValue('user')
    await wrapper.get('input[autocomplete="current-password"]').setValue('x'.repeat(129))
    await wrapper.get('button[type="submit"]').trigger('click')
    await flushPromises()
    expect(mockLogin).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('密码长度不能超过 128 个字符')
  })
})
