// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import RegisterView from './RegisterView.vue'

const mockRegister = vi.fn().mockResolvedValue(undefined)
vi.mock('../stores/auth', () => ({
  useAuthStore: () => ({ register: mockRegister, loading: false, error: null }),
}))

describe('RegisterView', () => {
  beforeEach(() => mockRegister.mockClear())

  it('submits selected ADMIN role during registration', async () => {
    const pinia = createPinia()
    const wrapper = mount(RegisterView, { global: { plugins: [pinia] } })
    await wrapper.get('input[autocomplete="username"]').setValue('admin-user')
    await wrapper.get('input[autocomplete="email"]').setValue('admin@example.com')
    await wrapper.get('input[autocomplete="new-password"]').setValue('long-enough-password')
    await wrapper.get('[data-test="role-admin"]').setValue(true)
    await wrapper.get('[data-test="register-submit"]').trigger('click')
    await flushPromises()
    expect(mockRegister).toHaveBeenCalledWith(expect.objectContaining({ role: 'ADMIN' }))
  })

  it('shows validation and does not register invalid input', async () => {
    mockRegister.mockClear()
    const wrapper = mount(RegisterView, { global: { plugins: [createPinia()] } })
    await wrapper.get('[data-test="register-submit"]').trigger('click')
    await flushPromises()
    expect(mockRegister).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('Username is required')
  })

  it('does not register values that violate the OpenAPI username pattern', async () => {
    const wrapper = mount(RegisterView, { global: { plugins: [createPinia()] } })
    await wrapper.get('input[autocomplete="username"]').setValue('bad!')
    await wrapper.get('input[autocomplete="email"]').setValue('person@example.com')
    await wrapper.get('input[autocomplete="new-password"]').setValue('long-enough-password')
    await wrapper.get('[data-test="register-submit"]').trigger('click')
    await flushPromises()
    expect(mockRegister).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('Username may contain only letters, numbers, dot, underscore, or hyphen')
  })

  it('does not register an invalid email accepted by the manual guard', async () => {
    const wrapper = mount(RegisterView, { global: { plugins: [createPinia()] } })
    await wrapper.get('input[autocomplete="username"]').setValue('valid-user')
    await wrapper.get('input[autocomplete="email"]').setValue('person@')
    await wrapper.get('input[autocomplete="new-password"]').setValue('long-enough-password')
    await wrapper.get('[data-test="register-submit"]').trigger('click')
    await flushPromises()
    expect(mockRegister).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('Enter a valid email')
  })
})
