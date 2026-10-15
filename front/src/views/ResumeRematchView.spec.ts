// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { nextTick } from 'vue'
import ResumeRematchView from './ResumeRematchView.vue'
import { ApiError } from '../api/contracts'

const { lifecycleApi, profiles, router, routeParams, notify } = vi.hoisted(() => ({
  lifecycleApi: { getResumeMatchContext: vi.fn(), submitResumeRematch: vi.fn() },
  profiles: { profiles: [{ id: 'profile001', displayName: 'Default model', selected: true }], list: vi.fn().mockResolvedValue(undefined) },
  router: { push: vi.fn() },
  routeParams: { resumeId: 'resume001', proxy: null as { resumeId: string } | null },
  notify: vi.fn(),
}))
vi.mock('../api/lifecycle', () => ({ lifecycleApi }))
vi.mock('../stores/llmProfiles', () => ({ useLlmProfileStore: () => profiles }))
vi.mock('vue-router', async () => {
  const { reactive } = await import('vue')
  const params = reactive(routeParams)
  routeParams.proxy = params
  return { useRoute: () => ({ params }), useRouter: () => router }
})
vi.mock('../ui/notifications', () => ({ showTopNotification: notify }))

const context = { title: '原始简历', effectiveRevisionId: 'revision001', pendingRevisionId: null, latestSuccessfulTaskId: 'task001', llmProfileId: 'profile001', jobDescriptionText: 'Java backend engineer with Spring Boot experience.' }
const wrappers: Array<{ unmount: () => void }> = []

beforeEach(() => {
  vi.clearAllMocks()
  if (routeParams.proxy) routeParams.proxy.resumeId = 'resume001'
  profiles.list.mockResolvedValue(undefined)
  lifecycleApi.getResumeMatchContext.mockResolvedValue(context)
  lifecycleApi.submitResumeRematch.mockResolvedValue({ id: 'task002', resumeId: 'resume001', state: 'QUEUED' })
})
afterEach(() => { for (const wrapper of wrappers) wrapper.unmount(); wrappers.length = 0 })

function mountView() {
  const wrapper = mount(ResumeRematchView, { global: { stubs: { RouterLink: true } } })
  wrappers.push(wrapper)
  return wrapper
}

