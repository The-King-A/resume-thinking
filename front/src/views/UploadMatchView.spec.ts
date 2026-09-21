// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import UploadMatchView from './UploadMatchView.vue'
import { ApiError } from '../api/contracts'

const { lifecycleApi, profiles, notify } = vi.hoisted(() => ({
  lifecycleApi: { submitInitialMatch: vi.fn(), getMatchTask: vi.fn() },
  profiles: { profiles: [{ id: 'profile001', displayName: 'Default model', selected: true }, { id: 'profile002', displayName: 'Alternate model', selected: false }], list: vi.fn().mockResolvedValue(undefined) },
  notify: vi.fn(),
}))
vi.mock('../api/lifecycle', () => ({ lifecycleApi }))
vi.mock('../stores/llmProfiles', () => ({ useLlmProfileStore: () => profiles }))
vi.mock('../ui/notifications', () => ({ showTopNotification: notify }))

const mountedWrappers: Array<{ unmount: () => void }> = []

afterEach(() => {
  for (const wrapper of mountedWrappers) wrapper.unmount()
  mountedWrappers.length = 0
  vi.useRealTimers()
})

beforeEach(() => {
  vi.clearAllMocks()
  profiles.list.mockResolvedValue(undefined)
})

describe('UploadMatchView', () => {
  it('accepts only TXT and DOCX and explicitly rejects PDF', async () => {
    const wrapper = mount(UploadMatchView, { global: { stubs: { RouterLink: true } } })
    mountedWrappers.push(wrapper)
    const fileInput = wrapper.get('input[type="file"]')
    expect(fileInput.attributes('accept')).toBe('.txt,.docx')
    Object.defineProperty(fileInput.element, 'files', { configurable: true, value: [new File(['pdf'], 'resume.pdf', { type: 'application/pdf' })] })
    await fileInput.trigger('change')
    expect(wrapper.text()).toContain('不支持 PDF 文件')
    expect(wrapper.get('[data-test="start-match"]').attributes('disabled')).toBeDefined()
  })

  it('sends the uploaded resume, selected profile, Java job text, and an idempotency key', async () => {
    lifecycleApi.submitInitialMatch.mockResolvedValue({ id: 'task001', resumeId: 'resume001', state: 'QUEUED' })
    const wrapper = mount(UploadMatchView, { global: { stubs: { RouterLink: true } } })
    mountedWrappers.push(wrapper)
    await flushPromises()
    const fileInput = wrapper.get('input[type="file"]')
    const file = new File(['resume'], 'resume.txt', { type: 'text/plain' })
    Object.defineProperty(fileInput.element, 'files', { configurable: true, value: [file] })
    await fileInput.trigger('change')
    await wrapper.get('textarea').setValue('Java backend engineer with Spring Boot experience.')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(lifecycleApi.submitInitialMatch).toHaveBeenCalledWith(expect.objectContaining({
      file, llmProfileId: 'profile001', jobFamily: 'JAVA_BACKEND', jobDescriptionText: 'Java backend engineer with Spring Boot experience.',
      idempotencyKey: expect.stringMatching(/^match-/),
    }))
    expect(wrapper.text()).toContain('排队中')
  })

  it('allows choosing any saved profile, while defaulting to the selected one', async () => {
    const wrapper = mount(UploadMatchView, { global: { stubs: { RouterLink: true } } })
    mountedWrappers.push(wrapper)
    await flushPromises()
    expect(wrapper.get('select').element.value).toBe('profile001')
    expect(wrapper.text()).toContain('Alternate model')
    await wrapper.get('select').setValue('profile002')
    expect((wrapper.get('select').element as HTMLSelectElement).value).toBe('profile002')
  })

  it('shows a safe Chinese failure message without exposing the internal code', async () => {
    vi.useFakeTimers()
    lifecycleApi.submitInitialMatch.mockResolvedValue({ id: 'task001', resumeId: 'resume001', state: 'QUEUED' })
    lifecycleApi.getMatchTask.mockResolvedValue({ id: 'task001', state: 'FAILED', failureCode: 'MODEL_OUTPUT_INVALID' })
    const wrapper = mount(UploadMatchView, { global: { stubs: { RouterLink: true } } })
    mountedWrappers.push(wrapper)
    await flushPromises()
    const fileInput = wrapper.get('input[type="file"]')
    const file = new File(['resume'], 'resume.txt', { type: 'text/plain' })
    Object.defineProperty(fileInput.element, 'files', { configurable: true, value: [file] })
    await fileInput.trigger('change')
    await wrapper.get('textarea').setValue('Java backend engineer with Spring Boot experience.')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    await vi.advanceTimersByTimeAsync(1500)
    await flushPromises()

    expect(wrapper.text()).toContain('模型返回的数据无法解析')
    expect(wrapper.text()).not.toContain('MODEL_OUTPUT_INVALID')
  })

  it('shows the Python service recovery guidance and stops polling after the task times out', async () => {
    vi.useFakeTimers()
    lifecycleApi.submitInitialMatch.mockResolvedValue({ id: 'task001', resumeId: 'resume001', state: 'QUEUED' })
    lifecycleApi.getMatchTask.mockResolvedValue({ id: 'task001', state: 'TIMED_OUT', failureCode: 'PYTHON_SERVICE_UNAVAILABLE' })
    const wrapper = mount(UploadMatchView, { global: { stubs: { RouterLink: true } } })
    mountedWrappers.push(wrapper)
    await flushPromises()
    const fileInput = wrapper.get('input[type="file"]')
    Object.defineProperty(fileInput.element, 'files', { configurable: true, value: [new File(['resume'], 'resume.txt', { type: 'text/plain' })] })
    await fileInput.trigger('change')
    await wrapper.get('textarea').setValue('Java backend engineer with Spring Boot experience.')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    await vi.advanceTimersByTimeAsync(1500)
    await flushPromises()

    expect(wrapper.text()).toContain('已超时')
    expect(wrapper.text()).toContain('请确认 Python 分析服务已启动后重试')
    await vi.advanceTimersByTimeAsync(3000)
    expect(lifecycleApi.getMatchTask).toHaveBeenCalledTimes(1)
  })

  it('ignores a late poll from a prior submission after a new task replaces it', async () => {
    vi.useFakeTimers()
    let rejectFirstPoll!: (reason: unknown) => void
    const firstPoll = new Promise<never>((_resolve, reject) => { rejectFirstPoll = reject })
    lifecycleApi.submitInitialMatch
      .mockResolvedValueOnce({ id: 'task001', resumeId: 'resume001', state: 'QUEUED' })
      .mockResolvedValueOnce({ id: 'task002', resumeId: 'resume002', state: 'QUEUED' })
    lifecycleApi.getMatchTask.mockImplementation((id: string) => id === 'task001' ? firstPoll : Promise.resolve({ id: 'task002', resumeId: 'resume002', state: 'QUEUED' }))
    const wrapper = mount(UploadMatchView, { global: { stubs: { RouterLink: true } } })
    mountedWrappers.push(wrapper)
    await flushPromises()
    const fileInput = wrapper.get('input[type="file"]')
    Object.defineProperty(fileInput.element, 'files', { configurable: true, value: [new File(['resume'], 'resume.txt', { type: 'text/plain' })] })
    await fileInput.trigger('change')
    await wrapper.get('textarea').setValue('Java backend engineer with Spring Boot experience.')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    await vi.advanceTimersByTimeAsync(1500)
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    rejectFirstPoll(new ApiError({ code: 'DUPLICATE_RESOURCE', message: 'private backend detail', correlationId: 'c', retryable: false }, 409))
    await flushPromises()

    expect(wrapper.text()).toContain('task002')
    expect(wrapper.text()).not.toContain('报告有效，但该候选简历未成为有效简历')
    expect(notify.mock.calls.some(([message]) => String(message).includes('未成为有效简历'))).toBe(false)
  })

  it('does not notify after an unmounted poll resolves', async () => {
    vi.useFakeTimers()
    let rejectPoll!: (reason: unknown) => void
    lifecycleApi.submitInitialMatch.mockResolvedValue({ id: 'task001', resumeId: 'resume001', state: 'QUEUED' })
    lifecycleApi.getMatchTask.mockImplementation(() => new Promise<never>((_resolve, reject) => { rejectPoll = reject }))
    const wrapper = mount(UploadMatchView, { global: { stubs: { RouterLink: true } } })
    await flushPromises()
    const fileInput = wrapper.get('input[type="file"]')
    Object.defineProperty(fileInput.element, 'files', { configurable: true, value: [new File(['resume'], 'resume.txt', { type: 'text/plain' })] })
    await fileInput.trigger('change')
    await wrapper.get('textarea').setValue('Java backend engineer with Spring Boot experience.')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    await vi.advanceTimersByTimeAsync(1500)
    wrapper.unmount()
    rejectPoll(new ApiError({ code: 'DUPLICATE_RESOURCE', message: 'private backend detail', correlationId: 'c', retryable: false }, 409))
    await flushPromises()

    expect(notify.mock.calls.some(([message]) => String(message).includes('未成为有效简历'))).toBe(false)
  })
})
