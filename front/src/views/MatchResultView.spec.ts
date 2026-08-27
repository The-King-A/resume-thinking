// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { nextTick } from 'vue'
import MatchResultView from './MatchResultView.vue'
import { ApiError } from '../api/contracts'

const { lifecycleApi, routeParams } = vi.hoisted(() => ({ lifecycleApi: { getMatchTask: vi.fn(), getMatchResult: vi.fn() }, routeParams: { taskId: 'task-1', proxy: null as { taskId: string } | null } }))
vi.mock('../api/lifecycle', () => ({ lifecycleApi }))
vi.mock('vue-router', async () => {
  const { reactive } = await import('vue')
  const params = reactive(routeParams)
  routeParams.proxy = params
  return { useRoute: () => ({ params }) }
})

describe('MatchResultView task lifecycle', () => {
  beforeEach(() => { vi.useFakeTimers(); if (routeParams.proxy) routeParams.proxy.taskId = 'task-1'; lifecycleApi.getMatchTask.mockReset(); lifecycleApi.getMatchResult.mockReset() })
  afterEach(() => vi.useRealTimers())

  it('polls while queued and stops after a failure without requesting a result', async () => {
    lifecycleApi.getMatchTask
      .mockResolvedValueOnce({ id: 'task-1', state: 'QUEUED' })
      .mockResolvedValueOnce({ id: 'task-1', state: 'FAILED', failureCode: 'MODEL_OUTPUT_INVALID' })
    const wrapper = mount(MatchResultView, { global: { stubs: { RouterLink: true, MatchEvidenceTable: true } } })
    await flushPromises()
    expect(wrapper.text()).toContain('Queued')

    await vi.advanceTimersByTimeAsync(1500)
    await flushPromises()
    expect(wrapper.text()).toContain('Failed')
    await vi.advanceTimersByTimeAsync(3000)
    expect(lifecycleApi.getMatchTask).toHaveBeenCalledTimes(2)
    expect(lifecycleApi.getMatchResult).not.toHaveBeenCalled()
  })

  it('shows result-not-ready without leaking backend details on a 409', async () => {
    lifecycleApi.getMatchTask.mockResolvedValue({ id: 'task-1', state: 'SUCCEEDED' })
    lifecycleApi.getMatchResult.mockRejectedValue(new ApiError({ code: 'TASK_NOT_READY', message: 'private backend detail', correlationId: 'c', retryable: true }, 409))
    const wrapper = mount(MatchResultView, { global: { stubs: { RouterLink: true, MatchEvidenceTable: true } } })
    await flushPromises()
    expect(wrapper.text()).toContain('Result not ready')
    expect(wrapper.text()).not.toContain('private backend detail')
  })

  it('retries a temporarily unavailable result until the completed result is available', async () => {
    lifecycleApi.getMatchTask.mockResolvedValue({ id: 'task-1', state: 'SUCCEEDED' })
    lifecycleApi.getMatchResult
      .mockRejectedValueOnce(new ApiError({ code: 'TASK_NOT_READY', message: 'private backend detail', correlationId: 'c', retryable: true }, 409))
      .mockResolvedValueOnce({ taskId: 'task-1', resumeId: 'resume-1', resumeVersion: 1, jobDescriptionText: 'Java backend engineer.', score: { skills: .5, projectExperience: .5, workContent: .5, educationExperience: .5, softSkills: .5, composite: .5 }, requirements: [], suggestions: [], completedAt: '2026-08-27T08:00:00Z' })
    const wrapper = mount(MatchResultView, { global: { stubs: { RouterLink: true, MatchEvidenceTable: true } } })
    await flushPromises()
    expect(lifecycleApi.getMatchResult).toHaveBeenCalledTimes(1)
    expect(wrapper.text()).toContain('Result not ready')

    await vi.advanceTimersByTimeAsync(1500)
    await flushPromises()
    expect(lifecycleApi.getMatchResult).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).toContain('Java backend engineer.')
    expect(wrapper.text()).not.toContain('Result not ready')
  })

  it('shows archived state on a 410 TASK_GONE', async () => {
    lifecycleApi.getMatchTask.mockRejectedValue(new ApiError({ code: 'TASK_GONE', message: 'private backend detail', correlationId: 'c', retryable: false }, 410))
    const wrapper = mount(MatchResultView, { global: { stubs: { RouterLink: true, MatchEvidenceTable: true } } })
    await flushPromises()
    expect(wrapper.text()).toContain('Task archived')
    expect(wrapper.text()).not.toContain('private backend detail')
  })

  it('reloads a new task when the route parameter changes', async () => {
    lifecycleApi.getMatchTask.mockResolvedValue({ id: 'task-1', state: 'FAILED' })
    mount(MatchResultView, { global: { stubs: { RouterLink: true, MatchEvidenceTable: true } } })
    await flushPromises()
    if (!routeParams.proxy) throw new Error('route proxy missing')
    routeParams.proxy.taskId = 'task-2'
    await nextTick()
    await flushPromises()
    expect(lifecycleApi.getMatchTask).toHaveBeenLastCalledWith('task-2')
  })

  it('ignores a late response from the previous route task', async () => {
    let resolveOld!: (value: { id: string; state: 'PROCESSING' }) => void
    const oldResponse = new Promise<{ id: string; state: 'PROCESSING' }>((resolve) => { resolveOld = resolve })
    lifecycleApi.getMatchTask.mockImplementation((id: string) => id === 'task-1' ? oldResponse : Promise.resolve({ id: 'task-2', state: 'FAILED' }))
    const wrapper = mount(MatchResultView, { global: { stubs: { RouterLink: true, MatchEvidenceTable: true } } })
    await nextTick()
    if (!routeParams.proxy) throw new Error('route proxy missing')
    routeParams.proxy.taskId = 'task-2'
    await nextTick()
    await flushPromises()
    expect(wrapper.text()).toContain('Failed')

    resolveOld({ id: 'task-1', state: 'PROCESSING' })
    await flushPromises()
    await vi.advanceTimersByTimeAsync(1500)
    expect(wrapper.text()).toContain('Failed')
    expect(wrapper.text()).not.toContain('Matching in progress')
    expect(lifecycleApi.getMatchTask).toHaveBeenCalledWith('task-2')
  })

  it('clears processing state when a polled task is archived', async () => {
    lifecycleApi.getMatchTask
      .mockResolvedValueOnce({ id: 'task-1', state: 'PROCESSING' })
      .mockRejectedValueOnce(new ApiError({ code: 'TASK_GONE', message: 'private backend detail', correlationId: 'c', retryable: false }, 410))
    const wrapper = mount(MatchResultView, { global: { stubs: { RouterLink: true, MatchEvidenceTable: true } } })
    await flushPromises()
    expect(wrapper.text()).toContain('Matching in progress')

    await vi.advanceTimersByTimeAsync(1500)
    await flushPromises()
    expect(wrapper.text()).toContain('Task archived')
    expect(wrapper.text()).not.toContain('Matching in progress')
  })

  it('shows the Java job description on a completed result', async () => {
    lifecycleApi.getMatchTask.mockResolvedValue({ id: 'task-1', state: 'SUCCEEDED' })
    lifecycleApi.getMatchResult.mockResolvedValue({ taskId: 'task-1', resumeId: 'resume-1', resumeVersion: 2, jobDescriptionText: 'Java backend engineer with Spring Boot.', score: { skills: .5, projectExperience: .5, workContent: .5, educationExperience: .5, softSkills: .5, composite: .5 }, requirements: [], suggestions: [], completedAt: '2026-08-27T08:00:00Z' })
    const wrapper = mount(MatchResultView, { global: { stubs: { RouterLink: true, MatchEvidenceTable: true } } })
    await flushPromises()
    expect(wrapper.text()).toContain('Java backend engineer with Spring Boot.')
  })
})
