// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import UploadMatchView from './UploadMatchView.vue'

const { lifecycleApi, profiles } = vi.hoisted(() => ({
  lifecycleApi: { uploadResume: vi.fn(), createMatchTask: vi.fn() },
  profiles: { profiles: [{ id: 'profile-1', displayName: 'Default model', selected: true }, { id: 'profile-2', displayName: 'Alternate model', selected: false }], list: vi.fn().mockResolvedValue(undefined) },
}))
vi.mock('../api/lifecycle', () => ({ lifecycleApi }))
vi.mock('../stores/llmProfiles', () => ({ useLlmProfileStore: () => profiles }))

describe('UploadMatchView', () => {
  it('accepts only TXT and DOCX and explicitly rejects PDF', async () => {
    const wrapper = mount(UploadMatchView, { global: { stubs: { RouterLink: true } } })
    const fileInput = wrapper.get('input[type="file"]')
    expect(fileInput.attributes('accept')).toBe('.txt,.docx')
    Object.defineProperty(fileInput.element, 'files', { configurable: true, value: [new File(['pdf'], 'resume.pdf', { type: 'application/pdf' })] })
    await fileInput.trigger('change')
    expect(wrapper.text()).toContain('PDF files are not supported')
    expect(wrapper.get('[data-test="start-match"]').attributes('disabled')).toBeDefined()
  })

  it('sends the uploaded resume, selected profile, Java job text, and an idempotency key', async () => {
    lifecycleApi.uploadResume.mockResolvedValue({ id: 'resume-1' })
    lifecycleApi.createMatchTask.mockResolvedValue({ id: 'task-1', state: 'QUEUED' })
    const wrapper = mount(UploadMatchView, { global: { stubs: { RouterLink: true } } })
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
      resumeId: 'resume-1', llmProfileId: 'profile-1', jobDescriptionText: 'Java backend engineer with Spring Boot experience.',
      idempotencyKey: expect.stringMatching(/^match-/),
    }))
    expect(wrapper.text()).toContain('Queued')
  })

  it('allows choosing any saved profile, while defaulting to the selected one', async () => {
    const wrapper = mount(UploadMatchView, { global: { stubs: { RouterLink: true } } })
    await flushPromises()
    expect(wrapper.get('select').element.value).toBe('profile-1')
    expect(wrapper.text()).toContain('Alternate model')
    await wrapper.get('select').setValue('profile-2')
    expect((wrapper.get('select').element as HTMLSelectElement).value).toBe('profile-2')
  })
})
