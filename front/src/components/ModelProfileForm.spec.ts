// @vitest-environment jsdom
import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import ModelProfileForm from './ModelProfileForm.vue'

describe('ModelProfileForm', () => {
  it('never renders a saved API key', () => {
    const wrapper = mount(ModelProfileForm, {
      props: {
        profile: {
          id: '1', displayName: 'Saved', endpointUrl: 'https://api.example.com', modelName: 'gpt',
          hasApiKey: true, selected: false, createdAt: '', updatedAt: '',
        },
      },
    })
    expect(wrapper.text()).not.toContain('secret-api-key')
  })

  it('does not emit an empty key when updating a saved profile', async () => {
    const wrapper = mount(ModelProfileForm, { props: { profile: { id: '1', displayName: 'Saved', endpointUrl: 'https://api.example.com', modelName: 'gpt', hasApiKey: true, selected: false, createdAt: '', updatedAt: '' } } })
    await wrapper.get('button[type="submit"]').trigger('click')
    expect(wrapper.emitted('save')).toBeFalsy()
  })

  it('retains the entered key when the save handler fails', async () => {
    const wrapper = mount(ModelProfileForm, { props: { profile: null } })
    await wrapper.get('input[autocomplete="new-password"]').setValue('new-secret')
    await wrapper.get('input[placeholder="e.g. gpt-4o-mini"]').setValue('gpt-4o-mini')
    await wrapper.get('button[type="submit"]').trigger('click')
    expect((wrapper.get('input[autocomplete="new-password"]').element as HTMLInputElement).value).toBe('new-secret')
  })

  it('shows validation and does not emit for an invalid draft', async () => {
    const wrapper = mount(ModelProfileForm, { props: { profile: null } })
    await wrapper.get('button[type="submit"]').trigger('click')
    expect(wrapper.emitted('save')).toBeFalsy()
    expect(wrapper.text()).toContain('Profile name is required')
  })

  it('emits the current draft for a pre-save connection test', async () => {
    const wrapper = mount(ModelProfileForm, { props: { profile: null } })
    await wrapper.get('input[autocomplete="new-password"]').setValue('draft-key')
    await wrapper.get('button[type="button"]').trigger('click')
    expect(wrapper.emitted('test')?.[0]?.[0]).toEqual(expect.objectContaining({ apiKey: 'draft-key' }))
  })
})
