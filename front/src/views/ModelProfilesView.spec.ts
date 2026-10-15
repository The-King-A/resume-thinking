// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ModelProfilesView from './ModelProfilesView.vue'

const savedProfile = { id: 'profile001', displayName: 'Saved', endpointUrl: 'https://api.example.com/v1', modelName: 'manual-model', hasApiKey: true, selected: false, createdAt: '', updatedAt: '' }
const { store, notify } = vi.hoisted(() => ({
  store: { profiles: [] as Array<typeof savedProfile>, list: vi.fn(), create: vi.fn(), update: vi.fn(), scanModels: vi.fn(), testConnection: vi.fn() },
  notify: vi.fn(),
}))
vi.mock('../stores/llmProfiles', () => ({ useLlmProfileStore: () => store }))
vi.mock('../ui/notifications', () => ({ showTopNotification: notify }))

const ModelProfileFormStub = {
  emits: ['save', 'scan', 'cancel'],
  setup(_props: unknown, { expose }: { expose: (value: Record<string, unknown>) => void }) {
    expose({ setModels: vi.fn(), clearApiKey: vi.fn(), requestClose: vi.fn() })
    return {}
  },
  template: `<div><button class="scan-editor" @click="$emit('scan', { endpointUrl: 'https://api.example.com/v1', apiKey: 'scan-key' })">Scan</button><button class="save-editor" @click="$emit('save', { displayName: 'Draft', endpointUrl: 'https://api.example.com/v1', modelName: 'manual-model', apiKey: 'new-key', selected: false })">Save</button></div>`,
}

function mountView() {
  return mount(ModelProfilesView, { global: { stubs: { ModelProfileForm: ModelProfileFormStub, RouterLink: true } } })
}

describe('ModelProfilesView feedback', () => {
  beforeEach(() => {
    store.profiles = [{ ...savedProfile }]
    store.list.mockReset().mockResolvedValue(store.profiles)
    store.create.mockReset().mockResolvedValue({ ...savedProfile, id: 'profile002', displayName: 'Draft' })
    store.update.mockReset().mockResolvedValue({ ...savedProfile })
    store.scanModels.mockReset().mockResolvedValue({ available: true, testedAt: '', models: ['scanned-model'] })
    store.testConnection.mockReset().mockResolvedValue({ available: true, testedAt: '' })
    notify.mockReset()
  })

  it.each([
    ['INVALID_API_KEY', 'API 密钥无效，请检查后重试。'],
    ['PROVIDER_FORBIDDEN', '服务商拒绝访问，请检查权限配置。'],
    ['PROVIDER_ENDPOINT_NOT_FOUND', '服务地址不存在，请检查接口地址。'],
    ['PROVIDER_RATE_LIMITED', '服务商请求过于频繁，请稍后重试。'],
    ['PROVIDER_UNAVAILABLE', '服务商暂时不可用，请稍后重试。'],
    ['PROVIDER_RESPONSE_INVALID', '服务商返回内容无效，请检查服务配置。'],
  ] as const)('reports the exact safe scan message for %s without exposing provider diagnostic text', async (diagnosticCode, expectedMessage) => {
    store.scanModels.mockResolvedValueOnce({ available: false, testedAt: '', models: [], diagnostic: 'provider body', diagnosticCode })
    const wrapper = mountView()
    await wrapper.get('[data-testid="new-profile"]').trigger('click')
    await wrapper.get('.scan-editor').trigger('click')
    await flushPromises()
    expect(notify).toHaveBeenCalledWith(expectedMessage, 'error')
    expect(wrapper.text()).not.toContain('provider body')
  })

  it('reports a successful scan', async () => {
    const wrapper = mountView()
    await wrapper.get('[data-testid="new-profile"]').trigger('click')
    await wrapper.get('.scan-editor').trigger('click')
    await flushPromises()
    expect(notify).toHaveBeenCalledWith('模型列表已更新。', 'success')
  })

  it('reports a saved but unavailable test and retains the editor', async () => {
    store.profiles = []
    store.testConnection.mockResolvedValueOnce({ available: false, testedAt: '', diagnostic: 'provider body', diagnosticCode: 'PROVIDER_FORBIDDEN' })
    const wrapper = mountView()
    await wrapper.get('[data-testid="new-profile"]').trigger('click')
    await wrapper.get('.save-editor').trigger('click')
    await flushPromises()
    expect(wrapper.get('[role="dialog"]').text()).toContain('编辑模型配置')
    expect(notify).toHaveBeenCalledWith(expect.any(String), 'error')
    expect(wrapper.text()).not.toContain('provider body')
  })

  it('reports a successful save and test then closes the editor', async () => {
    store.profiles = []
    const wrapper = mountView()
    await wrapper.get('[data-testid="new-profile"]').trigger('click')
    await wrapper.get('.save-editor').trigger('click')
    await flushPromises()
    expect(notify).toHaveBeenCalledWith('模型配置已保存，连接可用。', 'success')
    expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
  })

  it('continues a successful save and test when notification rendering throws', async () => {
    store.profiles = []
    notify.mockImplementationOnce(() => { throw new Error('notification renderer failed') })
    const wrapper = mountView()
    await wrapper.get('[data-testid="new-profile"]').trigger('click')
    await wrapper.get('.save-editor').trigger('click')
    await flushPromises()
    expect(store.create).toHaveBeenCalledOnce()
    expect(store.testConnection).toHaveBeenCalledWith('profile002')
    expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
  })
})
