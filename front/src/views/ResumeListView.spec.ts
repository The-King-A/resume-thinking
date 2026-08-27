// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ResumeListView from './ResumeListView.vue'
import { useAuthStore } from '../stores/auth'

const { lifecycleApi } = vi.hoisted(() => ({ lifecycleApi: { listResumes: vi.fn(), deleteResume: vi.fn(), adminSoftDeleteResume: vi.fn() } }))
vi.mock('../api/lifecycle', () => ({ lifecycleApi }))

const active = { id: 'resume-1', ownerId: 'owner-1', title: 'resume', sourceType: 'TXT', status: 0, visibilityState: 'ACTIVE', version: 2, visibleUntil: null, softDeletedAt: null, archivedAt: null, restoredAt: null, createdAt: '', updatedAt: '' }
const page = () => ({ items: [active], page: 1, pageSize: 20, totalItems: 1, totalPages: 1 })

describe('ResumeListView role-scoped deletion', () => {
  let pinia: ReturnType<typeof createPinia>
  beforeEach(() => {
    pinia = createPinia(); setActivePinia(pinia); lifecycleApi.listResumes.mockReset().mockResolvedValue(page()); lifecycleApi.deleteResume.mockReset().mockResolvedValue(active); lifecycleApi.adminSoftDeleteResume.mockReset().mockResolvedValue(active)
  })

  it('uses the user delete route for a USER', async () => {
    const auth = useAuthStore(); auth.user = { id: 'owner-1', username: 'user', email: 'user@example.com', role: 'USER', createdAt: '' }
    const wrapper = mount(ResumeListView, { global: { plugins: [pinia], stubs: { RouterLink: true } } }); await flushPromises()
    await wrapper.get('.button-danger-quiet').trigger('click'); await wrapper.get('input').setValue('确认删除简历'); await wrapper.get('[data-test="confirm-delete"]').trigger('click'); await flushPromises()
    expect(lifecycleApi.deleteResume).toHaveBeenCalledWith('resume-1', { confirmationText: '确认删除简历', expectedVersion: 2 }); expect(lifecycleApi.adminSoftDeleteResume).not.toHaveBeenCalled()
  })

  it('uses the administrator soft-delete route for an ADMIN', async () => {
    const auth = useAuthStore(); auth.user = { id: 'admin-1', username: 'admin', email: 'admin@example.com', role: 'ADMIN', createdAt: '' }
    const wrapper = mount(ResumeListView, { global: { plugins: [pinia], stubs: { RouterLink: { props: ['to'], template: '<a :data-to="to"><slot /></a>' } } } }); await flushPromises()
    expect(wrapper.find('[data-to="/recovery"]').exists()).toBe(false)
    expect(wrapper.find('[data-to="/admin/recovery"]').exists()).toBe(true)
    await wrapper.get('.button-danger-quiet').trigger('click'); await wrapper.get('input').setValue('确认删除简历'); await wrapper.get('[data-test="confirm-delete"]').trigger('click'); await flushPromises()
    expect(lifecycleApi.adminSoftDeleteResume).toHaveBeenCalledWith('resume-1', { confirmationText: '确认删除简历', expectedVersion: 2 }); expect(lifecycleApi.deleteResume).not.toHaveBeenCalled()
  })
})
