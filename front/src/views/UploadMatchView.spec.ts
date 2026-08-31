// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import UploadMatchView from './UploadMatchView.vue'

const { lifecycleApi, profiles } = vi.hoisted(() => ({
  lifecycleApi: { uploadResume: vi.fn(), createMatchTask: vi.fn(), getMatchTask: vi.fn() },
  profiles: { profiles: [{ id: 'profile001', displayName: 'Default model', selected: true }, { id: 'profile002', displayName: 'Alternate model', selected: false }], list: vi.fn().mockResolvedValue(undefined) },
}))
vi.mock('../api/lifecycle', () => ({ lifecycleApi }))
vi.mock('../stores/llmProfiles', () => ({ useLlmProfileStore: () => profiles }))

const mountedWrappers: Array<{ unmount: () => void }> = []

afterEach(() => {
  for (const wrapper of mountedWrappers) wrapper.unmount()
  mountedWrappers.length = 0
  vi.useRealTimers()
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
    lifecycleApi.uploadResume.mockResolvedValue({ id: 'resume001' })
    lifecycleApi.createMatchTask.mockResolvedValue({ id: 'task001', state: 'QUEUED' })
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

    expect(lifecycleApi.uploadResume).toHaveBeenCalledWith(file, undefined)
    expect(lifecycleApi.createMatchTask).toHaveBeenCalledWith(expect.objectContaining({
      resumeId: 'resume001', llmProfileId: 'profile001', jobFamily: 'JAVA_BACKEND', jobDescriptionText: 'Java backend engineer with Spring Boot experience.',
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
    lifecycleApi.uploadResume.mockResolvedValue({ id: 'resume001' })
    lifecycleApi.createMatchTask.mockResolvedValue({ id: 'task001', state: 'QUEUED' })
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
})
