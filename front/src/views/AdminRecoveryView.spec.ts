// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import AdminRecoveryView from './AdminRecoveryView.vue'
import { useAuthStore } from '../stores/auth'

const { request } = vi.hoisted(() => ({ request: vi.fn() }))
vi.mock('../api/http', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/http')>()
  return { ...actual, request }
})

const recoverable = {
  id: 'resume-1', ownerId: 'owner-42', title: 'admin-visible-resume', sourceType: 'DOCX', status: 1,
  visibilityState: 'ADMIN_SOFT_DELETED', version: 4, visibleUntil: null, softDeletedAt: '2026-08-27T08:00:00Z', archivedAt: null,
  restoredAt: null, createdAt: '2026-08-26T08:00:00Z', updatedAt: '2026-08-27T08:00:00Z',
}

describe('AdminRecoveryView role boundary', () => {
  let pinia: ReturnType<typeof createPinia>

  beforeEach(() => {
    pinia = createPinia()
    setActivePinia(pinia)
    request.mockReset()
  })

  it('does not load or expose administrator recovery data to a USER', async () => {
    const auth = useAuthStore()
    auth.user = { id: 'owner-1', username: 'user', email: 'user@example.com', role: 'USER', createdAt: '' }

    const wrapper = mount(AdminRecoveryView, { global: { plugins: [pinia], stubs: { RouterLink: true } } })
    await flushPromises()

    expect(request).not.toHaveBeenCalled()
    expect(wrapper.text()).not.toContain('admin-visible-resume')
    expect(wrapper.text()).toContain('Administrator access required')
  })

  it('uses only the administrator route and renders owner context for an ADMIN', async () => {
    const auth = useAuthStore()
    auth.user = { id: 'admin-1', username: 'admin', email: 'admin@example.com', role: 'ADMIN', createdAt: '' }
    request.mockResolvedValue({ items: [recoverable], page: 1, pageSize: 20, totalItems: 1, totalPages: 1 })

    const wrapper = mount(AdminRecoveryView, { global: { plugins: [pinia], stubs: { RouterLink: true } } })
    await flushPromises()

    expect(request).toHaveBeenCalledWith(expect.objectContaining({ url: '/api/v1/admin/recovery/resumes' }))
    expect(wrapper.text()).toContain('admin-visible-resume')
    expect(wrapper.text()).toContain('owner-42')
  })
})
