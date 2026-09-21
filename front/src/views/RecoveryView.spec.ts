// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import RecoveryView from './RecoveryView.vue'
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

const page = {
  items: [
    { id: 'resume001', ownerId: 'user001', title: 'my-resume', sourceType: 'TXT', status: 1, visibilityState: 'USER_SOFT_DELETED', version: 2, visibleUntil: null, softDeletedAt: '2026-08-27T08:00:00Z', archivedAt: null, restoredAt: null, createdAt: '2026-08-26T08:00:00Z', updatedAt: '2026-08-27T08:00:00Z' },
    { id: 'resume002', ownerId: 'user002', title: 'other-owner-resume', sourceType: 'DOCX', status: 1, visibilityState: 'USER_SOFT_DELETED', version: 3, visibleUntil: null, softDeletedAt: '2026-08-27T08:00:00Z', archivedAt: null, restoredAt: null, createdAt: '2026-08-26T08:00:00Z', updatedAt: '2026-08-27T08:00:00Z' },
  ],
  page: 1,
  pageSize: 20,
  totalItems: 2,
  totalPages: 1,
}

describe('RecoveryView owner isolation', () => {
  let pinia: ReturnType<typeof createPinia>

  beforeEach(() => {
    pinia = createPinia()
    setActivePinia(pinia)
    const auth = useAuthStore()
    auth.user = { id: 'user001', username: 'user', email: 'user@example.com', role: 'USER', createdAt: '' }
    request.mockReset().mockResolvedValue(page)
    notify.mockReset()
    router.push.mockReset()
  })

  it('does not render another owner in the USER recovery list', async () => {
    const wrapper = mount(RecoveryView, { global: { plugins: [pinia], stubs: { RouterLink: true } } })
    await flushPromises()

    expect(wrapper.text()).toContain('my-resume')
    expect(wrapper.text()).not.toContain('other-owner-resume')
    expect(request).toHaveBeenCalledWith(expect.objectContaining({ url: '/api/v2/recovery/resumes' }))
    expect(request).not.toHaveBeenCalledWith(expect.objectContaining({ url: expect.stringContaining('/api/v2/admin/') }))
  })

  it('redirects to the effective list and notifies after a successful restore', async () => {
    const wrapper = mount(RecoveryView, { global: { plugins: [pinia], stubs: { RouterLink: true } } })
    await flushPromises()

    await wrapper.get('.record-row button').trigger('click')
    await wrapper.get('[data-test="confirm-restore"]').trigger('click')
    await flushPromises()

    expect(router.push).toHaveBeenCalledWith('/resumes')
    expect(notify).toHaveBeenCalledWith('简历已恢复，正在打开有效简历列表。', 'success')
  })

  it('keeps a recovery row and shows a specific duplicate-title error', async () => {
    request.mockReset()
    request.mockResolvedValueOnce(page).mockRejectedValueOnce(new ApiError({
      code: 'DUPLICATE_RESOURCE', message: 'private backend detail', correlationId: 'c', retryable: false,
      details: [{ field: 'title', reason: 'duplicate' }],
    }, 409))
    const wrapper = mount(RecoveryView, { global: { plugins: [pinia], stubs: { RouterLink: true } } })
    await flushPromises()

    await wrapper.get('.record-row button').trigger('click')
    await wrapper.get('[data-test="confirm-restore"]').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('简历标题已存在')
    expect(wrapper.text()).not.toContain('private backend detail')
    expect(wrapper.find('.record-row').exists()).toBe(true)
    expect(router.push).not.toHaveBeenCalled()
  })

  it('keeps the row and dialog when the resume has no effective match', async () => {
    request.mockReset()
    request.mockResolvedValueOnce(page).mockRejectedValueOnce(new ApiError({
      code: 'RESUME_NOT_EFFECTIVE', message: 'private backend detail', correlationId: 'c', retryable: false,
    }, 409))
    const wrapper = mount(RecoveryView, { global: { plugins: [pinia], stubs: { RouterLink: true } } })
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
