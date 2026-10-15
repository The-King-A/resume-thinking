// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

const { request } = vi.hoisted(() => ({ request: vi.fn() }))
vi.mock('../api/http', () => ({ request }))

import { useLlmProfileStore } from './llmProfiles'

describe('LLM profile transient scans', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    request.mockReset()
  })

  it('posts only the editor endpoint and key without persisting scan state', async () => {
    request.mockResolvedValue({ available: true, testedAt: '2026-09-01T00:00:00Z', models: ['deepseek-chat'] })
    const store = useLlmProfileStore()

    await store.scanModels({ endpointUrl: 'https://api.deepseek.com/v1', apiKey: 'scan-only-key' })

    expect(request).toHaveBeenCalledWith({
      method: 'POST',
      url: '/api/v2/llm-profiles/scan',
      data: { endpointUrl: 'https://api.deepseek.com/v1', apiKey: 'scan-only-key' },
    })
    expect(store.profiles).toEqual([])
    expect(JSON.stringify(store.$state)).not.toContain('scan-only-key')
  })
})
