// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import MatchResultView from './MatchResultView.vue'
import { ApiError } from '../api/contracts'

const { lifecycleApi } = vi.hoisted(() => ({ lifecycleApi: { getMatchTask: vi.fn(), getMatchResult: vi.fn() } }))
vi.mock('../api/lifecycle', () => ({ lifecycleApi }))
vi.mock('vue-router', () => ({ useRoute: () => ({ params: { taskId: 'task-1' } }) }))

describe('MatchResultView task lifecycle', () => {
  beforeEach(() => { vi.useFakeTimers(); lifecycleApi.getMatchTask.mockReset(); lifecycleApi.getMatchResult.mockReset() })
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

  it('shows archived state on a 410 TASK_GONE', async () => {
    lifecycleApi.getMatchTask.mockRejectedValue(new ApiError({ code: 'TASK_GONE', message: 'private backend detail', correlationId: 'c', retryable: false }, 410))
    const wrapper = mount(MatchResultView, { global: { stubs: { RouterLink: true, MatchEvidenceTable: true } } })
    await flushPromises()
    expect(wrapper.text()).toContain('Task archived')
    expect(wrapper.text()).not.toContain('private backend detail')
  })
})
