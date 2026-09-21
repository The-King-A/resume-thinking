// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import AdminRecoveryView from './AdminRecoveryView.vue'
import { ApiError } from '../api/contracts'
import { useAuthStore } from '../stores/auth'

const { request, notify, router } = vi.hoisted(() => ({ request: vi.fn(), notify: vi.fn(), router: { push: vi.fn() } }))
vi.mock('../api/http', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/http')>()
  return { ...actual, request }
})
vi.mock('../ui/notifications', () => ({ showTopNotification: notify }))
vi.mock('vue-router', async (importOriginal) => {
  const actual = await importOriginal<typeof import('vue-router')>()
  return { ...actual, useRouter: () => router }
})

const recoverable = {
  id: 'resume001', ownerId: 'user042', title: 'admin-visible-resume', sourceType: 'DOCX', status: 1,
  visibilityState: 'ADMIN_SOFT_DELETED', version: 4, visibleUntil: null, softDeletedAt: '2026-08-27T08:00:00Z', archivedAt: null,
  restoredAt: null, createdAt: '2026-08-26T08:00:00Z', updatedAt: '2026-08-27T08:00:00Z',
}

describe('AdminRecoveryView role boundary', () => {
  let pinia: ReturnType<typeof createPinia>

  beforeEach(() => {
    pinia = createPinia()
    setActivePinia(pinia)
    request.mockReset()
    notify.mockReset()
    router.push.mockReset()
  })

  it('does not load or expose administrator recovery data to a USER', async () => {
    const auth = useAuthStore()
    auth.user = { id: 'user001', username: 'user', email: 'user@example.com', role: 'USER', createdAt: '' }

    const wrapper = mount(AdminRecoveryView, { global: { plugins: [pinia], stubs: { RouterLink: true } } })
    await flushPromises()

    expect(request).not.toHaveBeenCalled()
    expect(wrapper.text()).not.toContain('admin-visible-resume')
    expect(wrapper.text()).toContain('需要管理员权限')
  })

  it('uses only the administrator route and renders owner context for an ADMIN', async () => {
    const auth = useAuthStore()
    auth.user = { id: 'user002', username: 'admin', email: 'admin@example.com', role: 'ADMIN', createdAt: '' }
    request.mockResolvedValue({ items: [recoverable], page: 1, pageSize: 20, totalItems: 1, totalPages: 1 })

    const wrapper = mount(AdminRecoveryView, { global: { plugins: [pinia], stubs: { RouterLink: true } } })
    await flushPromises()

    expect(request).toHaveBeenCalledWith(expect.objectContaining({ url: '/api/v2/admin/recovery/resumes' }))
    expect(wrapper.text()).toContain('admin-visible-resume')
    expect(wrapper.text()).toContain('user042')
  })

  it('checks the current role when an action is invoked', async () => {
    const auth = useAuthStore()
    auth.user = { id: 'user002', username: 'admin', email: 'admin@example.com', role: 'ADMIN', createdAt: '' }
    request.mockResolvedValue({ items: [], page: 1, pageSize: 20, totalItems: 0, totalPages: 1 })
    const wrapper = mount(AdminRecoveryView, { global: { plugins: [pinia], stubs: { RouterLink: true } } })
    await flushPromises()
    auth.user = { id: 'user001', username: 'user', email: 'user@example.com', role: 'USER', createdAt: '' }
    await wrapper.get('form').trigger('submit')
    expect(request).toHaveBeenCalledTimes(1)
  })

  it('redirects to the effective list and notifies after an administrator restore', async () => {
    const auth = useAuthStore()
    auth.user = { id: 'user002', username: 'admin', email: 'admin@example.com', role: 'ADMIN', createdAt: '' }
    request.mockResolvedValue({ items: [recoverable], page: 1, pageSize: 20, totalItems: 1, totalPages: 1 })
    const wrapper = mount(AdminRecoveryView, { global: { plugins: [pinia], stubs: { RouterLink: true } } })
    await flushPromises()

    await wrapper.get('.record-row button').trigger('click')
    await wrapper.get('[data-test="confirm-restore"]').trigger('click')
    await flushPromises()

    expect(router.push).toHaveBeenCalledWith('/resumes')
    expect(notify).toHaveBeenCalledWith('简历已恢复，正在打开有效简历列表。', 'success')
  })

  it('shows a specific duplicate-title error and keeps the administrator row', async () => {
    const auth = useAuthStore()
    auth.user = { id: 'user002', username: 'admin', email: 'admin@example.com', role: 'ADMIN', createdAt: '' }
    request.mockReset()
    request.mockResolvedValueOnce({ items: [recoverable], page: 1, pageSize: 20, totalItems: 1, totalPages: 1 })
      .mockRejectedValueOnce(new ApiError({
        code: 'DUPLICATE_RESOURCE', message: 'private backend detail', correlationId: 'c', retryable: false,
        detailCode: 'DUPLICATE_RESUME_TITLE', details: [{ field: 'title', reason: 'duplicate' }],
      }, 409))
    const wrapper = mount(AdminRecoveryView, { global: { plugins: [pinia], stubs: { RouterLink: true } } })
    await flushPromises()

    await wrapper.get('.record-row button').trigger('click')
    await wrapper.get('[data-test="confirm-restore"]').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('简历标题已存在')
    expect(wrapper.text()).not.toContain('private backend detail')
    expect(wrapper.find('.record-row').exists()).toBe(true)
    expect(router.push).not.toHaveBeenCalled()
  })

  it('keeps the administrator row and dialog when the resume has no effective match', async () => {
    const auth = useAuthStore()
    auth.user = { id: 'user002', username: 'admin', email: 'admin@example.com', role: 'ADMIN', createdAt: '' }
    request.mockReset()
    request.mockResolvedValueOnce({ items: [recoverable], page: 1, pageSize: 20, totalItems: 1, totalPages: 1 })
      .mockRejectedValueOnce(new ApiError({
        code: 'RESUME_NOT_EFFECTIVE', message: 'private backend detail', correlationId: 'c', retryable: false,
      }, 409))
    const wrapper = mount(AdminRecoveryView, { global: { plugins: [pinia], stubs: { RouterLink: true } } })
    await flushPromises()

    await wrapper.get('.record-row button').trigger('click')
    await wrapper.get('[data-test="confirm-restore"]').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('尚未完成证据匹配')
    expect(wrapper.text()).not.toContain('private backend detail')
    expect(wrapper.find('.record-row').exists()).toBe(true)
    expect(wrapper.find('[role="dialog"]').exists()).toBe(true)
    expect(router.push).not.toHaveBeenCalled()
  })
})
