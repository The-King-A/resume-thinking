// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import RecoveryView from './RecoveryView.vue'
import { useAuthStore } from '../stores/auth'

const { request } = vi.hoisted(() => ({ request: vi.fn() }))
vi.mock('../api/http', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/http')>()
  return { ...actual, request }
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
  })

  it('does not render another owner in the USER recovery list', async () => {
    const wrapper = mount(RecoveryView, { global: { plugins: [pinia], stubs: { RouterLink: true } } })
    await flushPromises()

    expect(wrapper.text()).toContain('my-resume')
    expect(wrapper.text()).not.toContain('other-owner-resume')
    expect(request).toHaveBeenCalledWith(expect.objectContaining({ url: '/api/v2/recovery/resumes' }))
    expect(request).not.toHaveBeenCalledWith(expect.objectContaining({ url: expect.stringContaining('/api/v2/admin/') }))
  })
})
