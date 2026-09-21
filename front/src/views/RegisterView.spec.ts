// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import RegisterView from './RegisterView.vue'

const mocks = vi.hoisted(() => ({ error: null as string | null }))
const mockRegister = vi.fn().mockResolvedValue(undefined)
vi.mock('../stores/auth', () => ({
  useAuthStore: () => ({ register: mockRegister, loading: false, get error() { return mocks.error } }),
}))

describe('RegisterView', () => {
  beforeEach(() => { mockRegister.mockClear(); mocks.error = null })

  it('uses exclusive radio buttons and submits the selected ADMIN role during registration', async () => {
    const pinia = createPinia()
    const wrapper = mount(RegisterView, { global: { plugins: [pinia] } })
    await wrapper.get('input[autocomplete="username"]').setValue('admin-user')
    await wrapper.get('input[autocomplete="email"]').setValue('admin@example.com')
    await wrapper.get('input[autocomplete="new-password"]').setValue('long-enough-password')
    await wrapper.get('input[autocomplete="new-password-confirmation"]').setValue('long-enough-password')
    expect(wrapper.get('[data-test="role-user"]').attributes('type')).toBe('radio')
    expect(wrapper.get('[data-test="role-admin"]').attributes('type')).toBe('radio')
    expect((wrapper.get('[data-test="role-user"]').element as HTMLInputElement).checked).toBe(true)
    await wrapper.get('[data-test="role-admin"]').setValue()
    expect((wrapper.get('[data-test="role-user"]').element as HTMLInputElement).checked).toBe(false)
    expect((wrapper.get('[data-test="role-admin"]').element as HTMLInputElement).checked).toBe(true)
    await wrapper.get('[data-test="register-submit"]').trigger('click')
    await flushPromises()
    expect(mockRegister).toHaveBeenCalledWith({
      username: 'admin-user', email: 'admin@example.com', password: 'long-enough-password', role: 'ADMIN',
    })
  })

  it('shows validation and does not register invalid input', async () => {
    mockRegister.mockClear()
    const wrapper = mount(RegisterView, { global: { plugins: [createPinia()] } })
    await wrapper.get('[data-test="register-submit"]').trigger('click')
    await flushPromises()
    expect(mockRegister).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('用户名不能为空')
  })

  it('does not register values that violate the OpenAPI username pattern', async () => {
    const wrapper = mount(RegisterView, { global: { plugins: [createPinia()] } })
    await wrapper.get('input[autocomplete="username"]').setValue('bad!')
    await wrapper.get('input[autocomplete="email"]').setValue('person@example.com')
    await wrapper.get('input[autocomplete="new-password"]').setValue('long-enough-password')
    await wrapper.get('[data-test="register-submit"]').trigger('click')
    await flushPromises()
    expect(mockRegister).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('用户名只能包含字母、数字、点、下划线或连字符')
  })

  it('does not register an invalid email accepted by the manual guard', async () => {
    const wrapper = mount(RegisterView, { global: { plugins: [createPinia()] } })
    await wrapper.get('input[autocomplete="username"]').setValue('valid-user')
    await wrapper.get('input[autocomplete="email"]').setValue('person@')
    await wrapper.get('input[autocomplete="new-password"]').setValue('long-enough-password')
    await wrapper.get('[data-test="register-submit"]').trigger('click')
    await flushPromises()
    expect(mockRegister).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('请输入有效的邮箱地址')
  })

  it('blocks registration when the password confirmation does not match', async () => {
    const wrapper = mount(RegisterView, { global: { plugins: [createPinia()] } })
    await wrapper.get('input[autocomplete="username"]').setValue('valid-user')
    await wrapper.get('input[autocomplete="email"]').setValue('person@example.com')
    await wrapper.get('input[autocomplete="new-password"]').setValue('long-enough-password')
    await wrapper.get('input[autocomplete="new-password-confirmation"]').setValue('different-password')
    await wrapper.get('[data-test="register-submit"]').trigger('click')
    await flushPromises()
    expect(mockRegister).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('两次输入的密码不一致')
  })

  it('does not render an API failure inline after submission', async () => {
    mocks.error = '注册失败，请稍后重试。'
    mockRegister.mockRejectedValueOnce(new Error('network failure'))
    const wrapper = mount(RegisterView, { global: { plugins: [createPinia()] } })
    await wrapper.get('input[autocomplete="username"]').setValue('valid-user')
    await wrapper.get('input[autocomplete="email"]').setValue('person@example.com')
    await wrapper.get('input[autocomplete="new-password"]').setValue('long-enough-password')
    await wrapper.get('input[autocomplete="new-password-confirmation"]').setValue('long-enough-password')
    await wrapper.get('[data-test="register-submit"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).not.toContain('注册失败，请稍后重试。')
  })
})
