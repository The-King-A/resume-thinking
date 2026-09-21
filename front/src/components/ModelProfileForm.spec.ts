// @vitest-environment jsdom
import { describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ModelProfileForm from './ModelProfileForm.vue'

const savedProfile = { id: 'profile001', displayName: 'Saved', endpointUrl: 'https://api.deepseek.com/v1', modelName: 'manual-model', hasApiKey: true, selected: false, createdAt: '', updatedAt: '' }

describe('ModelProfileForm', () => {
  it('uses normal fields as the save payload while keeping the key out of advanced TOML', async () => {
    const wrapper = mount(ModelProfileForm)
    await wrapper.get('[data-testid="profile-name"]').setValue('Production')
    await wrapper.get('[data-testid="endpoint-url"]').setValue('https://api.example.com/v1')
    await wrapper.get('[data-testid="model-name"]').setValue('manual-model')
    await wrapper.get('[data-testid="api-key-input"]').setValue('new-profile-key')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(wrapper.emitted('save')?.[0]?.[0]).toEqual({ displayName: 'Production', endpointUrl: 'https://api.example.com/v1', modelName: 'manual-model', apiKey: 'new-profile-key', selected: false })
    expect((wrapper.get('[data-testid="model-profile-toml"]').element as HTMLTextAreaElement).value).not.toContain('new-profile-key')
  })

  it('shows the three OpenAI-compatible provider choices', () => {
    const wrapper = mount(ModelProfileForm)

    expect(wrapper.get('[data-testid="provider-preset"]').findAll('option').map((option) => option.text())).toEqual([
      'GPT / OpenAI',
      'Claude（OpenAI 兼容）',
      '自定义（OpenAI 兼容）',
    ])
  })

  it('preserves an existing DeepSeek endpoint when 自定义（OpenAI 兼容） is selected', async () => {
    const wrapper = mount(ModelProfileForm, { props: { profile: savedProfile } })
    const provider = wrapper.get('[data-testid="provider-preset"]')
    const endpoint = wrapper.get('[data-testid="endpoint-url"]')
    await provider.setValue('CUSTOM')
    expect((endpoint.element as HTMLInputElement).value).toBe('https://api.deepseek.com/v1')
  })

  it('lets a new profile switch directly from OpenAI to Custom before entering an endpoint', async () => {
    const wrapper = mount(ModelProfileForm)
    const provider = wrapper.get('[data-testid="provider-preset"]')
    await provider.setValue('CUSTOM')

    expect((provider.element as HTMLSelectElement).value).toBe('CUSTOM')
    expect((wrapper.get('[data-testid="endpoint-url"]').element as HTMLInputElement).value).toBe('https://api.openai.com/v1')
  })

  it('switches the provider preset to 自定义 when the endpoint is edited directly', async () => {
    const wrapper = mount(ModelProfileForm)
    await wrapper.get('[data-testid="endpoint-url"]').setValue('https://api.deepseek.com/v1')

    expect((wrapper.get('[data-testid="provider-preset"]').element as HTMLSelectElement).value).toBe('CUSTOM')
  })

  it('switches the provider preset back to OpenAI when the canonical endpoint is restored', async () => {
    const wrapper = mount(ModelProfileForm)
    const endpoint = wrapper.get('[data-testid="endpoint-url"]')
    await endpoint.setValue('https://api.deepseek.com/v1')
    await endpoint.setValue('https://api.openai.com/v1')

    expect((wrapper.get('[data-testid="provider-preset"]').element as HTMLSelectElement).value).toBe('OPENAI')
  })

  it('uses the Claude OpenAI-compatible endpoint while preserving an editable model and recognizes it after direct edits', async () => {
    const wrapper = mount(ModelProfileForm)
    const provider = wrapper.get('[data-testid="provider-preset"]')
    const endpoint = wrapper.get('[data-testid="endpoint-url"]')
    const model = wrapper.get('[data-testid="model-name"]')

    await provider.setValue('CLAUDE')
    expect((endpoint.element as HTMLInputElement).value).toBe('https://api.anthropic.com/v1')
    expect(model.attributes('disabled')).toBeUndefined()
    await model.setValue('claude-sonnet')
    expect((model.element as HTMLInputElement).value).toBe('claude-sonnet')

    await endpoint.setValue('https://api.deepseek.com/v1')
    expect((provider.element as HTMLSelectElement).value).toBe('CUSTOM')
    await endpoint.setValue('https://api.anthropic.com/v1')
    expect((provider.element as HTMLSelectElement).value).toBe('CLAUDE')
  })

  it('opens an existing non-OpenAI endpoint as Custom without losing its normal form values', () => {
    const wrapper = mount(ModelProfileForm, { props: { profile: savedProfile } })
    expect((wrapper.get('[data-testid="provider-preset"]').element as HTMLSelectElement).value).toBe('CUSTOM')
    expect((wrapper.get('[data-testid="endpoint-url"]').element as HTMLInputElement).value).toBe('https://api.deepseek.com/v1')
    expect((wrapper.get('[data-testid="model-name"]').element as HTMLInputElement).value).toBe('manual-model')
  })

  it('keeps TOML collapsed until requested and applies a valid non-secret projection to normal fields', async () => {
    const wrapper = mount(ModelProfileForm, { props: { profile: savedProfile } })
    const details = wrapper.get('[data-testid="advanced-toml"]')
    expect(details.attributes('open')).toBeUndefined()
    expect((wrapper.get('[data-testid="model-profile-toml"]').element as HTMLTextAreaElement).value).not.toContain('api_key')
    await wrapper.get('[data-testid="show-advanced-toml"]').trigger('click')
    await wrapper.get('[data-testid="model-profile-toml"]').setValue(`[profile]\ndisplay_name = "Imported"\nendpoint_url = "https://api.example.com/v1"\nmodel_name = "imported-model"\nselected = true\n`)
    await wrapper.get('[data-testid="apply-toml"]').trigger('click')
    expect((wrapper.get('[data-testid="profile-name"]').element as HTMLInputElement).value).toBe('Imported')
    expect((wrapper.get('[data-testid="endpoint-url"]').element as HTMLInputElement).value).toBe('https://api.example.com/v1')
    expect((wrapper.get('[data-testid="model-name"]').element as HTMLInputElement).value).toBe('imported-model')
    expect((wrapper.get('[data-testid="selected-profile"]').element as HTMLInputElement).checked).toBe(true)
  })

  it.each([
    `[profile]\ndisplay_name = "Rejected"\nendpoint_url = "https://api.invalid/v1"\nmodel_name = "rejected-model"\nselected = false\napi_key = "redacted"\n`,
    `[profile]\ndisplay_name = "Rejected"\nendpoint_url = "https://api.invalid/v1"\nmodel_name = "rejected-model"\nselected = false\nunknown_setting = "unexpected"\n`,
  ])('rejects an advanced TOML document with undeclared fields without changing normal form values', async (invalidToml) => {
    const wrapper = mount(ModelProfileForm, { props: { profile: savedProfile } })
    await wrapper.get('[data-testid="model-profile-toml"]').setValue(invalidToml)
    await wrapper.get('[data-testid="apply-toml"]').trigger('click')

    expect(wrapper.get('[role="alert"]').text()).toContain('TOML 配置格式无效')
    expect((wrapper.get('[data-testid="profile-name"]').element as HTMLInputElement).value).toBe('Saved')
    expect((wrapper.get('[data-testid="endpoint-url"]').element as HTMLInputElement).value).toBe('https://api.deepseek.com/v1')
    expect((wrapper.get('[data-testid="model-name"]').element as HTMLInputElement).value).toBe('manual-model')
    expect((wrapper.get('[data-testid="selected-profile"]').element as HTMLInputElement).checked).toBe(false)
    expect(wrapper.emitted('save')).toBeFalsy()
    expect(wrapper.emitted('scan')).toBeFalsy()
  })

  it('copies a scanned choice into the editable model field without disabling manual entry', async () => {
    const wrapper = mount(ModelProfileForm, { props: { profile: savedProfile } })
    ;(wrapper.vm as unknown as { setModels: (models: string[]) => void }).setModels(['scanned-model'])
    await wrapper.vm.$nextTick()
    await wrapper.get('[data-testid="scanned-models"]').setValue('scanned-model')
    const model = wrapper.get('[data-testid="model-name"]')
    expect((model.element as HTMLInputElement).value).toBe('scanned-model')
    expect(model.attributes('disabled')).toBeUndefined()
    await model.setValue('typed-after-scan')
    expect((model.element as HTMLInputElement).value).toBe('typed-after-scan')
  })

  it('emits a transient scan payload without a profile identifier or model name', async () => {
    const wrapper = mount(ModelProfileForm, { props: { profile: savedProfile } })
    await wrapper.get('[data-testid="api-key-input"]').setValue('scan-only-key')
    await wrapper.get('[data-testid="scan-models"]').trigger('click')
    await flushPromises()
    expect(wrapper.emitted('scan')?.[0]?.[0]).toEqual({ endpointUrl: 'https://api.deepseek.com/v1', apiKey: 'scan-only-key' })
  })

  it('does not emit a scan without a separately entered key', async () => {
    const wrapper = mount(ModelProfileForm, { props: { profile: savedProfile } })
    await wrapper.get('[data-testid="scan-models"]').trigger('click')
    await flushPromises()
    expect(wrapper.emitted('scan')).toBeFalsy()
    expect(wrapper.text()).toContain('请输入 API 密钥')
  })

  it('omits a blank key when saving an existing profile', async () => {
    const wrapper = mount(ModelProfileForm, { props: { profile: savedProfile } })
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(wrapper.emitted('save')?.[0]?.[0]).toEqual({ displayName: 'Saved', endpointUrl: 'https://api.deepseek.com/v1', modelName: 'manual-model', selected: false })
  })

  it('uses directly edited advanced TOML values when saving', async () => {
    const wrapper = mount(ModelProfileForm, { props: { profile: savedProfile } })
    await wrapper.get('[data-testid="model-profile-toml"]').setValue(`[profile]\ndisplay_name = "Imported"\nendpoint_url = "https://api.example.com/v1"\nmodel_name = "imported-model"\nselected = true\n`)
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(wrapper.emitted('save')?.[0]?.[0]).toEqual({
      displayName: 'Imported',
      endpointUrl: 'https://api.example.com/v1',
      modelName: 'imported-model',
      selected: true,
    })
  })

  it('rejects invalid advanced TOML before scanning', async () => {
    const wrapper = mount(ModelProfileForm, { props: { profile: savedProfile } })
    await wrapper.get('[data-testid="model-profile-toml"]').setValue('[profile]\ndisplay_name = "Incomplete"')
    await wrapper.get('[data-testid="api-key-input"]').setValue('scan-key')
    await wrapper.get('[data-testid="scan-models"]').trigger('click')
    await flushPromises()

    expect(wrapper.emitted('scan')).toBeFalsy()
    expect(wrapper.get('[role="alert"]').text()).toContain('TOML 配置格式无效')
  })

  it('does not close the editor while an operation is in progress', () => {
    const wrapper = mount(ModelProfileForm, { props: { profile: savedProfile, scanning: true } })
    ;(wrapper.vm as unknown as { requestClose: () => void }).requestClose()
    expect(wrapper.emitted('cancel')).toBeFalsy()
  })

  it('confirms before closing a dirty editor', async () => {
    const confirm = vi.spyOn(window, 'confirm').mockReturnValueOnce(false).mockReturnValueOnce(true)
    const wrapper = mount(ModelProfileForm, { props: { profile: savedProfile } })
    await wrapper.get('[data-testid="model-name"]').setValue('changed-model')
    await wrapper.get('[data-testid="cancel-editor"]').trigger('click')
    expect(wrapper.emitted('cancel')).toBeFalsy()
    await wrapper.get('[data-testid="cancel-editor"]').trigger('click')
    expect(confirm).toHaveBeenCalledTimes(2)
    expect(wrapper.emitted('cancel')).toHaveLength(1)
  })

  it('confirms before closing after editing advanced TOML without applying it', async () => {
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false)
    confirm.mockClear()
    const wrapper = mount(ModelProfileForm, { props: { profile: savedProfile } })
    await wrapper.get('[data-testid="model-profile-toml"]').setValue('[profile]\ndisplay_name = "draft"')
    await wrapper.get('[data-testid="cancel-editor"]').trigger('click')

    expect(confirm).toHaveBeenCalledOnce()
    expect(wrapper.emitted('cancel')).toBeFalsy()
  })
})
