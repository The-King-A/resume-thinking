// @vitest-environment jsdom
import { describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import RegisterView from './RegisterView.vue'

const mockRegister = vi.fn().mockResolvedValue(undefined)
vi.mock('../stores/auth', () => ({
  useAuthStore: () => ({ register: mockRegister, loading: false, error: null }),
}))

describe('RegisterView', () => {
  it('submits selected ADMIN role during registration', async () => {
    const pinia = createPinia()
    const wrapper = mount(RegisterView, { global: { plugins: [pinia] } })
    await wrapper.get('[data-test="role-admin"]').setValue(true)
    await wrapper.get('[data-test="register-submit"]').trigger('click')
    expect(mockRegister).toHaveBeenCalledWith(expect.objectContaining({ role: 'ADMIN' }))
  })
})
