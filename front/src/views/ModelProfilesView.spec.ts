// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ModelProfilesView from './ModelProfilesView.vue'

const { store } = vi.hoisted(() => ({
  store: {
    profiles: [{ id: '1', displayName: 'Saved', endpointUrl: 'https://api.example.com/v1', modelName: 'gpt', hasApiKey: true, selected: false, createdAt: '', updatedAt: '' }],
    list: vi.fn(),
    testConnection: vi.fn().mockResolvedValue({ available: true, testedAt: '', models: ['gpt'] }),
  },
}))
vi.mock('../stores/llmProfiles', () => ({ useLlmProfileStore: () => store }))

const ModelProfileFormStub = {
  props: ['profile'],
  template: '<div><button class="test-saved" @click="$emit(\'test\', { displayName: profile?.displayName || \'Draft\', endpointUrl: profile?.endpointUrl || \'https://api.example.com/v1\', modelName: profile?.modelName || \'gpt\', apiKey: \'\', selected: profile?.selected || false })">Test saved</button><button class="test-changed" @click="$emit(\'test\', { displayName: profile?.displayName || \'Draft\', endpointUrl: profile?.endpointUrl || \'https://api.example.com/v1\', modelName: \'changed\', apiKey: \'new-key\', selected: profile?.selected || false })">Test changed</button></div>',
  emits: ['test'],
  setup() { return { setModels: vi.fn(), setTesting: vi.fn(), clearApiKey: vi.fn() } },
}

describe('ModelProfilesView connection testing', () => {
  beforeEach(() => {
    store.profiles = [{ id: '1', displayName: 'Saved', endpointUrl: 'https://api.example.com/v1', modelName: 'gpt', hasApiKey: true, selected: false, createdAt: '', updatedAt: '' }]
    store.testConnection.mockClear()
    store.list.mockClear()
  })

  it('tests the saved profile only when the edit form has no unsaved changes', async () => {
    const wrapper = mount(ModelProfilesView, { global: { stubs: { ModelProfileForm: ModelProfileFormStub } } })
    await wrapper.get('.profile-row button').trigger('click')
    await wrapper.get('.test-changed').trigger('click')
    expect(store.testConnection).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('Save changes before testing the connection.')

    await wrapper.get('.test-saved').trigger('click')
    await flushPromises()
    expect(store.testConnection).toHaveBeenCalledWith('1')
  })

  it('does not test an unsaved profile', async () => {
    store.profiles = []
    const wrapper = mount(ModelProfilesView, { global: { stubs: { ModelProfileForm: ModelProfileFormStub } } })
    await wrapper.get('.test-changed').trigger('click')
    expect(store.testConnection).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('Save the profile before testing the connection.')
  })
})
