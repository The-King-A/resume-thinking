// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ResumeListView from './ResumeListView.vue'
import { useAuthStore } from '../stores/auth'

const { lifecycleApi } = vi.hoisted(() => ({ lifecycleApi: { listEffectiveResumes: vi.fn(), deleteV3Resume: vi.fn() } }))
vi.mock('../api/lifecycle', () => ({ lifecycleApi }))

const active = { id: 'resume001', ownerId: 'user001', title: 'resume', sourceType: 'TXT', status: 0, version: 2, effectiveRevisionId: 'revision001', pendingRevisionId: null, latestSuccessfulTaskId: 'task001', createdAt: '', updatedAt: '' }
const withoutReport = { ...active, id: 'resume002', title: 'pending report', latestSuccessfulTaskId: null }
const page = () => ({ items: [active], page: 1, pageSize: 20, totalItems: 1, totalPages: 1 })

describe('ResumeListView role-scoped deletion', () => {
  let pinia: ReturnType<typeof createPinia>
  beforeEach(() => {
    pinia = createPinia(); setActivePinia(pinia); lifecycleApi.listEffectiveResumes.mockReset().mockResolvedValue(page()); lifecycleApi.deleteV3Resume.mockReset().mockResolvedValue(undefined)
  })

  it('uses the user delete route for a USER', async () => {
    const auth = useAuthStore(); auth.user = { id: 'user001', username: 'user', email: 'user@example.com', role: 'USER', createdAt: '' }
    const wrapper = mount(ResumeListView, { global: { plugins: [pinia], stubs: { RouterLink: true } } }); await flushPromises()
    await wrapper.get('.button-danger-quiet').trigger('click'); await wrapper.get('input').setValue('确认删除简历'); await wrapper.get('[data-test="confirm-delete"]').trigger('click'); await flushPromises()
    expect(lifecycleApi.deleteV3Resume).toHaveBeenCalledWith('resume001', { confirmationText: '确认删除简历', expectedVersion: 2 })
  })

  it('uses the administrator soft-delete route for an ADMIN', async () => {
    const auth = useAuthStore(); auth.user = { id: 'user002', username: 'admin', email: 'admin@example.com', role: 'ADMIN', createdAt: '' }
    const wrapper = mount(ResumeListView, { global: { plugins: [pinia], stubs: { RouterLink: { props: ['to'], template: '<a :data-to="to"><slot /></a>' } } } }); await flushPromises()
    expect(wrapper.find('[data-to="/recovery"]').exists()).toBe(false)
    expect(wrapper.find('[data-to="/admin/recovery"]').exists()).toBe(true)
    await wrapper.get('.button-danger-quiet').trigger('click'); await wrapper.get('input').setValue('确认删除简历'); await wrapper.get('[data-test="confirm-delete"]').trigger('click'); await flushPromises()
    expect(lifecycleApi.deleteV3Resume).toHaveBeenCalledWith('resume001', { confirmationText: '确认删除简历', expectedVersion: 2 })
  })

  it('shows re-match for every effective resume and a report link only when one is available', async () => {
    lifecycleApi.listEffectiveResumes.mockResolvedValue({ items: [active, withoutReport], page: 1, pageSize: 20, totalItems: 2, totalPages: 1 })
    const auth = useAuthStore(); auth.user = { id: 'user001', username: 'user', email: 'user@example.com', role: 'USER', createdAt: '' }
    const wrapper = mount(ResumeListView, { global: { plugins: [pinia], stubs: { RouterLink: { props: ['to'], template: '<a :data-to="to"><slot /></a>' } } } })
    await flushPromises()

    expect(wrapper.text()).toContain('重新匹配')
    expect(wrapper.find('[data-to="/resumes/resume001/rematch"]').exists()).toBe(true)
    expect(wrapper.find('[data-to="/matches/task001"]').text()).toContain('查看匹配报告')
    expect(wrapper.find('[data-to="/matches/undefined"]').exists()).toBe(false)
  })
})
