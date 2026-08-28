// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ModelProfilesView from './ModelProfilesView.vue'

const { store } = vi.hoisted(() => ({
  store: {
    profiles: [{ id: '1', displayName: 'Saved', endpointUrl: 'https://api.example.com/v1', modelName: 'gpt', hasApiKey: true, selected: false, createdAt: '', updatedAt: '' }],
    list: vi.fn(),
    create: vi.fn().mockResolvedValue({ id: '2', displayName: 'New', endpointUrl: 'https://api.example.com/v1', modelName: 'gpt', hasApiKey: true, selected: false, createdAt: '', updatedAt: '' }),
    update: vi.fn().mockResolvedValue({ id: '1', displayName: 'Saved', endpointUrl: 'https://api.example.com/v1', modelName: 'gpt', hasApiKey: true, selected: false, createdAt: '', updatedAt: '' }),
    testConnection: vi.fn().mockResolvedValue({ available: true, testedAt: '', models: ['gpt'] }),
  },
}))
vi.mock('../stores/llmProfiles', () => ({ useLlmProfileStore: () => store }))

const ModelProfileFormStub = {
  props: ['profile'],
  template: '<div><button class="test-saved" @click="$emit(\'test\', { displayName: profile?.displayName || \'Draft\', endpointUrl: profile?.endpointUrl || \'https://api.example.com/v1\', modelName: profile?.modelName || \'gpt\', apiKey: \'\', selected: profile?.selected || false })">Test saved</button><button class="test-changed" @click="$emit(\'test\', { displayName: profile?.displayName || \'Draft\', endpointUrl: profile?.endpointUrl || \'https://api.example.com/v1\', modelName: \'changed\', apiKey: \'new-key\', selected: profile?.selected || false })">Test changed</button><button class="save-draft" @click="$emit(\'save\', { displayName: profile?.displayName || \'Draft\', endpointUrl: profile?.endpointUrl || \'https://api.example.com/v1\', modelName: profile?.modelName || \'gpt\', apiKey: \'new-key\', selected: profile?.selected || false })">Save</button><button class="save-unchanged" @click="$emit(\'save\', { displayName: profile?.displayName || \'Draft\', endpointUrl: profile?.endpointUrl || \'https://api.example.com/v1\', modelName: profile?.modelName || \'gpt\', selected: profile?.selected || false })">Save unchanged</button></div>',
  emits: ['test', 'save'],
  setup() { return { setModels: vi.fn(), setTesting: vi.fn(), clearApiKey: vi.fn() } },
}

describe('ModelProfilesView connection testing', () => {
  beforeEach(() => {
    store.profiles = [{ id: '1', displayName: 'Saved', endpointUrl: 'https://api.example.com/v1', modelName: 'gpt', hasApiKey: true, selected: false, createdAt: '', updatedAt: '' }]
    store.testConnection.mockClear()
    store.list.mockClear()
    store.create.mockReset()
    store.update.mockReset()
    store.create.mockResolvedValue({ id: '2', displayName: 'New', endpointUrl: 'https://api.example.com/v1', modelName: 'gpt', hasApiKey: true, selected: false, createdAt: '', updatedAt: '' })
    store.update.mockResolvedValue({ id: '1', displayName: 'Saved', endpointUrl: 'https://api.example.com/v1', modelName: 'gpt', hasApiKey: true, selected: false, createdAt: '', updatedAt: '' })
  })

  it('tests the saved profile only when the edit form has no unsaved changes', async () => {
    const wrapper = mount(ModelProfilesView, { global: { stubs: { ModelProfileForm: ModelProfileFormStub } } })
    await wrapper.get('.profile-row button').trigger('click')
    await wrapper.get('.test-changed').trigger('click')
    expect(store.testConnection).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('请先保存更改，再测试连接。')

    await wrapper.get('.test-saved').trigger('click')
    await flushPromises()
    expect(store.testConnection).toHaveBeenCalledWith('1')
  })

  it('does not test an unsaved profile', async () => {
    store.profiles = []
    const wrapper = mount(ModelProfilesView, { global: { stubs: { ModelProfileForm: ModelProfileFormStub } } })
    await wrapper.get('.test-changed').trigger('click')
    expect(store.testConnection).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('请先保存模型配置，再测试连接。')
  })

  it('shows a safe message when profile listing fails', async () => {
    store.list.mockRejectedValueOnce(new Error('backend details'))
    const wrapper = mount(ModelProfilesView, { global: { stubs: { ModelProfileForm: ModelProfileFormStub } } })
    await flushPromises()
    expect(wrapper.text()).toContain('无法加载模型配置。')
    expect(wrapper.text()).not.toContain('backend details')
  })

  it('shows a safe message when creating a profile fails', async () => {
    store.profiles = []
    store.create.mockRejectedValueOnce(new Error('backend details'))
    const wrapper = mount(ModelProfilesView, { global: { stubs: { ModelProfileForm: ModelProfileFormStub } } })
    await wrapper.get('.save-draft').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('无法保存模型配置。')
    expect(wrapper.text()).not.toContain('backend details')
  })

  it('shows a safe message when updating a profile fails', async () => {
    store.update.mockRejectedValueOnce(new Error('backend details'))
    const wrapper = mount(ModelProfilesView, { global: { stubs: { ModelProfileForm: ModelProfileFormStub } } })
    await wrapper.get('.profile-row button').trigger('click')
    await wrapper.get('.save-draft').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('无法保存模型配置。')
    expect(wrapper.text()).not.toContain('backend details')
  })

  it('updates a saved profile when the key is intentionally omitted', async () => {
    const wrapper = mount(ModelProfilesView, { global: { stubs: { ModelProfileForm: ModelProfileFormStub } } })
    await wrapper.get('.profile-row button').trigger('click')
    await wrapper.get('.save-unchanged').trigger('click')
    await flushPromises()
    expect(store.update).toHaveBeenCalledTimes(1)
    expect(store.update.mock.calls[0]?.[1]).not.toHaveProperty('apiKey')
  })

  it('clears the password only after a successful parent save', async () => {
    store.profiles = []
    store.create.mockResolvedValueOnce({ id: '2', displayName: 'New', endpointUrl: 'https://api.example.com/v1', modelName: 'gpt', hasApiKey: true, selected: false, createdAt: '', updatedAt: '' })
    const wrapper = mount(ModelProfilesView, { global: { stubs: { RouterLink: true } } })
    await wrapper.get('input').setValue('New')
    await wrapper.get('input[placeholder="例如 gpt-4o-mini"]').setValue('gpt')
    const key = wrapper.get('input[autocomplete="new-password"]')
    await key.setValue('success-key')
    await wrapper.get('button[type="submit"]').trigger('click')
    await flushPromises()
    expect((key.element as HTMLInputElement).value).toBe('')
  })

  it('retains the password when the parent save fails', async () => {
    store.profiles = []
    store.create.mockRejectedValueOnce(new Error('backend details'))
    const wrapper = mount(ModelProfilesView, { global: { stubs: { RouterLink: true } } })
    await wrapper.get('input').setValue('New')
    await wrapper.get('input[placeholder="例如 gpt-4o-mini"]').setValue('gpt')
    const key = wrapper.get('input[autocomplete="new-password"]')
    await key.setValue('failed-key')
    await wrapper.get('button[type="submit"]').trigger('click')
    await flushPromises()
    expect((key.element as HTMLInputElement).value).toBe('failed-key')
  })
})
