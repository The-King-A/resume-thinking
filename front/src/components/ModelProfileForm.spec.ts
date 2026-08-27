// @vitest-environment jsdom
import { describe, expect, it } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
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
    await flushPromises()
    expect(wrapper.emitted('save')).toBeFalsy()
  })

  it('retains the entered key when the save handler fails', async () => {
    const wrapper = mount(ModelProfileForm, { props: { profile: null } })
    await wrapper.get('input[autocomplete="new-password"]').setValue('new-secret')
    await wrapper.get('input[placeholder="e.g. gpt-4o-mini"]').setValue('gpt-4o-mini')
    await wrapper.get('button[type="submit"]').trigger('click')
    await flushPromises()
    expect((wrapper.get('input[autocomplete="new-password"]').element as HTMLInputElement).value).toBe('new-secret')
  })

  it('shows validation and does not emit for an invalid draft', async () => {
    const wrapper = mount(ModelProfileForm, { props: { profile: null } })
    await wrapper.get('button[type="submit"]').trigger('click')
    await flushPromises()
    expect(wrapper.emitted('save')).toBeFalsy()
    expect(wrapper.text()).toContain('Profile name is required')
  })

  it('does not emit a profile with an OpenAPI-invalid display name', async () => {
    const wrapper = mount(ModelProfileForm, { props: { profile: null } })
    await wrapper.get('input').setValue('x'.repeat(101))
    await wrapper.get('input[placeholder="e.g. gpt-4o-mini"]').setValue('gpt-4o-mini')
    await wrapper.get('input[autocomplete="new-password"]').setValue('draft-key')
    await wrapper.get('button[type="submit"]').trigger('click')
    await flushPromises()
    expect(wrapper.emitted('save')).toBeFalsy()
    expect(wrapper.text()).toContain('Profile name must be at most 100 characters')
  })

  it('does not emit an invalid draft for a connection test', async () => {
    const wrapper = mount(ModelProfileForm, { props: { profile: null } })
    await wrapper.get('input[autocomplete="new-password"]').setValue('draft-key')
    await wrapper.get('button[type="button"]').trigger('click')
    await flushPromises()
    expect(wrapper.emitted('test')).toBeFalsy()
    expect(wrapper.text()).toContain('Profile name is required')
  })

})
