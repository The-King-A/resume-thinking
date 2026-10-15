// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { nextTick } from 'vue'
import MatchResultView from './MatchResultView.vue'
import { ApiError } from '../api/contracts'

const { lifecycleApi, routeParams, routeQuery, notify } = vi.hoisted(() => ({ lifecycleApi: { getMatchTask: vi.fn(), getMatchResult: vi.fn(), getResumeMatchContext: vi.fn(), deleteV3Resume: vi.fn() }, routeParams: { taskId: 'task001', proxy: null as { taskId: string } | null }, routeQuery: { candidate: '', publication: '', proxy: null as { candidate: string; publication: string } | null }, notify: vi.fn() }))
vi.mock('../api/lifecycle', () => ({ lifecycleApi }))
vi.mock('../ui/notifications', () => ({ showTopNotification: notify }))
vi.mock('vue-router', async () => {
  const { reactive } = await import('vue')
  const params = reactive(routeParams)
  const query = reactive(routeQuery)
  routeParams.proxy = params
  routeQuery.proxy = query
  return { useRoute: () => ({ params, query }), useRouter: () => ({ push: vi.fn(), replace: vi.fn().mockResolvedValue(undefined) }) }
})

describe('MatchResultView task lifecycle', () => {
  beforeEach(() => { vi.useFakeTimers(); if (routeParams.proxy) routeParams.proxy.taskId = 'task001'; if (routeQuery.proxy) { routeQuery.proxy.candidate = ''; routeQuery.proxy.publication = '' }; lifecycleApi.getMatchTask.mockReset(); lifecycleApi.getMatchResult.mockReset(); lifecycleApi.getResumeMatchContext.mockReset(); lifecycleApi.deleteV3Resume.mockReset(); notify.mockReset() })
  afterEach(() => vi.useRealTimers())

  it('polls while queued and stops after a failure without requesting a result', async () => {
    lifecycleApi.getMatchTask
      .mockResolvedValueOnce({ id: 'task001', state: 'QUEUED' })
      .mockResolvedValueOnce({ id: 'task001', state: 'FAILED', failureCode: 'MODEL_OUTPUT_INVALID' })
    const wrapper = mount(MatchResultView, { global: { stubs: { RouterLink: true, MatchEvidenceTable: true } } })
    await flushPromises()
    expect(wrapper.text()).toContain('排队中')

    await vi.advanceTimersByTimeAsync(1500)
    await flushPromises()
    expect(wrapper.text()).toContain('失败')
    expect(wrapper.text()).toContain('模型返回的数据无法解析')
    expect(wrapper.text()).not.toContain('MODEL_OUTPUT_INVALID')
    await vi.advanceTimersByTimeAsync(3000)
    expect(lifecycleApi.getMatchTask).toHaveBeenCalledTimes(2)
    expect(lifecycleApi.getMatchResult).not.toHaveBeenCalled()
  })

  it('shows callback timeout guidance and does not poll a timed-out task', async () => {
    lifecycleApi.getMatchTask.mockResolvedValue({ id: 'task001', state: 'TIMED_OUT', failureCode: 'CALLBACK_DELIVERY_FAILED' })
    const wrapper = mount(MatchResultView, { global: { stubs: { RouterLink: true, MatchEvidenceTable: true } } })
    await flushPromises()

    expect(wrapper.text()).toContain('已超时')
    expect(wrapper.text()).toContain('分析回调超时，可以稍后重新发起匹配')
    await vi.advanceTimersByTimeAsync(3000)
    expect(lifecycleApi.getMatchTask).toHaveBeenCalledTimes(1)
    expect(lifecycleApi.getMatchResult).not.toHaveBeenCalled()
  })

  it('shows result-not-ready without leaking backend details on a 409', async () => {
    lifecycleApi.getMatchTask.mockResolvedValue({ id: 'task001', state: 'SUCCEEDED' })
    lifecycleApi.getMatchResult.mockRejectedValue(new ApiError({ code: 'TASK_NOT_READY', message: 'private backend detail', correlationId: 'c', retryable: true }, 409))
    const wrapper = mount(MatchResultView, { global: { stubs: { RouterLink: true, MatchEvidenceTable: true } } })
    await flushPromises()
    expect(wrapper.text()).toContain('结果尚未就绪')
    expect(wrapper.text()).not.toContain('private backend detail')
  })

  it('retries a temporarily unavailable result until the completed result is available', async () => {
    lifecycleApi.getMatchTask.mockResolvedValue({ id: 'task001', state: 'SUCCEEDED' })
    lifecycleApi.getMatchResult
      .mockRejectedValueOnce(new ApiError({ code: 'TASK_NOT_READY', message: 'private backend detail', correlationId: 'c', retryable: true }, 409))
      .mockResolvedValueOnce({ taskId: 'task001', resumeId: 'resume001', resumeVersion: 1, jobDescriptionText: 'Java backend engineer.', score: { skills: .5, projectExperience: .5, workContent: .5, educationExperience: .5, softSkills: .5, composite: .5 }, requirements: [], suggestions: [], completedAt: '2026-08-27T08:00:00Z' })
    const wrapper = mount(MatchResultView, { global: { stubs: { RouterLink: true, MatchEvidenceTable: true } } })
    await flushPromises()
    expect(lifecycleApi.getMatchResult).toHaveBeenCalledTimes(1)
    expect(wrapper.text()).toContain('结果尚未就绪')

    await vi.advanceTimersByTimeAsync(1500)
    await flushPromises()
    expect(lifecycleApi.getMatchResult).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).toContain('Java backend engineer.')
    expect(wrapper.text()).not.toContain('结果尚未就绪')
  })

  it('shows archived state on a 410 TASK_GONE', async () => {
    lifecycleApi.getMatchTask.mockRejectedValue(new ApiError({ code: 'TASK_GONE', message: 'private backend detail', correlationId: 'c', retryable: false }, 410))
    const wrapper = mount(MatchResultView, { global: { stubs: { RouterLink: true, MatchEvidenceTable: true } } })
    await flushPromises()
    expect(wrapper.text()).toContain('任务已归档')
    expect(wrapper.text()).not.toContain('private backend detail')
  })

  it('reloads a new task when the route parameter changes', async () => {
    lifecycleApi.getMatchTask.mockResolvedValue({ id: 'task001', state: 'FAILED' })
    mount(MatchResultView, { global: { stubs: { RouterLink: true, MatchEvidenceTable: true } } })
    await flushPromises()
    if (!routeParams.proxy) throw new Error('route proxy missing')
    routeParams.proxy.taskId = 'task002'
    await nextTick()
    await flushPromises()
    expect(lifecycleApi.getMatchTask).toHaveBeenLastCalledWith('task002')
  })

  it('ignores a late response from the previous route task', async () => {
    let resolveOld!: (value: { id: string; state: 'PROCESSING' }) => void
    const oldResponse = new Promise<{ id: string; state: 'PROCESSING' }>((resolve) => { resolveOld = resolve })
    lifecycleApi.getMatchTask.mockImplementation((id: string) => id === 'task001' ? oldResponse : Promise.resolve({ id: 'task002', state: 'FAILED' }))
    const wrapper = mount(MatchResultView, { global: { stubs: { RouterLink: true, MatchEvidenceTable: true } } })
    await nextTick()
    if (!routeParams.proxy) throw new Error('route proxy missing')
    routeParams.proxy.taskId = 'task002'
    await nextTick()
    await flushPromises()
    expect(wrapper.text()).toContain('失败')

    resolveOld({ id: 'task001', state: 'PROCESSING' })
    await flushPromises()
    await vi.advanceTimersByTimeAsync(1500)
    expect(wrapper.text()).toContain('失败')
    expect(wrapper.text()).not.toContain('正在匹配')
    expect(lifecycleApi.getMatchTask).toHaveBeenCalledWith('task002')
  })

  it('clears processing state when a polled task is archived', async () => {
    lifecycleApi.getMatchTask
      .mockResolvedValueOnce({ id: 'task001', state: 'PROCESSING' })
      .mockRejectedValueOnce(new ApiError({ code: 'TASK_GONE', message: 'private backend detail', correlationId: 'c', retryable: false }, 410))
    const wrapper = mount(MatchResultView, { global: { stubs: { RouterLink: true, MatchEvidenceTable: true } } })
    await flushPromises()
    expect(wrapper.text()).toContain('正在匹配')

    await vi.advanceTimersByTimeAsync(1500)
    await flushPromises()
    expect(wrapper.text()).toContain('任务已归档')
    expect(wrapper.text()).not.toContain('正在匹配')
  })

  it('shows the Java job description on a completed result', async () => {
    lifecycleApi.getMatchTask.mockResolvedValue({ id: 'task001', state: 'SUCCEEDED' })
    lifecycleApi.getMatchResult.mockResolvedValue({ taskId: 'task001', resumeId: 'resume001', resumeVersion: 2, jobDescriptionText: 'Java backend engineer with Spring Boot.', score: { skills: .5, projectExperience: .5, workContent: .5, educationExperience: .5, softSkills: .5, composite: .5 }, requirements: [], suggestions: [], completedAt: '2026-08-27T08:00:00Z' })
    const wrapper = mount(MatchResultView, { global: { stubs: { RouterLink: true, MatchEvidenceTable: true } } })
    await flushPromises()
    expect(wrapper.text()).toContain('Java backend engineer with Spring Boot.')
  })

  it('renders the valid report and stops polling when v2 status reports duplicate title', async () => {
    lifecycleApi.getMatchTask.mockRejectedValue(new ApiError({ code: 'DUPLICATE_RESOURCE', message: 'private backend detail', correlationId: 'c', retryable: false }, 409))
    lifecycleApi.getMatchResult.mockResolvedValue({ taskId: 'task003', resumeId: 'resume003', resumeVersion: 1, jobDescriptionText: 'Java backend engineer.', score: { skills: .5, projectExperience: .5, workContent: .5, educationExperience: .5, softSkills: .5, composite: .5 }, requirements: [], suggestions: [], completedAt: '2026-08-27T08:00:00Z' })
    const wrapper = mount(MatchResultView, { global: { stubs: { RouterLink: true, MatchEvidenceTable: true } } })
    await flushPromises()

    expect(wrapper.text()).toContain('报告有效，但该候选简历未成为有效简历')
    expect(wrapper.text()).toContain('Java backend engineer.')
    expect(wrapper.text()).not.toContain('private backend detail')
    expect(lifecycleApi.getMatchResult).toHaveBeenCalledWith('task001')
    await vi.advanceTimersByTimeAsync(3000)
    expect(lifecycleApi.getMatchTask).toHaveBeenCalledTimes(1)
  })

  it('does not label an ordinary report as rejected from a forged publication query', async () => {
    if (!routeQuery.proxy) throw new Error('route query proxy missing')
    routeQuery.proxy.publication = 'rejected-duplicate-title'
    lifecycleApi.getMatchTask.mockResolvedValue({ id: 'task001', resumeId: 'resume001', state: 'SUCCEEDED' })
    lifecycleApi.getMatchResult.mockResolvedValue({ taskId: 'task001', resumeId: 'resume001', resumeVersion: 1, jobDescriptionText: 'Java backend engineer.', score: { skills: .5, projectExperience: .5, workContent: .5, educationExperience: .5, softSkills: .5, composite: .5 }, requirements: [], suggestions: [], completedAt: '2026-08-27T08:00:00Z' })
    const wrapper = mount(MatchResultView, { global: { stubs: { RouterLink: true, MatchEvidenceTable: true } } })
    await flushPromises()

    expect(lifecycleApi.getMatchTask).toHaveBeenCalledWith('task001')
    expect(wrapper.text()).not.toContain('报告有效，但该候选简历未成为有效简历')
  })

  it('hides pending-candidate deletion when safe context has an effective revision despite a forged query', async () => {
    if (!routeQuery.proxy) throw new Error('route query proxy missing')
    routeQuery.proxy.candidate = 'pending'
    lifecycleApi.getMatchTask.mockResolvedValue({ id: 'task004', resumeId: 'resume004', state: 'FAILED', resumeVersion: 3 })
    lifecycleApi.getResumeMatchContext.mockResolvedValue({ title: 'effective', effectiveRevisionId: 'revision001', pendingRevisionId: 'revision002', latestSuccessfulTaskId: 'task001', llmProfileId: 'profile001', jobDescriptionText: 'Java backend engineer with safe revision checks.' })
    const wrapper = mount(MatchResultView, { global: { stubs: { RouterLink: true, MatchEvidenceTable: true } } })
    await flushPromises()

    expect(lifecycleApi.getResumeMatchContext).toHaveBeenCalledWith('resume004')
    expect(wrapper.find('[data-test="delete-pending-candidate"]').exists()).toBe(false)
  })

  it('allows confirmed deletion of a failed pending candidate', async () => {
    if (!routeParams.proxy) throw new Error('route params proxy missing')
    routeParams.proxy.taskId = 'task004'
    lifecycleApi.getMatchTask.mockResolvedValue({ id: 'task004', resumeId: 'resume004', state: 'FAILED', resumeVersion: 3 })
    lifecycleApi.getResumeMatchContext.mockResolvedValue({ title: 'pending', effectiveRevisionId: null, pendingRevisionId: 'revision004', latestSuccessfulTaskId: null, llmProfileId: 'profile001', jobDescriptionText: 'Java backend engineer with safe revision checks.' })
    lifecycleApi.deleteV3Resume.mockResolvedValue(undefined)
    const wrapper = mount(MatchResultView, { global: { stubs: { RouterLink: true, MatchEvidenceTable: true } } })
    await flushPromises()
    await wrapper.get('[data-test="delete-pending-candidate"]').trigger('click')
    await wrapper.get('input').setValue('确认删除简历')
    await wrapper.get('[data-test="confirm-delete"]').trigger('click')
    await flushPromises()

    expect(lifecycleApi.deleteV3Resume).toHaveBeenCalledWith('resume004', { confirmationText: '确认删除简历', expectedVersion: 3 })
    expect(wrapper.text()).toContain('已删除该候选简历')
  })

  it('does not report a completed candidate deletion after the task route changes', async () => {
    if (!routeParams.proxy) throw new Error('route params proxy missing')
    routeParams.proxy.taskId = 'task004'
    let resolveDeletion!: () => void
    lifecycleApi.getMatchTask.mockImplementation((taskId: string) => Promise.resolve({ id: taskId, resumeId: taskId === 'task004' ? 'resume004' : 'resume005', state: 'FAILED', resumeVersion: 3 }))
    lifecycleApi.getResumeMatchContext.mockResolvedValue({ title: 'pending', effectiveRevisionId: null, pendingRevisionId: 'revision004', latestSuccessfulTaskId: null, llmProfileId: 'profile001', jobDescriptionText: 'Java backend engineer with safe revision checks.' })
    lifecycleApi.deleteV3Resume.mockImplementation(() => new Promise<void>((resolve) => { resolveDeletion = resolve }))
    const wrapper = mount(MatchResultView, { global: { stubs: { RouterLink: true, MatchEvidenceTable: true } } })
    await flushPromises()
    await wrapper.get('[data-test="delete-pending-candidate"]').trigger('click')
    await wrapper.get('input').setValue('确认删除简历')
    await wrapper.get('[data-test="confirm-delete"]').trigger('click')
    if (!routeParams.proxy) throw new Error('route params proxy missing')
    routeParams.proxy.taskId = 'task005'
    await nextTick()
    await flushPromises()
    resolveDeletion()
    await flushPromises()

    expect(wrapper.text()).not.toContain('已删除该候选简历')
    expect(notify.mock.calls.some(([message]) => String(message).includes('已删除该候选简历'))).toBe(false)
  })

  it('does not fetch a duplicate report or notify after unmount', async () => {
    let rejectStatus!: (reason: unknown) => void
    lifecycleApi.getMatchTask.mockImplementation(() => new Promise<never>((_resolve, reject) => { rejectStatus = reject }))
    const wrapper = mount(MatchResultView, { global: { stubs: { RouterLink: true, MatchEvidenceTable: true } } })
    await flushPromises()
    wrapper.unmount()
    rejectStatus(new ApiError({ code: 'DUPLICATE_RESOURCE', message: 'private backend detail', correlationId: 'c', retryable: false }, 409))
    await flushPromises()

    expect(lifecycleApi.getMatchResult).not.toHaveBeenCalled()
    expect(notify.mock.calls.some(([message]) => String(message).includes('未成为有效简历'))).toBe(false)
  })
})