describe('ResumeRematchView', () => {
  it('prefills safe matching context without rendering resume source content', async () => {
    const wrapper = mountView()
    await flushPromises()

    expect(lifecycleApi.getResumeMatchContext).toHaveBeenCalledWith('resume001')
    expect((wrapper.get('[data-test="resume-title"]').element as HTMLInputElement).value).toBe('原始简历')
    expect((wrapper.get('select').element as HTMLSelectElement).value).toBe('profile001')
    expect((wrapper.get('textarea').element as HTMLTextAreaElement).value).toBe(context.jobDescriptionText)
    expect(wrapper.text()).not.toContain('ciphertext')
  })

  it('submits a title-only re-match with the existing source document', async () => {
    const wrapper = mountView()
    await flushPromises()
    await wrapper.get('[data-test="resume-title"]').setValue('更新后的标题')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(lifecycleApi.submitResumeRematch).toHaveBeenCalledWith('resume001', expect.objectContaining({ expectedEffectiveRevisionId: 'revision001', title: '更新后的标题', llmProfileId: 'profile001', jobFamily: 'JAVA_BACKEND', jobDescriptionText: context.jobDescriptionText, idempotencyKey: expect.stringMatching(/^match-/) }))
    expect(lifecycleApi.submitResumeRematch.mock.calls[0]?.[1]).not.toHaveProperty('file')
    expect(router.push).toHaveBeenCalledWith('/matches/task002')
  })

  it('includes an optional replacement file when provided', async () => {
    const wrapper = mountView()
    await flushPromises()
    const file = new File(['replacement'], 'replacement.txt', { type: 'text/plain' })
    const input = wrapper.get('input[type="file"]')
    Object.defineProperty(input.element, 'files', { configurable: true, value: [file] })
    await input.trigger('change')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(lifecycleApi.submitResumeRematch).toHaveBeenCalledWith('resume001', expect.objectContaining({ file }))
  })

  it('shows a safe duplicate-title error without provider or backend detail', async () => {
    lifecycleApi.submitResumeRematch.mockRejectedValue(new ApiError({ code: 'DUPLICATE_RESOURCE', message: 'private provider detail', correlationId: 'c', retryable: false, detailCode: 'DUPLICATE_RESUME_TITLE' }, 409))
    const wrapper = mountView()
    await flushPromises()
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(wrapper.text()).toContain('简历标题已存在')
    expect(wrapper.text()).not.toContain('private provider detail')
  })

  it('handles a stale effective revision without exposing backend detail', async () => {
    lifecycleApi.submitResumeRematch.mockRejectedValue(new ApiError({ code: 'VERSION_CONFLICT', message: 'private backend detail', correlationId: 'c', retryable: false }, 409))
    const wrapper = mountView()
    await flushPromises()
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(wrapper.text()).toContain('有效版本已更新')
    expect(wrapper.text()).not.toContain('private backend detail')
  })

  it('reloads context and submits to the current resume when the route parameter changes', async () => {
    const secondContext = { ...context, title: '第二份简历', effectiveRevisionId: 'revision002', jobDescriptionText: 'Java backend engineer with reliable distributed systems experience.' }
    lifecycleApi.getResumeMatchContext.mockImplementation((resumeId: string) => Promise.resolve(resumeId === 'resume002' ? secondContext : context))
    lifecycleApi.submitResumeRematch.mockResolvedValue({ id: 'task003', resumeId: 'resume002', state: 'QUEUED' })
    const wrapper = mountView()
    await flushPromises()
    if (!routeParams.proxy) throw new Error('route params proxy missing')
    routeParams.proxy.resumeId = 'resume002'
    await nextTick()
    await flushPromises()
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect((wrapper.get('[data-test="resume-title"]').element as HTMLInputElement).value).toBe('第二份简历')
    expect(lifecycleApi.getResumeMatchContext).toHaveBeenLastCalledWith('resume002')
    expect(lifecycleApi.submitResumeRematch).toHaveBeenCalledWith('resume002', expect.objectContaining({ expectedEffectiveRevisionId: 'revision002' }))
  })

  it('does not surface an old submission error after the re-match route changes', async () => {
    const secondContext = { ...context, title: '第二份简历', effectiveRevisionId: 'revision002', jobDescriptionText: 'Java backend engineer with reliable distributed systems experience.' }
    let rejectSubmission!: (reason: unknown) => void
    lifecycleApi.getResumeMatchContext.mockImplementation((resumeId: string) => Promise.resolve(resumeId === 'resume002' ? secondContext : context))
    lifecycleApi.submitResumeRematch.mockImplementation(() => new Promise<never>((_resolve, reject) => { rejectSubmission = reject }))
    const wrapper = mountView()
    await flushPromises()
    await wrapper.get('form').trigger('submit')
    if (!routeParams.proxy) throw new Error('route params proxy missing')
    routeParams.proxy.resumeId = 'resume002'
    await nextTick()
    await flushPromises()
    rejectSubmission(new ApiError({ code: 'VERSION_CONFLICT', message: 'private backend detail', correlationId: 'c', retryable: false }, 409))
    await flushPromises()

    expect(wrapper.text()).not.toContain('有效版本已更新')
    expect(router.push).not.toHaveBeenCalled()
    expect(notify).not.toHaveBeenCalled()
  })

  it('does not navigate or notify when an unmounted re-match submission succeeds', async () => {
    let resolveSubmission!: (value: { id: string; resumeId: string; state: 'QUEUED' }) => void
    lifecycleApi.submitResumeRematch.mockImplementation(() => new Promise((resolve) => { resolveSubmission = resolve }))
    const wrapper = mountView()
    await flushPromises()
    await wrapper.get('form').trigger('submit')
    wrapper.unmount()
    resolveSubmission({ id: 'task003', resumeId: 'resume001', state: 'QUEUED' })
    await flushPromises()

    expect(router.push).not.toHaveBeenCalled()
    expect(notify).not.toHaveBeenCalled()
  })
})
